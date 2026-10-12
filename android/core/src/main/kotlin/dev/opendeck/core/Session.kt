package dev.opendeck.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One binary WebSocket frame per protocol message (spec §2). */
interface FrameTransport {
    fun send(frame: ByteArray)
    fun close(code: Int, reason: String)
}

enum class SessionState { AwaitingHello, Unauthenticated, Ready, DeviceOpen, Closed }

interface SessionListener {
    /** Every decoded server message, including the hello. Called on the transport's thread. */
    fun onMessage(message: ServerMessage) {}
    fun onClosed(code: Int, reason: String) {}
}

class SessionException(message: String, val errorCode: Int? = null) : Exception(message)

object CloseCodes {
    const val GOING_AWAY = 1001
    const val POLICY_VIOLATION = 1008
}

/**
 * Drives the VSD2 handshake, encryption and request/reply matching (spec §3, §5) over an
 * abstract [FrameTransport]. The transport must feed received frames into [onBinaryFrame] /
 * [onTextFrame] and report closure via [onTransportClosed].
 */
class Session(
    private val transport: FrameTransport,
    private val keyPair: KeyPair,
    private val clientInfo: ClientInformation,
    private val expectedWorkstationID: String? = null,
    private val listener: SessionListener = object : SessionListener {},
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val PROTOCOL_VERSION = 2
        const val RPC_TIMEOUT_MS = 30_000L
        const val DEVICE_LIST_TIMEOUT_MS = 20_000L
    }

    private val lock = Any()
    private val _state = MutableStateFlow(SessionState.AwaitingHello)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<VirtualDevice>?>(null)
    /** Latest `virtualDeviceList` snapshot, null until the first one arrives. */
    val devices: StateFlow<List<VirtualDevice>?> = _devices.asStateFlow()

    @Volatile var serverHello: ServerMessage.Hello? = null
        private set
    @Volatile var closeReason: String? = null
        private set
    @Volatile var currentDevice: VirtualDevice? = null
        private set

    @Volatile private var sharedKey: ByteArray? = null
    @Volatile private var authRequestID: Int? = null
    private var helloSent = false
    private val nextRequestID = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ServerMessage>>()

    /** 6 uppercase hex chars the user compares with the desktop prompt. */
    val pairingCode: String get() = CryptoBox.pairingCode(keyPair.publicKey)

    /** Sends the plaintext client hello. Call once, after the socket is open. */
    fun start() {
        synchronized(lock) {
            check(!helloSent) { "hello already sent" }
            helloSent = true
        }
        transport.send(Proto.encodeClient(ClientMessage.Hello(keyPair.publicKey, clientInfo)))
    }

    // ---------- transport callbacks ----------

    fun onBinaryFrame(frame: ByteArray) {
        if (_state.value == SessionState.Closed) return
        val key = sharedKey
        val msg = try {
            Proto.decodeServer(if (key == null) frame else CryptoBox.openFrame(frame, key))
        } catch (e: Exception) {
            // Unlike the original client we never fall back to plaintext after a decryption failure.
            protocolError("invalid frame: ${e.message}")
            return
        }
        if (key == null) handleHello(msg) else dispatch(msg)
    }

    fun onTextFrame() = protocolError("unexpected text frame")

    fun onTransportClosed(code: Int, reason: String) = finish(code, reason, closeTransport = false)

    fun close(reason: String = "client closing") {
        // Withdraw a pending pairing request, otherwise the desktop keeps it open and rejects
        // later attempts with ResourceLimitReached.
        cancelAuthentication()
        finish(CloseCodes.GOING_AWAY, reason, closeTransport = true)
    }

    private fun protocolError(reason: String) = finish(CloseCodes.POLICY_VIOLATION, reason, closeTransport = true)

    private fun finish(code: Int, reason: String, closeTransport: Boolean) {
        synchronized(lock) {
            if (_state.value == SessionState.Closed) return
            closeReason = reason
            _state.value = SessionState.Closed
        }
        if (closeTransport) transport.close(code, reason)
        val err = SessionException("connection closed: $reason")
        pending.values.forEach { it.completeExceptionally(err) }
        pending.clear()
        listener.onClosed(code, reason)
    }

    private fun handleHello(msg: ServerMessage) {
        if (msg !is ServerMessage.Hello) return protocolError("first server message is not hello")
        if (msg.publicKey.size != 32) return protocolError("bad server public key")
        if (expectedWorkstationID != null && msg.workstationID != expectedWorkstationID) {
            return finish(CloseCodes.GOING_AWAY, "unexpected workstation", closeTransport = true)
        }
        sharedKey = try {
            CryptoBox.beforenm(msg.publicKey, keyPair.secretKey)
        } catch (e: CryptoException) {
            return protocolError("bad server public key")
        }
        serverHello = msg
        val next = if (msg.clientNeedsAuthentication) SessionState.Unauthenticated else SessionState.Ready
        _state.compareAndSet(SessionState.AwaitingHello, next)
        listener.onMessage(msg)
    }

    private fun dispatch(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Hello -> return protocolError("duplicate hello")
            is ServerMessage.Ok -> pending.remove(msg.requestID)?.complete(msg)
            is ServerMessage.Error -> pending.remove(msg.requestID)?.complete(msg)
            is ServerMessage.VirtualDeviceOpened -> pending.remove(msg.requestID)?.complete(msg)
            is ServerMessage.VirtualDeviceList -> _devices.value = msg.devices
            else -> Unit
        }
        listener.onMessage(msg)
    }

    // ---------- requests ----------

    private fun sendEncrypted(msg: ClientMessage) {
        val key = sharedKey ?: throw IllegalStateException("no session key yet")
        transport.send(CryptoBox.sealFrame(Proto.encodeClient(msg), key, random))
    }

    private suspend fun request(timeoutMs: Long?, build: (Int) -> ClientMessage): ServerMessage {
        val id = nextRequestID.getAndIncrement()
        val reply = CompletableDeferred<ServerMessage>()
        pending[id] = reply
        try {
            if (_state.value == SessionState.Closed) throw SessionException("connection closed: $closeReason")
            sendEncrypted(build(id))
            val r = if (timeoutMs == null) reply.await() else withTimeoutOrNull(timeoutMs) { reply.await() }
            val result = r ?: throw SessionException("timed out waiting for reply")
            if (result is ServerMessage.Error) {
                throw SessionException(result.reason?.takeIf { it.isNotEmpty() } ?: ErrorCode.name(result.code), result.code)
            }
            return result
        } finally {
            pending.remove(id)
        }
    }

    private fun requireState(vararg allowed: SessionState) {
        val s = _state.value
        if (s !in allowed) throw SessionException("invalid session state $s")
    }

    /** Pairing: suspends until the user accepts or rejects on the desktop (or [cancelAuthentication]). */
    suspend fun authenticate() {
        requireState(SessionState.Unauthenticated)
        try {
            request(null) { id -> authRequestID = id; ClientMessage.Authenticate(id) }
        } finally {
            authRequestID = null
        }
        _state.compareAndSet(SessionState.Unauthenticated, SessionState.Ready)
    }

    fun cancelAuthentication() {
        val id = authRequestID ?: return
        if (_state.value != SessionState.Closed) sendEncrypted(ClientMessage.CancelAuthenticate(id))
        pending.remove(id)?.completeExceptionally(SessionException(ErrorCode.name(ErrorCode.CANCELLED), ErrorCode.CANCELLED))
    }

    /** Waits for the first `virtualDeviceList` snapshot. */
    suspend fun awaitDeviceList(timeoutMs: Long = DEVICE_LIST_TIMEOUT_MS): List<VirtualDevice> {
        withTimeoutOrNull(timeoutMs) {
            combine(devices, state) { d, s -> d != null || s == SessionState.Closed }.first { it }
        }
        return devices.value ?: throw SessionException(
            if (state.value == SessionState.Closed) "connection closed: $closeReason" else "timed out waiting for device list",
        )
    }

    /**
     * Creates a device. Verified against a live server: the reply is `virtualDeviceOpened`, i.e.
     * the new device is opened on this connection right away (a following `openVirtualDevice`
     * is rejected with ResourceLimitReached). Returns the opened device, or null if the server
     * only answered `ok`.
     */
    suspend fun createDevice(name: String, config: KeypadConfig, skipUniquenessOfNameValidation: Boolean): VirtualDevice? {
        requireState(SessionState.Ready)
        val reply = request(RPC_TIMEOUT_MS) { ClientMessage.CreateVirtualDevice(it, name, config, skipUniquenessOfNameValidation) }
        return (reply as? ServerMessage.VirtualDeviceOpened)?.device?.also { markOpened(it) }
    }

    suspend fun openDevice(deviceID: String, config: KeypadConfig): VirtualDevice {
        requireState(SessionState.Ready, SessionState.DeviceOpen)
        val reply = request(RPC_TIMEOUT_MS) { ClientMessage.OpenVirtualDevice(it, deviceID, config) }
        val device = (reply as? ServerMessage.VirtualDeviceOpened)?.device ?: VirtualDevice(deviceID, "")
        markOpened(device)
        return device
    }

    private fun markOpened(device: VirtualDevice) {
        currentDevice = device
        _state.compareAndSet(SessionState.Ready, SessionState.DeviceOpen)
        if ((serverHello?.protocolVersion ?: 0) >= 3) {
            // This client has no paid subscription, so it never claims one.
            sendEncrypted(ClientMessage.ClientCapabilities(isUserPro = false))
        }
    }

    /** Deletes the open device; the server then closes the socket. */
    fun deleteOpenDevice(): Boolean = sendIfOpen(ClientMessage.DeleteVirtualDevice)

    /** Returns false (and sends nothing) for out-of-grid keys or when no device is open. */
    fun pressKey(row: Int, col: Int, layout: Layout, pressed: Boolean): Boolean {
        if (row !in 0 until layout.rows || col !in 0 until layout.cols) return false
        return sendIfOpen(ClientMessage.KeyPress(Keypad.index(row, col, layout.cols), pressed))
    }

    fun nextPage(): Boolean = sendIfOpen(ClientMessage.NextPage)

    fun previousPage(): Boolean = sendIfOpen(ClientMessage.PreviousPage)

    fun pageByIndex(index: Int): Boolean = sendIfOpen(ClientMessage.PageByIndex(index))

    fun updateLayout(layout: Layout, iconSize: Int): Boolean = sendIfOpen(
        ClientMessage.UpdateKeypadConfig(
            iconSize = Size(iconSize, iconSize),
            pressedIconSize = Size(iconSize, iconSize), // unset makes the server also darken pressed icons (SPEC §5.5)
            highDPI = true,
            layoutCrop = Size(layout.cols, layout.rows),
        ),
    )

    private fun sendIfOpen(msg: ClientMessage): Boolean {
        if (_state.value != SessionState.DeviceOpen) return false
        sendEncrypted(msg)
        return true
    }
}
