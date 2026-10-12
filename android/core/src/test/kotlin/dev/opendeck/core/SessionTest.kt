package dev.opendeck.core

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.SecureRandom

/** In-memory desktop stand-in that speaks the same crypto and replies synchronously. */
internal class FakeServer(
    val needsAuth: Boolean = true,
    val protocolVersion: Int = 3,
    val workstationID: String = "WS-1",
    val rejectAuth: Boolean = false,
    val holdAuth: Boolean = false,
    val firstMessage: ServerMessage? = null,
) : FrameTransport {
    val keys = CryptoBox.generateKeyPair()
    var key: ByteArray? = null
    lateinit var session: Session
    val received = mutableListOf<ClientMessage>()
    var opened = false
    val devices = mutableListOf<VirtualDevice>()
    var closedWith: Int? = null
    private val random = SecureRandom()

    // 2x1 icon, BGRA, LZ4 literal-only block
    val iconImage = Image(ImageFormat.LZ4, ImageType.TRANSPARENT, Size(2, 1), 8, byteArrayOf(0x80.toByte(), 10, 20, 30, -1, 0, 0, 0, -1))

    override fun send(frame: ByteArray) {
        val k = key
        if (k == null) {
            val hello = Proto.decodeClient(frame) as ClientMessage.Hello
            received += hello
            key = CryptoBox.beforenm(hello.publicKey, keys.secretKey)
            val reply = firstMessage ?: ServerMessage.Hello(
                workstationID, "desktop", "7.1", protocolVersion, keys.publicKey, needsAuth,
                endpoints = ServiceEndpoints(listOf("192.168.1.10"), 28198),
            )
            session.onBinaryFrame(Proto.encodeServer(reply)) // the server hello is plaintext
            return
        }
        val msg = Proto.decodeClient(CryptoBox.openFrame(frame, k))
        received += msg
        when (msg) {
            is ClientMessage.Authenticate ->
                if (holdAuth) {
                    Unit // simulate the user not answering yet
                } else if (rejectAuth) {
                    push(ServerMessage.Error(msg.requestID, ErrorCode.AUTHENTICATION_REJECTED, null))
                } else {
                    push(ServerMessage.Ok(msg.requestID))
                    push(ServerMessage.VirtualDeviceList(devices.toList()))
                }
            // Mirrors the live server: create opens the new device; one device per connection.
            is ClientMessage.CreateVirtualDevice -> {
                if (opened) {
                    push(ServerMessage.Error(msg.requestID, ErrorCode.RESOURCE_LIMIT_REACHED, "Client already requested a virtual device"))
                } else if (devices.any { it.name == msg.name } && !msg.skipUniquenessOfNameValidation) {
                    push(ServerMessage.Error(msg.requestID, ErrorCode.DEVICE_NAME_ALREADY_TAKEN, null))
                } else {
                    val dev = VirtualDevice("dev-${devices.size + 1}", msg.name)
                    devices += dev
                    opened = true
                    push(ServerMessage.VirtualDeviceOpened(msg.requestID, dev))
                    push(ServerMessage.VirtualDeviceList(devices.toList()))
                    push(ServerMessage.Context("00000000-0000-0000-0000-000000000000", -1, -1, emptyList()))
                    push(ServerMessage.Context("profile", 3, 0, listOf(Action(0, "Mute", "mute", "audio"))))
                    push(ServerMessage.Icon(0, iconImage))
                }
            }
            is ClientMessage.OpenVirtualDevice -> {
                val dev = devices.firstOrNull { it.id == msg.deviceID }
                if (opened) {
                    push(ServerMessage.Error(msg.requestID, ErrorCode.RESOURCE_LIMIT_REACHED, "Client already requested a virtual device"))
                } else if (dev == null) {
                    push(ServerMessage.Error(msg.requestID, ErrorCode.DEVICE_NOT_FOUND, "nope"))
                } else {
                    opened = true
                    push(ServerMessage.VirtualDeviceOpened(msg.requestID, dev))
                    push(ServerMessage.Context("profile", 3, 0, listOf(Action(0, "Mute", "mute", "audio"))))
                    push(ServerMessage.Icon(0, iconImage))
                }
            }
            else -> Unit
        }
    }

    fun push(m: ServerMessage) = session.onBinaryFrame(CryptoBox.sealFrame(Proto.encodeServer(m), key!!, random))

    override fun close(code: Int, reason: String) {
        closedWith = code
    }
}

