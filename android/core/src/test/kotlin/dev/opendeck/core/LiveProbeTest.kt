package dev.opendeck.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage

/**
 * Manual interop probe against a real desktop server. Skipped unless VSD2_LIVE is set, e.g.
 *   VSD2_LIVE=ws://127.0.0.1:28198 VSD2_STEP=hello ./gradlew :core:test --tests '*LiveProbe*' -i
 * VSD2_STEP: hello (default) | pair | open
 */
class LiveProbeTest {
    @Test
    fun probe() {
        val url = System.getenv("VSD2_LIVE")
        assumeTrue(url != null)
        val step = System.getenv("VSD2_STEP") ?: "hello"

        // Persist the probe's keypair so pairing survives across runs.
        val keyFile = File(System.getProperty("java.io.tmpdir"), "opendeck-probe.key")
        val keyPair = if (keyFile.exists()) {
            val sk = keyFile.readBytes(); KeyPair(CryptoBox.publicKeyOf(sk), sk)
        } else CryptoBox.generateKeyPair().also { keyFile.writeBytes(it.secretKey) }

        lateinit var ws: WebSocket
        val transport = object : FrameTransport {
            override fun send(frame: ByteArray) { ws.sendBinary(ByteBuffer.wrap(frame), true).join() }
            override fun close(code: Int, reason: String) { ws.sendClose(code, reason) }
        }
        val session = Session(
            transport, keyPair,
            ClientInformation("OpenDeck probe", "OpenDeckMobile/0.1", 2, "JVM", "Android", "15"),
            listener = object : SessionListener {
                override fun onMessage(message: ServerMessage) {
                    val s = message.toString()
                    println("<- " + if (s.length > 300) s.take(300) + "…" else s)
                    val img = (message as? ServerMessage.Icon)?.image ?: return
                    val rgba = IconCodec.decodeToRgba(img) ?: return
                    val (w, h) = img.size!!
                    val bi = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    for (i in 0 until w * h) {
                        val r = rgba[4 * i].toInt() and 255; val g = rgba[4 * i + 1].toInt() and 255
                        val b = rgba[4 * i + 2].toInt() and 255; val a = rgba[4 * i + 3].toInt() and 255
                        bi.setRGB(i % w, i / w, (a shl 24) or (r shl 16) or (g shl 8) or b)
                    }
                    val out = File(System.getenv("VSD2_ICON_DIR") ?: return, "icon_${message.index}_fmt${img.format}.png")
                    javax.imageio.ImageIO.write(bi, "png", out)
                }
                override fun onClosed(code: Int, reason: String) = println("closed $code $reason")
            },
        )
        val buf = java.io.ByteArrayOutputStream()
        ws = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI(url), object : WebSocket.Listener {
                override fun onBinary(w: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                    val b = ByteArray(data.remaining()); data.get(b); buf.write(b)
                    if (last) { val f = buf.toByteArray(); buf.reset(); session.onBinaryFrame(f) }
                    w.request(1); return null
                }
                override fun onText(w: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    session.onTextFrame(); return null
                }
                override fun onClose(w: WebSocket, code: Int, reason: String): CompletionStage<*>? {
                    session.onTransportClosed(code, reason); return null
                }
            }).join()

        println("pairing code: ${session.pairingCode}")
        session.start()
        runBlocking {
            val st = withTimeout(10_000) { session.state.first { it != SessionState.AwaitingHello } }
            println("state after hello: $st, server=${session.serverHello}")
            if (step == "hello") return@runBlocking
            if (st == SessionState.Unauthenticated) {
                println(">> Approve code ${session.pairingCode} on the desktop")
                session.authenticate()
            }
            val devices = session.awaitDeviceList()
            println("devices: $devices")
            if (step == "open") {
                val layout = Layout(3, 2)
                val dev = devices.firstOrNull { it.name == "OpenDeck probe" } ?: run {
                    session.createDevice("OpenDeck probe", Keypad.keypadConfig(layout, 144), true)
                    withTimeout(20_000) {
                        session.devices.first { l -> l?.any { it.name == "OpenDeck probe" } == true }!!
                    }.first { it.name == "OpenDeck probe" }
                }
                println("opened: " + session.openDevice(dev.id, Keypad.keypadConfig(layout, 144)))
                Thread.sleep(4000) // collect context + icons
            }
        }
        session.close()
        Thread.sleep(300)
    }
}
