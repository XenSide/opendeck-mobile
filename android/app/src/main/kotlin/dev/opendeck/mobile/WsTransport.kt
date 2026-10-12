package dev.opendeck.mobile

import dev.opendeck.core.CloseCodes
import dev.opendeck.core.Endpoint
import dev.opendeck.core.FrameTransport
import dev.opendeck.core.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp WebSocket carrying one protocol message per binary frame (spec §2).
 * OkHttp answers server pings itself and does not surface them, so liveness is checked with
 * the client's own ping interval (see OkHttpClient in AppViewModel) instead of a receive timer.
 */
class WsTransport(private val client: OkHttpClient) : FrameTransport {
    @Volatile var session: Session? = null
    @Volatile private var ws: WebSocket? = null
    private val opened = CompletableDeferred<Boolean>()

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            session?.onBinaryFrame(bytes.toByteArray())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            session?.onTextFrame()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(CloseCodes.GOING_AWAY, null)
            session?.onTransportClosed(code, reason.ifEmpty { "closed by computer ($code)" })
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            session?.onTransportClosed(code, reason.ifEmpty { "closed ($code)" })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.complete(false)
            session?.onTransportClosed(1006, t.message ?: t.javaClass.simpleName)
        }
    }

    /** Opens the socket; false if it could not be established within [timeoutMs]. */
    suspend fun open(endpoint: Endpoint, timeoutMs: Long): Boolean {
        val request = try {
            Request.Builder().url(endpoint.wsUrl).build()
        } catch (e: IllegalArgumentException) {
            return false
        }
        ws = client.newWebSocket(request, listener)
        val ok = withTimeoutOrNull(timeoutMs) { opened.await() } ?: false
        if (!ok) cancel()
        return ok
    }

    fun cancel() {
        ws?.cancel()
    }

    override fun send(frame: ByteArray) {
        ws?.send(frame.toByteString())
    }

    override fun close(code: Int, reason: String) {
        // OkHttp limits the close reason to 123 UTF-8 bytes (30 chars is safe even at 4 bytes each).
        ws?.close(code, reason.take(30))
    }
}