internal class Recorder : SessionListener {
    val messages = mutableListOf<ServerMessage>()
    var closed: Pair<Int, String>? = null
    override fun onMessage(message: ServerMessage) { messages += message }
    override fun onClosed(code: Int, reason: String) { closed = code to reason }
}

class SessionTest {
    private val clientKeys = CryptoBox.generateKeyPair()
    private val info = ClientInformation("Test phone", "OpenDeckMobile/test", Session.PROTOCOL_VERSION, "Model", "Android", "15")

    private fun connect(server: FakeServer, expected: String? = null, rec: Recorder = Recorder()): Session {
        val s = Session(server, clientKeys, info, expected, rec)
        server.session = s
        s.start()
        return s
    }

    @Test fun fullFlow() = runBlocking {
        val server = FakeServer()
        val rec = Recorder()
        val s = connect(server, "WS-1", rec)

        val hello = server.received[0] as ClientMessage.Hello
        assertArrayEquals(clientKeys.publicKey, hello.publicKey)
        assertEquals(2, hello.clientInformation!!.protocolVersion)
        assertEquals(SessionState.Unauthenticated, s.state.value)
        assertEquals("desktop", s.serverHello!!.hostname)
        assertEquals(CryptoBox.fingerprint(clientKeys.publicKey).take(6).uppercase(), s.pairingCode)

        s.authenticate()
        assertEquals(SessionState.Ready, s.state.value)
        assertEquals(emptyList<VirtualDevice>(), s.awaitDeviceList(1000))

        val layout = Keypad.DEFAULT_LAYOUT
        // Create opens the device on this connection.
        val created = s.createDevice("OpenDeck", Keypad.keypadConfig(layout, 144), skipUniquenessOfNameValidation = true)
        assertEquals(VirtualDevice("dev-1", "OpenDeck"), created)
        assertEquals(SessionState.DeviceOpen, s.state.value)
        assertEquals(listOf(VirtualDevice("dev-1", "OpenDeck")), s.devices.value)

        try {
            s.openDevice("dev-1", Keypad.keypadConfig(layout, 144))
            fail("expected one-device-per-connection error")
        } catch (e: SessionException) {
            assertEquals(ErrorCode.RESOURCE_LIMIT_REACHED, e.errorCode)
        }

        // request IDs: authenticate 0, create 1, open 2
        assertEquals(0, (server.received[1] as ClientMessage.Authenticate).requestID)
        val create = server.received[2] as ClientMessage.CreateVirtualDevice
        assertEquals(1, create.requestID)
        assertEquals(Size(5, 3), create.keypadConfig!!.layoutCrop)
        assertEquals(ClientMessage.ClientCapabilities(false), server.received[3])
        assertEquals(2, (server.received[4] as ClientMessage.OpenVirtualDevice).requestID)

        val icon = rec.messages.filterIsInstance<ServerMessage.Icon>().single()
        assertArrayEquals(byteArrayOf(30, 20, 10, -1, 0, 0, 0, 0), IconCodec.decodeToRgba(icon.image!!))
        assertEquals(listOf(-1, 3), rec.messages.filterIsInstance<ServerMessage.Context>().map { it.totalPages })

        assertTrue(s.pressKey(1, 2, layout, true))
        assertTrue(s.pressKey(1, 2, layout, false))
        assertTrue(s.pressKey(0, 3, layout, true)) // no free-tier lock: whole grid usable
        assertFalse(s.pressKey(layout.rows, 0, layout, true)) // outside the grid
        assertTrue(s.nextPage())
        assertTrue(s.updateLayout(Layout(3, 2), 96))
        val tail = server.received.drop(5)
        assertEquals(
            listOf(
                ClientMessage.KeyPress(7, true),
                ClientMessage.KeyPress(7, false),
                ClientMessage.KeyPress(3, true),
                ClientMessage.NextPage,
                ClientMessage.UpdateKeypadConfig(Size(96, 96), Size(96, 96), true, Size(3, 2)),
            ),
            tail,
        )
    }

