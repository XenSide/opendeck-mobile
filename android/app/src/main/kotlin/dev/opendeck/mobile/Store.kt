package dev.opendeck.mobile

import android.util.Log
import dev.opendeck.core.CryptoBox
import dev.opendeck.core.Hex
import dev.opendeck.core.KeyPair
import dev.opendeck.core.Keypad
import dev.opendeck.core.Layout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredDevice(
    val id: String,
    val name: String,
    val cols: Int = Keypad.DEFAULT_LAYOUT.cols,
    val rows: Int = Keypad.DEFAULT_LAYOUT.rows,
    val iconSize: Int = Keypad.DEFAULT_ICON_SIZE,
    /** Gap between keys and corner radius, both in % of the key cell. Client-side only. */
    val gapPercent: Int = 10,
    val cornerPercent: Int = 14,
    /** Round only the four outer corners of the whole grid. */
    val outerCornersOnly: Boolean = false,
    /** Power saving: screen at dimPercent brightness, full brightness for a few seconds after a tap. */
    val powerSave: Boolean = false,
    val dimPercent: Int = 25,
) {
    val layout: Layout get() = Layout(cols, rows)
}

@Serializable
data class StoredHost(
    val workstationID: String,
    val name: String,
    /** "host:port" strings, most recently successful first. */
    val endpoints: List<String> = emptyList(),
    val isAuthenticated: Boolean = false,
    val devices: List<StoredDevice> = emptyList(),
)

@Serializable
data class StoredState(
    val publicKey: String = "",
    val secretKey: String = "",
    val hosts: List<StoredHost> = emptyList(),
    val lastHostID: String? = null,
    val lastDeviceID: String? = null,
) {
    fun host(id: String?): StoredHost? = hosts.firstOrNull { it.workstationID == id }

    fun upsertHost(id: String, f: (StoredHost?) -> StoredHost): StoredState {
        val old = host(id)
        val new = f(old)
        return copy(hosts = if (old == null) hosts + new else hosts.map { if (it.workstationID == id) new else it })
    }

    fun withHost(id: String, f: (StoredHost) -> StoredHost): StoredState =
        copy(hosts = hosts.map { if (it.workstationID == id) f(it) else it })
}

/** JSON file in app-private storage (spec §8). Main-thread only. */
class Store(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    var state: StoredState = load()
        private set

    private fun load(): StoredState {
        if (!file.exists()) return StoredState()
        return try {
            json.decodeFromString(StoredState.serializer(), file.readText())
        } catch (e: Exception) {
            // Keep the unreadable file for inspection instead of silently overwriting it.
            Log.w(TAG, "unreadable store, starting fresh", e)
            file.renameTo(File(file.path + ".corrupt"))
            StoredState()
        }
    }

    fun update(f: (StoredState) -> StoredState): StoredState {
        val next = f(state)
        if (next == state) return state
        state = next
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(StoredState.serializer(), next))
        if (!tmp.renameTo(file)) Log.e(TAG, "could not replace ${file.path}")
        return next
    }

    /** The persistent Curve25519 identity, generated on first launch. */
    fun keyPair(): KeyPair {
        val sk = try {
            Hex.decode(state.secretKey).takeIf { it.size == 32 }
        } catch (e: Exception) {
            null
        }
        if (sk != null) return KeyPair(CryptoBox.publicKeyOf(sk), sk)
        val kp = CryptoBox.generateKeyPair()
        update { it.copy(publicKey = Hex.encode(kp.publicKey), secretKey = Hex.encode(kp.secretKey)) }
        return kp
    }

    private companion object {
        const val TAG = "OpenDeckStore"
    }
}
