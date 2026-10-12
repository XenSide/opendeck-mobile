package dev.opendeck.core

import java.net.URI
import java.net.URLDecoder

data class Endpoint(val host: String, val port: Int) {
    /** host:port, with IPv6 literals bracketed. Round-trips through [Endpoints.parseHostPort]. */
    override fun toString(): String = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    val wsUrl: String get() = "ws://$this/"
}

data class QrPayload(val workstationID: String, val endpoints: List<Endpoint>)

/** Spec §7.2. */
object Endpoints {
    const val DEFAULT_PORT = 28198
    const val LEGACY_DEFAULT_PORT = 28197
    const val QR_HOST = "streamdeck.elgato.com"

    /** Parses a pairing QR URL; null if it isn't one. */
    fun parseQr(text: String): QrPayload? {
        val uri = try { URI(text.trim()) } catch (e: Exception) { return null }
        if (!QR_HOST.equals(uri.host, ignoreCase = true)) return null
        val params = mutableListOf<Pair<String, String>>()
        for (part in (uri.rawQuery ?: return null).split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val k = if (eq < 0) part else part.substring(0, eq)
            val v = if (eq < 0) "" else part.substring(eq + 1)
            try {
                // String charset overload: the Charset overload needs Android API 33.
                params += URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            } catch (e: IllegalArgumentException) {
                return null
            }
        }
        val ws = params.firstOrNull { it.first == "w" }?.second?.takeIf { it.isNotEmpty() } ?: return null
        val av2 = params.filter { it.first == "av2" }.mapNotNull { parseHostPort(it.second, DEFAULT_PORT) }
        val eps = av2.ifEmpty { params.filter { it.first == "a" }.mapNotNull { parseHostPort(it.second, LEGACY_DEFAULT_PORT) } }
        return QrPayload(ws, eps.distinct())
    }

    /** Parses host, host:port, [v6], [v6]:port or a bare IPv6 literal. */
    fun parseHostPort(text: String, defaultPort: Int = DEFAULT_PORT): Endpoint? {
        val s = text.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '@' }) return null
        val host: String
        val portText: String?
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            host = s.substring(1, close)
            val rest = s.substring(close + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else if (s.count { it == ':' } > 1) {
            host = s // bare IPv6, no port
            portText = null
        } else {
            val colon = s.indexOf(':')
            host = if (colon < 0) s else s.substring(0, colon)
            portText = if (colon < 0) null else s.substring(colon + 1)
        }
        if (host.isEmpty()) return null
        val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Endpoint(host, port)
    }

    /** Add-host input: either a QR URL or host[:port]. */
    sealed interface HostInput {
        data class Qr(val payload: QrPayload) : HostInput
        data class Manual(val endpoint: Endpoint) : HostInput
    }

    fun parseHostInput(text: String): HostInput? {
        parseQr(text)?.let { return HostInput.Qr(it) }
        if (text.contains("://")) return null
        return parseHostPort(text)?.let { HostInput.Manual(it) }
    }
}