    @Test fun noCapabilitiesBeforeProtocol3AndNoAuthWhenTrusted() = runBlocking {
        val server = FakeServer(needsAuth = false, protocolVersion = 2)
        server.devices += VirtualDevice("d", "Mine")
        val s = connect(server)
        assertEquals(SessionState.Ready, s.state.value)
        s.openDevice("d", Keypad.keypadConfig(Layout(3, 2), 144))
        assertTrue(server.received.none { it is ClientMessage.ClientCapabilities })
    }

    @Test fun rejectedPairing() = runBlocking {
        val s = connect(FakeServer(rejectAuth = true))
        try {
            s.authenticate()
            fail("expected rejection")
        } catch (e: SessionException) {
            assertEquals(ErrorCode.AUTHENTICATION_REJECTED, e.errorCode)
        }
        assertEquals(SessionState.Unauthenticated, s.state.value)
    }

    @Test fun unexpectedWorkstationCloses() {
        val server = FakeServer()
        val s = connect(server, expected = "OTHER")
        assertEquals(SessionState.Closed, s.state.value)
        assertEquals(CloseCodes.GOING_AWAY, server.closedWith)
        assertEquals("unexpected workstation", s.closeReason)
    }

    @Test fun firstServerMessageMustBeHello() {
        val server = FakeServer(firstMessage = ServerMessage.Ok(0))
        val s = connect(server)
        assertEquals(SessionState.Closed, s.state.value)
        assertEquals(CloseCodes.POLICY_VIOLATION, server.closedWith)
    }

    @Test fun textFrameAndBadCiphertextAreProtocolErrors() {
        val a = FakeServer(needsAuth = false)
        val rec = Recorder()
        val s = connect(a, rec = rec)
        s.onTextFrame()
        assertEquals(CloseCodes.POLICY_VIOLATION, a.closedWith)
        assertNotNull(rec.closed)

        val b = FakeServer(needsAuth = false)
        val t = connect(b)
        val frame = CryptoBox.sealFrame(Proto.encodeServer(ServerMessage.Ok(1)), b.key!!, SecureRandom())
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0x40).toByte()
        t.onBinaryFrame(frame)
        assertEquals(CloseCodes.POLICY_VIOLATION, b.closedWith)

        val c = FakeServer(needsAuth = false)
        val u = connect(c)
        u.onBinaryFrame(ByteArray(39))
        assertEquals(CloseCodes.POLICY_VIOLATION, c.closedWith)
    }

    @Test fun pendingRequestFailsWhenClosed() = runBlocking {
        val server = FakeServer(needsAuth = false)
        val s = connect(server)
        s.onTransportClosed(1006, "gone")
        try {
            s.openDevice("x", Keypad.keypadConfig(Layout(3, 2), 144))
            fail("expected failure")
        } catch (e: SessionException) {
            // expected
        }
        assertFalse(s.pressKey(0, 0, Layout(3, 2), true))
    }

    @Test fun deviceNameClash() = runBlocking {
        val server = FakeServer(needsAuth = false)
        server.devices += VirtualDevice("d", "OpenDeck")
        val s = connect(server)
        try {
            s.createDevice("OpenDeck", Keypad.keypadConfig(Keypad.DEFAULT_LAYOUT, 144), skipUniquenessOfNameValidation = false)
            fail("expected name clash")
        } catch (e: SessionException) {
            assertEquals(ErrorCode.DEVICE_NAME_ALREADY_TAKEN, e.errorCode)
        }
        assertEquals(SessionState.Ready, s.state.value)
    }

    @Test fun closeWhilePairingCancelsTheRequest() = runBlocking {
        val server = FakeServer(holdAuth = true)
        val s = connect(server)
        val job = launch { runCatching { s.authenticate() } }
        while (server.received.none { it is ClientMessage.Authenticate }) yield()
        s.close()
        job.join()
        val auth = server.received.filterIsInstance<ClientMessage.Authenticate>().single()
        assertEquals(ClientMessage.CancelAuthenticate(auth.requestID), server.received.last())
        assertEquals(SessionState.Closed, s.state.value)
    }
}
