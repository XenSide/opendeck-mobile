package dev.opendeck.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.opendeck.core.ClientInformation
import dev.opendeck.core.CryptoBox
import dev.opendeck.core.Endpoint
import dev.opendeck.core.Endpoints
import dev.opendeck.core.ErrorCode
import dev.opendeck.core.IconCodec
import dev.opendeck.core.Keypad
import dev.opendeck.core.Layout
import dev.opendeck.core.ServerMessage
import dev.opendeck.core.Session
import dev.opendeck.core.SessionException
import dev.opendeck.core.SessionListener
import dev.opendeck.core.SessionState
import dev.opendeck.core.VirtualDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.sqrt

sealed interface Screen {
    data object Hosts : Screen
    data class Busy(val text: String) : Screen
    data class Pairing(val waiting: Boolean) : Screen
    data object Devices : Screen
    data object Keypad : Screen
}

data class PageInfo(val current: Int, val total: Int) {
    // Context.currentPage is 0-based (verified against a live server).
    val label: String get() = if (total <= 0) "–" else "${current + 1}/$total"
}

/** A host to connect to. workstationID is null for manual entry (learned from the hello). */
data class HostTarget(val workstationID: String?, val name: String, val endpoints: List<Endpoint>)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(File(app.filesDir, "opendeck.json"))
    private val keyPair = store.keyPair()
    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // Liveness: a missing pong fails the socket within ~2 intervals. Looser than spec §2's 10 s
        // because phone Wi-Fi power saving delays pongs; drops are auto-reconnected anyway.
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    val pairingCode: String = CryptoBox.pairingCode(keyPair.publicKey)

    var stored by mutableStateOf(store.state)
        private set
    var screen by mutableStateOf<Screen>(Screen.Hosts)
        private set
    var notice by mutableStateOf<String?>(null)
    var scanning by mutableStateOf(false)
        private set
    val discovered = mutableStateListOf<DiscoveredHost>()

    var hostID by mutableStateOf<String?>(null)
        private set
    var deviceID by mutableStateOf<String?>(null)
        private set
    val icons = mutableStateMapOf<Int, ImageBitmap>()
    val titles = mutableStateMapOf<Int, String>()
    var page by mutableStateOf(PageInfo(0, 0))
        private set

    private var session: Session? = null
    // Icons are applied in short batches: on a page reload the server first sends a provisional
    // render of every key (e.g. plugins drawing multi-key sliders as single keys), then the final one.
    private val pendingIcons = mutableMapOf<Int, ImageBitmap?>() // null = blank key
    private var iconFlush: Job? = null
    private var lastIconAt = 0L
    private var job: Job? = null
    // Cold start reopens the last used computer and device.
    private var resumeTarget: Pair<String, String>? =
        store.state.lastHostID?.let { ws -> store.state.lastDeviceID?.let { ws to it } }

    private val discovery = Discovery(
        app,
        onHosts = { discovered.clear(); discovered.addAll(it) },
        onScanning = { scanning = it },
    )

    val currentHost: StoredHost? get() = stored.host(hostID)
    val currentDevice: StoredDevice? get() = currentHost?.devices?.firstOrNull { it.id == deviceID }

    private fun save(f: (StoredState) -> StoredState) {
        stored = store.update(f)
    }

    // ---------- discovery / hosts ----------

    fun startDiscovery() = discovery.start()

    fun stopDiscovery() = discovery.stop()

    fun connectSaved(host: StoredHost) = connect(targetFor(host))

    fun connectDiscovered(host: DiscoveredHost) = connect(HostTarget(host.workstationID, host.name, host.endpoints))

    /** Returns an error message, or null if a connection attempt started. */
    fun addHost(text: String): String? {
        when (val input = Endpoints.parseHostInput(text)) {
            null -> return "Enter host[:port] or paste the pairing link from the QR code."
            is Endpoints.HostInput.Qr -> {
                val p = input.payload
                val known = stored.host(p.workstationID)
                val target = known?.let { targetFor(it) }
                connect(HostTarget(p.workstationID, known?.name ?: "", p.endpoints + (target?.endpoints ?: emptyList())))
            }
            is Endpoints.HostInput.Manual -> connect(HostTarget(null, "", listOf(input.endpoint)))
        }
        return null
    }

    private fun targetFor(host: StoredHost): HostTarget {
        val fresh = discovered.firstOrNull { it.workstationID == host.workstationID }?.endpoints ?: emptyList()
        val saved = host.endpoints.mapNotNull { Endpoints.parseHostPort(it) }
        return HostTarget(host.workstationID, host.name, (fresh + saved).distinct())
    }

    // ---------- connection ----------

    /** An endless reconnect after an unexpected drop; only the Busy screen's Cancel stops it. */
    private data class Retry(val target: HostTarget, val deviceID: String)
    private var retry: Retry? = null

    private fun connect(target: HostTarget, openDeviceID: String? = null, retry: Retry? = null) {
        disconnect()
        discovery.stop()
        this.retry = retry
        val label = target.name.ifBlank { target.endpoints.firstOrNull()?.toString() ?: "computer" }
        screen = Screen.Busy(
            if (retry == null) "Connecting to $label…" else "Connection lost. Reconnecting to $label…",
        )
        job = viewModelScope.launch {
            try {
                val (s, used) = openSession(target)
                session = s
                val hello = s.serverHello ?: throw SessionException("no hello")
                hostID = hello.workstationID
                rememberHost(target, hello, used)
                if (s.state.value == SessionState.Unauthenticated) {
                    this@AppViewModel.retry = null // pairing needs the user, stop retrying
                    save { st -> st.withHost(hello.workstationID) { it.copy(isAuthenticated = false) } }
                    screen = Screen.Pairing(waiting = false)
                } else {
                    // A retry ends only once the device is open again (openDevice/enterKeypad clear it).
                    afterAuthenticated(s, openDeviceID)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retry != null) scheduleRetry(retry, e) else fail(e)
            }
        }
    }

    private fun scheduleRetry(last: Retry, e: Exception) {
        Log.i(TAG, "reconnect failed: ${e.message}")
        session?.close()
        session = null
        job = viewModelScope.launch {
            delay(RETRY_DELAY_MS)
            connect(last.target, last.deviceID, last)
        }
    }

    private suspend fun openSession(target: HostTarget): Pair<Session, Endpoint> {
        val endpoints = target.endpoints.distinct().take(MAX_CONNECT_ATTEMPTS)
        if (endpoints.isEmpty()) throw SessionException("No known address for this computer.")
        var lastError = "Could not reach the computer."
        for (ep in endpoints) {
            val transport = WsTransport(http)
            val events = Events()
            val s = Session(transport, keyPair, clientInfo(), target.workstationID, events)
            events.owner = s
            transport.session = s
            try {
                if (!transport.open(ep, CONNECT_TIMEOUT_MS)) {
                    lastError = "Could not reach $ep."
                    continue
                }
                s.start()
                val st = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { s.state.first { it != SessionState.AwaitingHello } }
                when (st) {
                    null -> { s.close("hello timeout"); lastError = "No answer from $ep." }
                    SessionState.Closed -> {
                        lastError = s.closeReason ?: "Connection closed."
                        if (lastError == "unexpected workstation") throw SessionException("$ep belongs to a different computer.")
                    }
                    else -> return s to ep
                }
            } catch (e: CancellationException) {
                transport.cancel()
                throw e
            }
        }
        throw SessionException(lastError)
    }

    private fun rememberHost(target: HostTarget, hello: ServerMessage.Hello, used: Endpoint) {
        val advertised = hello.endpoints?.let { e ->
            val port = if (e.port in 1..65535) e.port else Endpoints.DEFAULT_PORT
            e.addresses.filter { it.isNotBlank() }.map { Endpoint(it.trim(), port) }
        } ?: emptyList()
        val ws = hello.workstationID
        save { st ->
            st.upsertHost(ws) { old ->
                val previous = old?.endpoints?.mapNotNull { Endpoints.parseHostPort(it) } ?: emptyList()
                val all = (listOf(used) + target.endpoints + advertised + previous).distinct().take(MAX_STORED_ENDPOINTS)
                val name = target.name.ifBlank { old?.name ?: "" }.ifBlank { hello.hostname.ifBlank { ws } }
                (old ?: StoredHost(ws, name)).copy(name = name, endpoints = all.map { it.toString() })
            }
        }
    }

    private suspend fun afterAuthenticated(s: Session, openDeviceID: String?) {
        val ws = s.serverHello!!.workstationID
        screen = Screen.Busy("Loading devices…")
        var list = s.awaitDeviceList()
        save { st -> st.withHost(ws) { it.copy(isAuthenticated = true) } }
        mergeDevices(ws, list)
        // While the computer is shutting down or booting, the server can answer before the device exists:
        // keep retrying instead of creating a new device or dropping to the device list.
        if (retry != null && list.none { it.id == openDeviceID }) throw SessionException("device not available yet")
        if (list.isEmpty()) {
            val config = Keypad.keypadConfig(Keypad.DEFAULT_LAYOUT, Keypad.DEFAULT_ICON_SIZE)
            // Creating also opens the device on this connection (verified live).
            val created = s.createDevice(DEFAULT_DEVICE_NAME, config, skipUniquenessOfNameValidation = true)
            if (created != null) {
                enterKeypad(ws, created)
                return
            }
            list = withTimeoutOrNull(Session.DEVICE_LIST_TIMEOUT_MS) { s.devices.first { !it.isNullOrEmpty() } } ?: emptyList()
            mergeDevices(ws, list)
        }
        val reopen = openDeviceID?.takeIf { id -> list.any { it.id == id } }
        if (reopen != null) openDevice(reopen) else screen = Screen.Devices
    }

    private fun mergeDevices(ws: String, list: List<VirtualDevice>) {
        save { st ->
            st.withHost(ws) { h ->
                val old = h.devices.associateBy { it.id }
                h.copy(devices = list.map { d -> old[d.id]?.copy(name = d.name) ?: StoredDevice(d.id, d.name) })
            }
        }
    }

    fun pair() {
        val s = session ?: return
        screen = Screen.Pairing(waiting = true)
        job = viewModelScope.launch {
            try {
                s.authenticate()
                afterAuthenticated(s, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (s.state.value == SessionState.Unauthenticated) {
                    notice = describe(e, pairing = true)
                    screen = Screen.Pairing(waiting = false)
                } else {
                    fail(e)
                }
            }
        }
    }

    fun cancelPairing() {
        session?.cancelAuthentication()
    }

    fun createDevice(name: String) {
        val s = session ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            try {
                val created = s.createDevice(trimmed, Keypad.keypadConfig(Keypad.DEFAULT_LAYOUT, Keypad.DEFAULT_ICON_SIZE), skipUniquenessOfNameValidation = false)
                if (created != null) enterKeypad(hostID ?: return@launch, created)
                else notice = "Created “$trimmed”."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = describe(e)
            }
        }
    }

    /** Bookkeeping for a device the server already opened on this connection (after create). */
    private fun enterKeypad(ws: String, device: VirtualDevice) {
        save { st ->
            st.withHost(ws) { h ->
                if (h.devices.any { it.id == device.id }) h
                else h.copy(devices = h.devices + StoredDevice(device.id, device.name))
            }
        }
        clearIcons()
        titles.clear()
        page = PageInfo(0, 0)
        deviceID = device.id
        save { it.copy(lastHostID = ws, lastDeviceID = device.id) }
        retry = null
        screen = Screen.Keypad
    }

    fun openDevice(id: String) {
        val s = session ?: return
        val ws = hostID ?: return
        val dev = stored.host(ws)?.devices?.firstOrNull { it.id == id } ?: return
        // The server allows one device per connection (a second open gets ResourceLimitReached).
        val open = s.currentDevice
        if (open != null && s.state.value == SessionState.DeviceOpen) {
            if (open.id == id) {
                deviceID = id
                screen = Screen.Keypad
            } else {
                val host = stored.host(ws) ?: return
                connect(targetFor(host), openDeviceID = id)
            }
            return
        }
        screen = Screen.Busy("Opening ${dev.name}…")
        clearIcons()
        titles.clear()
        page = PageInfo(0, 0)
        job = viewModelScope.launch {
            try {
                s.openDevice(id, Keypad.keypadConfig(dev.layout, dev.iconSize))
                deviceID = id
                save { it.copy(lastHostID = ws, lastDeviceID = id) }
                retry = null
                screen = Screen.Keypad
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val ongoing = retry
                if (ongoing != null) {
                    scheduleRetry(ongoing, e)
                } else if (s.state.value == SessionState.Closed) {
                    fail(e)
                } else {
                    notice = describe(e)
                    screen = Screen.Devices
                }
            }
        }
    }

    // ---------- keypad ----------

    private fun queueIcon(index: Int, image: ImageBitmap?) {
        pendingIcons[index] = image
        lastIconAt = SystemClock.uptimeMillis()
        if (iconFlush != null) return
        iconFlush = viewModelScope.launch {
            val start = SystemClock.uptimeMillis()
            delay(ICON_BATCH_MS)
            // Many keys at once = a page redraw: wait for it to settle and show only the final state.
            while (pendingIcons.size >= PAGE_REDRAW_KEYS &&
                SystemClock.uptimeMillis() - lastIconAt < ICON_QUIET_MS &&
                SystemClock.uptimeMillis() - start < ICON_MAX_WAIT_MS
            ) delay(ICON_POLL_MS)
            pendingIcons.forEach { (i, img) -> if (img == null) icons.remove(i) else icons[i] = img }
            pendingIcons.clear()
            iconFlush = null
        }
    }

    private fun clearIcons() {
        iconFlush?.cancel()
        iconFlush = null
        pendingIcons.clear()
        icons.clear()
    }

    fun press(row: Int, col: Int, pressed: Boolean) {
        val dev = currentDevice ?: return
        session?.pressKey(row, col, dev.layout, pressed)
    }

    fun nextPage() {
        session?.nextPage()
    }

    fun previousPage() {
        session?.previousPage()
    }

    fun setLayout(layout: Layout, iconSize: Int) {
        val ws = hostID ?: return
        val id = deviceID ?: return
        if (!Keypad.isValid(layout)) return
        save { st ->
            st.withHost(ws) { h ->
                h.copy(devices = h.devices.map { if (it.id == id) it.copy(cols = layout.cols, rows = layout.rows, iconSize = iconSize) else it })
            }
        }
        clearIcons()
        titles.clear()
        session?.updateLayout(layout, iconSize)
    }

    /** Saves the client-only display settings of [new]; the server never sees these. */
    fun setStyle(new: StoredDevice) {
        val ws = hostID ?: return
        val id = deviceID ?: return
        save { st ->
            st.withHost(ws) { h ->
                h.copy(devices = h.devices.map {
                    if (it.id != id) it
                    else it.copy(
                        gapPercent = new.gapPercent,
                        cornerPercent = new.cornerPercent,
                        outerCornersOnly = new.outerCornersOnly,
                        powerSave = new.powerSave,
                        dimPercent = new.dimPercent,
                        backgroundColor = new.backgroundColor,
                    )
                })
            }
        }
    }

    fun back() {
        when (screen) {
            Screen.Keypad -> {
                deviceID = null
                screen = Screen.Devices
            }
            Screen.Hosts -> Unit
            else -> disconnect()
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        retry = null
        val s = session
        session = null // set first so the close callback is ignored
        s?.close()
        deviceID = null
        hostID = null
        clearIcons()
        titles.clear()
        screen = Screen.Hosts
    }

    // ---------- lifecycle ----------

    fun onAppStart() {
        val (ws, dev) = resumeTarget ?: return
        resumeTarget = null
        val host = stored.host(ws) ?: return
        // Resume as a retry: the computer may still be off (or booting) when the app comes back.
        connect(targetFor(host), dev, Retry(targetFor(host), dev))
    }

    fun onAppStop() {
        // Don't keep retrying in the background: resume the reconnect when the app comes back.
        val r = retry
        val ws = if (r != null) r.target.workstationID else hostID
        val dev = r?.deviceID ?: deviceID
        if ((screen == Screen.Keypad || r != null) && ws != null && dev != null) {
            resumeTarget = ws to dev
            disconnect()
        }
    }

    override fun onCleared() {
        disconnect()
        discovery.stop()
    }

    // ---------- session events ----------

    private inner class Events : SessionListener {
        @Volatile var owner: Session? = null

        // Called on the OkHttp reader thread.
        override fun onMessage(message: ServerMessage) {
            val s = owner ?: return
            when (message) {
                is ServerMessage.Icon -> {
                    val rgba = decodeIcon(message) ?: return
                    // Keys with no action arrive as an all-black image: show nothing so the background shows through.
                    if (IconCodec.isBlank(rgba)) return onMain(s) { queueIcon(message.index, null) }
                    val size = message.image!!.size!!
                    val bmp = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
                    // Pressed icons are recognised by their pixels: arrival order vs. release is unreliable.
                    if (hasPressMask(bmp)) fillPressMask(bmp) // reader thread, before the bitmap is shared
                    val image = bmp.asImageBitmap()
                    onMain(s) { queueIcon(message.index, image) }
                }
                is ServerMessage.Context -> onMain(s) {
                    // The server first sends a placeholder context with page fields = 0xFFFFFFFF.
                    page = if (message.totalPages > 0) PageInfo(message.currentPage, message.totalPages) else PageInfo(0, 0)
                    titles.clear()
                    message.actions.forEach { titles[it.index] = it.title }
                }
                is ServerMessage.ContextUpdate -> onMain(s) {
                    message.remove.forEach { titles.remove(it) }
                    message.replace.forEach { titles[it.index] = it.title }
                }
                is ServerMessage.VirtualDeviceList -> onMain(s) {
                    hostID?.let { mergeDevices(it, message.devices) }
                }
                is ServerMessage.Unknown -> Log.i(TAG, "unhandled server message, field ${message.field}")
                else -> Unit
            }
        }

        override fun onClosed(code: Int, reason: String) {
            val s = owner ?: return
            onMain(s) {
                session = null
                job?.cancel()
                val host = stored.host(hostID)
                val dev = deviceID
                val ongoing = retry
                if (ongoing != null) {
                    // Dropped again mid-reconnect: keep going.
                    scheduleRetry(ongoing, SessionException("$code $reason"))
                    return@onMain
                }
                Log.i(TAG, "closed: $code $reason")
                // Another phone took the device ("Connection replaced"): reconnecting would steal it back.
                // Any other close, including the desktop app quitting ("kicked from client"), is retried.
                val replaced = reason.contains("replaced", ignoreCase = true)
                if (!replaced && screen == Screen.Keypad && host != null && dev != null) {
                    Log.i(TAG, "connection lost ($code $reason), reconnecting")
                    connect(targetFor(host), dev, Retry(targetFor(host), dev))
                    return@onMain
                }
                notice = "Disconnected: $reason"
                deviceID = null
                screen = Screen.Hosts
            }
        }
    }

    private fun onMain(s: Session, block: () -> Unit) {
        viewModelScope.launch { if (session === s) block() }
    }

    /**
     * Pressed icons (sent while a key is held, or just after a quick tap) have the four corners masked
     * transparent while the edges stay opaque. Normal icons never match: a coloured background has
     * opaque corners, a black one (decoded as transparent) has transparent edges too.
     */
    private fun hasPressMask(bmp: Bitmap): Boolean {
        val w = bmp.width - 1
        val h = bmp.height - 1
        val corners = listOf(0 to 0, w to 0, 0 to h, w to h)
        val edges = listOf(w / 2 to 0, w / 2 to h, 0 to h / 2, w to h / 2)
        return corners.all { (x, y) -> Color.alpha(bmp.getPixel(x, y)) == 0 } &&
            edges.all { (x, y) -> Color.alpha(bmp.getPixel(x, y)) == 255 }
    }

    /**
     * Repaints the rounded corner mask of a held key's icon from the icon itself: each masked pixel
     * takes the colour just inside the curve along the ray from the corner's arc centre.
     */
    private fun fillPressMask(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        // Measured on a live server: the mask radius is 22 px on a 144 px icon, plus a ~2 px dark rim.
        val r = minOf(w, h) * PRESS_MASK_FRACTION
        val inside = r - PRESS_MASK_RIM * minOf(w, h) / 144f
        val n = ceil(r).toInt()
        val px = IntArray(n * n)
        // Only the four n×n corner squares are touched; every sample point lies inside its own square.
        for ((left, top) in listOf(true to true, false to true, true to false, false to false)) {
            val x0 = if (left) 0 else w - n
            val y0 = if (top) 0 else h - n
            // Arc centre in square-local coordinates.
            val cx = if (left) r else n - r
            val cy = if (top) r else n - r
            bmp.getPixels(px, 0, n, x0, y0, n, n)
            val src = px.copyOf()
            for (y in 0 until n) for (x in 0 until n) {
                val dx = x + 0.5f - cx
                val dy = y + 0.5f - cy
                val d = sqrt(dx * dx + dy * dy)
                // Only the corner side of the arc centre, outside the clean disc.
                if (d <= inside || (dx > 0) == left || (dy > 0) == top) continue
                val sx = (cx + dx / d * inside).toInt().coerceIn(0, n - 1)
                val sy = (cy + dy / d * inside).toInt().coerceIn(0, n - 1)
                px[y * n + x] = src[sy * n + sx]
            }
            bmp.setPixels(px, 0, n, x0, y0, n, n)
        }
    }

    private fun decodeIcon(m: ServerMessage.Icon): ByteArray? {
        val image = m.image ?: return null
        return try {
            IconCodec.decodeToRgba(image) ?: run {
                Log.d(TAG, "ignoring icon format ${image.format}")
                null
            }
        } catch (e: Exception) { // untrusted input: never let a bad icon kill the reader thread
            Log.w(TAG, "bad icon ${m.index}: ${e.message}")
            null
        }
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "connection failed", e)
        notice = describe(e)
        val s = session
        session = null
        s?.close()
        deviceID = null
        screen = Screen.Hosts
    }

    private fun describe(e: Exception, pairing: Boolean = false): String = when ((e as? SessionException)?.errorCode) {
        ErrorCode.AUTHENTICATION_REJECTED -> "Pairing was rejected on the computer."
        ErrorCode.CANCELLED -> "Pairing cancelled."
        ErrorCode.RESOURCE_LIMIT_REACHED ->
            if (pairing) "Another pairing is already in progress on the computer."
            else "This connection already has a device open."
        ErrorCode.DEVICE_NAME_ALREADY_TAKEN -> "That name is already taken. Choose another."
        ErrorCode.DEVICE_NOT_FOUND -> "That device no longer exists on the computer."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun clientInfo(): ClientInformation {
        val ctx = getApplication<Application>()
        val name = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return ClientInformation(
            name = name,
            userAgent = "OpenDeckMobile/${BuildConfig.VERSION_NAME}",
            protocolVersion = Session.PROTOCOL_VERSION,
            deviceModel = Build.MODEL,
            osName = "Android",
            osVersion = Build.VERSION.RELEASE,
        )
    }

    companion object {
        const val DEFAULT_DEVICE_NAME = "OpenDeck"
        const val CONNECT_TIMEOUT_MS = 6_000L
        private const val RETRY_DELAY_MS = 3_000L
        const val MAX_CONNECT_ATTEMPTS = 5
        const val MAX_STORED_ENDPOINTS = 8
        private const val ICON_BATCH_MS = 40L
        private const val PAGE_REDRAW_KEYS = 3
        private const val ICON_QUIET_MS = 120L
        private const val ICON_MAX_WAIT_MS = 500L
        private const val ICON_POLL_MS = 20L
        private const val PRESS_MASK_FRACTION = 22f / 144f
        private const val PRESS_MASK_RIM = 3f
        val ICON_SIZES = listOf(72, 96, 144, 192)
        private const val TAG = "OpenDeck"
    }
}
