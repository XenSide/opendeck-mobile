package dev.opendeck.core

// Messages of package `vsd2`, spec §4. Enums are kept as Int so unknown values survive.

object ImageFormat {
    const val LZF = 0
    const val LZFSE = 1
    const val LZ4 = 2
    const val BMP = 3
    const val JPG = 4
    const val PNG = 5
    const val INVALID = 100
}

object ImageType {
    const val OPAQUE = 0
    const val TRANSPARENT = 1
}

object ErrorCode {
    const val DEVICE_NOT_FOUND = 0
    const val DEVICE_NAME_ALREADY_TAKEN = 1
    const val RESOURCE_LIMIT_REACHED = 2
    const val AUTHENTICATION_REJECTED = 3
    const val CANCELLED = 4
    const val INVALID_STATE = 5

    fun name(code: Int): String = when (code) {
        DEVICE_NOT_FOUND -> "device not found"
        DEVICE_NAME_ALREADY_TAKEN -> "device name already taken"
        RESOURCE_LIMIT_REACHED -> "resource limit reached"
        AUTHENTICATION_REJECTED -> "authentication rejected"
        CANCELLED -> "cancelled"
        INVALID_STATE -> "invalid state"
        else -> "error $code"
    }
}

data class Size(val width: Int, val height: Int)

class Image(val format: Int, val type: Int, val size: Size?, val rowBytes: Int, val image: ByteArray)

data class VirtualDevice(val id: String, val name: String)

data class ServiceEndpoints(val addresses: List<String>, val port: Int)

data class ClientInformation(
    val name: String,
    val userAgent: String,
    val protocolVersion: Int,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
)

data class KeypadConfig(
    val supportedIconImageFormats: List<Int>,
    val iconSize: Size?,
    val pressedIconSize: Size? = null,
    val highDPI: Boolean,
    val overscale: Boolean,
    val layoutCrop: Size? = null,
    val enableContextAPI: Boolean,
)

data class Action(val index: Int, val title: String, val name: String, val category: String, val shortcutID: String? = null)

class Shortcut(val id: String, val title: String, val name: String, val category: String, val icon: Image? = null)

sealed interface ClientMessage {
    class Hello(val publicKey: ByteArray, val clientInformation: ClientInformation?) : ClientMessage
    data class Authenticate(val requestID: Int) : ClientMessage
    data class CancelAuthenticate(val requestID: Int) : ClientMessage
    data class CreateVirtualDevice(val requestID: Int, val name: String, val keypadConfig: KeypadConfig?, val skipUniquenessOfNameValidation: Boolean) : ClientMessage
    data class OpenVirtualDevice(val requestID: Int, val deviceID: String, val keypadConfig: KeypadConfig?) : ClientMessage
    data object DeleteVirtualDevice : ClientMessage
    data class KeyPress(val index: Int, val pressed: Boolean) : ClientMessage
    data object NextPage : ClientMessage
    data object PreviousPage : ClientMessage
    data class PageByIndex(val index: Int) : ClientMessage
    data class UpdateKeypadConfig(val iconSize: Size?, val pressedIconSize: Size?, val highDPI: Boolean?, val layoutCrop: Size?) : ClientMessage
    data class AssignShortcut(val profileID: String, val shortcutID: String, val index: Int) : ClientMessage
    data class RemoveShortcut(val requestID: Int, val shortcutID: String) : ClientMessage
    data class TriggerShortcut(val deviceID: String, val shortcutID: String) : ClientMessage
    data class ClientCapabilities(val isUserPro: Boolean) : ClientMessage
    data class Unknown(val field: Int) : ClientMessage
}

sealed interface ServerMessage {
    data class Error(val requestID: Int, val code: Int, val reason: String?) : ServerMessage
    data class Ok(val requestID: Int) : ServerMessage
    class Hello(
        val workstationID: String,
        val hostname: String,
        val version: String,
        val protocolVersion: Int,
        val publicKey: ByteArray,
        val clientNeedsAuthentication: Boolean,
        val osName: String? = null,
        val osVersion: String? = null,
        val endpoints: ServiceEndpoints? = null,
    ) : ServerMessage
    data class VirtualDeviceList(val devices: List<VirtualDevice>) : ServerMessage
    data class VirtualDeviceOpened(val requestID: Int, val device: VirtualDevice?) : ServerMessage
    data class Context(val profileID: String, val totalPages: Int, val currentPage: Int, val actions: List<Action>) : ServerMessage
    data class ContextUpdate(val profileID: String, val replace: List<Action>, val remove: List<Int>) : ServerMessage
    data class Shortcuts(val shortcuts: List<Shortcut>) : ServerMessage
    data class ShortcutsUpdate(val replace: List<Shortcut>, val remove: List<String>) : ServerMessage
    data class Icon(val index: Int, val image: Image?) : ServerMessage
    data class Unknown(val field: Int) : ServerMessage
}

/** Hand-written protobuf codec for exactly the messages above. */
object Proto {
    // ---------- common types ----------

    private fun ProtoWriter.size(field: Int, s: Size?) {
        if (s != null) message(field) { uint32(1, s.width); uint32(2, s.height) }
    }

    private fun readSize(r: ProtoReader): Size {
        var w = 0; var h = 0
        while (r.next()) when (r.field) { 1 -> w = r.uint32(); 2 -> h = r.uint32(); else -> r.skip() }
        return Size(w, h)
    }

    private fun ProtoWriter.image(field: Int, i: Image?) {
        if (i != null) message(field) {
            uint32(1, i.format); uint32(2, i.type); size(3, i.size); uint32(4, i.rowBytes); bytes(5, i.image)
        }
    }

    private fun readImage(r: ProtoReader): Image {
        var format = 0; var type = 0; var size: Size? = null; var rowBytes = 0; var data = ByteArray(0)
        while (r.next()) when (r.field) {
            1 -> format = r.uint32()
            2 -> type = r.uint32()
            3 -> size = readSize(r.message())
            4 -> rowBytes = r.uint32()
            5 -> data = r.bytes()
            else -> r.skip()
        }
        return Image(format, type, size, rowBytes, data)
    }

    private fun ProtoWriter.device(field: Int, d: VirtualDevice?) {
        if (d != null) message(field) { string(1, d.id); string(2, d.name) }
    }

    private fun readDevice(r: ProtoReader): VirtualDevice {
        var id = ""; var name = ""
        while (r.next()) when (r.field) { 1 -> id = r.string(); 2 -> name = r.string(); else -> r.skip() }
        return VirtualDevice(id, name)
    }

    private fun ProtoWriter.clientInfo(field: Int, c: ClientInformation?) {
        if (c != null) message(field) {
            string(1, c.name); string(2, c.userAgent); uint32(3, c.protocolVersion)
            c.deviceModel?.let { presentString(4, it) }
            c.osName?.let { presentString(5, it) }
            c.osVersion?.let { presentString(6, it) }
        }
    }

    private fun readClientInfo(r: ProtoReader): ClientInformation {
        var name = ""; var ua = ""; var pv = 0; var model: String? = null; var os: String? = null; var osv: String? = null
        while (r.next()) when (r.field) {
            1 -> name = r.string()
            2 -> ua = r.string()
            3 -> pv = r.uint32()
            4 -> model = r.string()
            5 -> os = r.string()
            6 -> osv = r.string()
            else -> r.skip()
        }
        return ClientInformation(name, ua, pv, model, os, osv)
    }

    private fun ProtoWriter.keypadConfig(field: Int, k: KeypadConfig?) {
        if (k != null) message(field) {
            packedUint32(1, k.supportedIconImageFormats)
            size(2, k.iconSize)
            size(3, k.pressedIconSize)
            bool(4, k.highDPI)
            bool(5, k.overscale)
            size(6, k.layoutCrop)
            bool(7, k.enableContextAPI)
        }
    }

    private fun readKeypadConfig(r: ProtoReader): KeypadConfig {
        val formats = mutableListOf<Int>()
        var icon: Size? = null; var pressed: Size? = null; var hd = false; var over = false; var crop: Size? = null; var ctx = false
        while (r.next()) when (r.field) {
            1 -> r.repeatedUint32(formats)
            2 -> icon = readSize(r.message())
            3 -> pressed = readSize(r.message())
            4 -> hd = r.bool()
            5 -> over = r.bool()
            6 -> crop = readSize(r.message())
            7 -> ctx = r.bool()
            else -> r.skip()
        }
        return KeypadConfig(formats, icon, pressed, hd, over, crop, ctx)
    }

    private fun ProtoWriter.action(field: Int, a: Action) = message(field) {
        uint32(1, a.index); string(2, a.title); string(3, a.name); string(4, a.category)
        a.shortcutID?.let { presentString(5, it) }
    }

    private fun readAction(r: ProtoReader): Action {
        var index = 0; var title = ""; var name = ""; var cat = ""; var sid: String? = null
        while (r.next()) when (r.field) {
            1 -> index = r.uint32()
            2 -> title = r.string()
            3 -> name = r.string()
            4 -> cat = r.string()
            5 -> sid = r.string()
            else -> r.skip()
        }
        return Action(index, title, name, cat, sid)
    }

    private fun ProtoWriter.shortcut(field: Int, s: Shortcut) = message(field) {
        string(1, s.id); string(2, s.title); string(3, s.name); string(4, s.category); image(5, s.icon)
    }

    private fun readShortcut(r: ProtoReader): Shortcut {
        var id = ""; var title = ""; var name = ""; var cat = ""; var icon: Image? = null
        while (r.next()) when (r.field) {
            1 -> id = r.string()
            2 -> title = r.string()
            3 -> name = r.string()
            4 -> cat = r.string()
            5 -> icon = readImage(r.message())
            else -> r.skip()
        }
        return Shortcut(id, title, name, cat, icon)
    }

    // ---------- ClientMessage ----------

    fun encodeClient(m: ClientMessage): ByteArray = ProtoWriter().apply {
        when (m) {
            is ClientMessage.Hello -> message(1) { bytes(1, m.publicKey); clientInfo(2, m.clientInformation) }
            is ClientMessage.Authenticate -> message(2) { uint32(1, m.requestID) }
            is ClientMessage.CancelAuthenticate -> message(3) { uint32(1, m.requestID) }
            is ClientMessage.CreateVirtualDevice -> message(10) {
                uint32(1, m.requestID); string(2, m.name); keypadConfig(3, m.keypadConfig); bool(4, m.skipUniquenessOfNameValidation)
            }
            is ClientMessage.OpenVirtualDevice -> message(11) {
                uint32(1, m.requestID); string(2, m.deviceID); keypadConfig(3, m.keypadConfig)
            }
            ClientMessage.DeleteVirtualDevice -> message(12) {}
            is ClientMessage.KeyPress -> message(20) { uint32(1, m.index); bool(2, m.pressed) }
            ClientMessage.NextPage -> message(21) {}
            ClientMessage.PreviousPage -> message(22) {}
            is ClientMessage.PageByIndex -> message(23) { uint32(1, m.index) }
            is ClientMessage.UpdateKeypadConfig -> message(30) {
                size(1, m.iconSize); size(2, m.pressedIconSize); m.highDPI?.let { presentBool(3, it) }; size(4, m.layoutCrop)
            }
            is ClientMessage.AssignShortcut -> message(31) { string(1, m.profileID); string(2, m.shortcutID); uint32(3, m.index) }
            is ClientMessage.RemoveShortcut -> message(32) { uint32(1, m.requestID); string(2, m.shortcutID) }
            is ClientMessage.TriggerShortcut -> message(33) { string(1, m.deviceID); string(2, m.shortcutID) }
            is ClientMessage.ClientCapabilities -> message(100) { bool(1, m.isUserPro) }
            is ClientMessage.Unknown -> throw IllegalArgumentException("cannot encode unknown message")
        }
    }.toByteArray()

    fun decodeClient(bytes: ByteArray): ClientMessage {
        val r = ProtoReader(bytes)
        var result: ClientMessage? = null
        var unknown = 0
        while (r.next()) {
            if (r.field !in CLIENT_FIELDS) { unknown = r.field; r.skip(); continue }
            val s = r.message()
            result = when (r.field) {
                1 -> {
                    var pk = ByteArray(0); var info: ClientInformation? = null
                    while (s.next()) when (s.field) { 1 -> pk = s.bytes(); 2 -> info = readClientInfo(s.message()); else -> s.skip() }
                    ClientMessage.Hello(pk, info)
                }
                2 -> ClientMessage.Authenticate(readSingleUint(s))
                3 -> ClientMessage.CancelAuthenticate(readSingleUint(s))
                10 -> {
                    var id = 0; var name = ""; var cfg: KeypadConfig? = null; var skip = false
                    while (s.next()) when (s.field) {
                        1 -> id = s.uint32(); 2 -> name = s.string(); 3 -> cfg = readKeypadConfig(s.message()); 4 -> skip = s.bool(); else -> s.skip()
                    }
                    ClientMessage.CreateVirtualDevice(id, name, cfg, skip)
                }
                11 -> {
                    var id = 0; var dev = ""; var cfg: KeypadConfig? = null
                    while (s.next()) when (s.field) {
                        1 -> id = s.uint32(); 2 -> dev = s.string(); 3 -> cfg = readKeypadConfig(s.message()); else -> s.skip()
                    }
                    ClientMessage.OpenVirtualDevice(id, dev, cfg)
                }
                12 -> { skipAll(s); ClientMessage.DeleteVirtualDevice }
                20 -> {
                    var idx = 0; var pressed = false
                    while (s.next()) when (s.field) { 1 -> idx = s.uint32(); 2 -> pressed = s.bool(); else -> s.skip() }
                    ClientMessage.KeyPress(idx, pressed)
                }
                21 -> { skipAll(s); ClientMessage.NextPage }
                22 -> { skipAll(s); ClientMessage.PreviousPage }
                23 -> ClientMessage.PageByIndex(readSingleUint(s))
                30 -> {
                    var icon: Size? = null; var pressed: Size? = null; var hd: Boolean? = null; var crop: Size? = null
                    while (s.next()) when (s.field) {
                        1 -> icon = readSize(s.message()); 2 -> pressed = readSize(s.message()); 3 -> hd = s.bool(); 4 -> crop = readSize(s.message()); else -> s.skip()
                    }
                    ClientMessage.UpdateKeypadConfig(icon, pressed, hd, crop)
                }
                31 -> {
                    var p = ""; var sc = ""; var idx = 0
                    while (s.next()) when (s.field) { 1 -> p = s.string(); 2 -> sc = s.string(); 3 -> idx = s.uint32(); else -> s.skip() }
                    ClientMessage.AssignShortcut(p, sc, idx)
                }
                32 -> {
                    var id = 0; var sc = ""
                    while (s.next()) when (s.field) { 1 -> id = s.uint32(); 2 -> sc = s.string(); else -> s.skip() }
                    ClientMessage.RemoveShortcut(id, sc)
                }
                33 -> {
                    var d = ""; var sc = ""
                    while (s.next()) when (s.field) { 1 -> d = s.string(); 2 -> sc = s.string(); else -> s.skip() }
                    ClientMessage.TriggerShortcut(d, sc)
                }
                100 -> {
                    var pro = false
                    while (s.next()) when (s.field) { 1 -> pro = s.bool(); else -> s.skip() }
                    ClientMessage.ClientCapabilities(pro)
                }
                else -> throw IllegalStateException("unreachable")
            }
        }
        return result ?: ClientMessage.Unknown(unknown)
    }

    private val CLIENT_FIELDS = setOf(1, 2, 3, 10, 11, 12, 20, 21, 22, 23, 30, 31, 32, 33, 100)
    private val SERVER_FIELDS = setOf(1, 2, 10, 20, 21, 30, 31, 32, 33, 40)

    private fun readSingleUint(s: ProtoReader): Int {
        var v = 0
        while (s.next()) when (s.field) { 1 -> v = s.uint32(); else -> s.skip() }
        return v
    }

    private fun skipAll(s: ProtoReader) { while (s.next()) s.skip() }

    // ---------- ServerMessage ----------

    fun encodeServer(m: ServerMessage): ByteArray = ProtoWriter().apply {
        when (m) {
            is ServerMessage.Error -> message(1) { uint32(1, m.requestID); uint32(2, m.code); m.reason?.let { presentString(3, it) } }
            is ServerMessage.Ok -> message(2) { uint32(1, m.requestID) }
            is ServerMessage.Hello -> message(10) {
                string(1, m.workstationID); string(2, m.hostname); string(3, m.version); uint32(4, m.protocolVersion)
                bytes(5, m.publicKey); bool(6, m.clientNeedsAuthentication)
                m.osName?.let { presentString(7, it) }
                m.osVersion?.let { presentString(8, it) }
                m.endpoints?.let { e -> message(9) { e.addresses.forEach { presentString(1, it) }; uint32(2, e.port) } }
            }
            is ServerMessage.VirtualDeviceList -> message(20) { m.devices.forEach { device(1, it) } }
            is ServerMessage.VirtualDeviceOpened -> message(21) { uint32(1, m.requestID); device(2, m.device) }
            is ServerMessage.Context -> message(30) {
                string(1, m.profileID); uint32(2, m.totalPages); uint32(3, m.currentPage); m.actions.forEach { action(4, it) }
            }
            is ServerMessage.ContextUpdate -> message(31) {
                string(1, m.profileID); m.replace.forEach { action(2, it) }; packedUint32(3, m.remove)
            }
            is ServerMessage.Shortcuts -> message(32) { m.shortcuts.forEach { shortcut(1, it) } }
            is ServerMessage.ShortcutsUpdate -> message(33) { m.replace.forEach { shortcut(1, it) }; m.remove.forEach { presentString(2, it) } }
            is ServerMessage.Icon -> message(40) { uint32(1, m.index); image(2, m.image) }
            is ServerMessage.Unknown -> throw IllegalArgumentException("cannot encode unknown message")
        }
    }.toByteArray()

    fun decodeServer(bytes: ByteArray): ServerMessage {
        val r = ProtoReader(bytes)
        var result: ServerMessage? = null
        var unknown = 0
        while (r.next()) {
            if (r.field !in SERVER_FIELDS) { unknown = r.field; r.skip(); continue }
            val s = r.message()
            result = when (r.field) {
                1 -> {
                    var id = 0; var code = 0; var reason: String? = null
                    while (s.next()) when (s.field) { 1 -> id = s.uint32(); 2 -> code = s.uint32(); 3 -> reason = s.string(); else -> s.skip() }
                    ServerMessage.Error(id, code, reason)
                }
                2 -> ServerMessage.Ok(readSingleUint(s))
                10 -> {
                    var ws = ""; var host = ""; var ver = ""; var pv = 0; var pk = ByteArray(0); var auth = false
                    var os: String? = null; var osv: String? = null; var eps: ServiceEndpoints? = null
                    while (s.next()) when (s.field) {
                        1 -> ws = s.string()
                        2 -> host = s.string()
                        3 -> ver = s.string()
                        4 -> pv = s.uint32()
                        5 -> pk = s.bytes()
                        6 -> auth = s.bool()
                        7 -> os = s.string()
                        8 -> osv = s.string()
                        9 -> {
                            val e = s.message()
                            val addrs = mutableListOf<String>(); var port = 0
                            while (e.next()) when (e.field) { 1 -> addrs.add(e.string()); 2 -> port = e.uint32(); else -> e.skip() }
                            eps = ServiceEndpoints(addrs, port)
                        }
                        else -> s.skip()
                    }
                    ServerMessage.Hello(ws, host, ver, pv, pk, auth, os, osv, eps)
                }
                20 -> {
                    val list = mutableListOf<VirtualDevice>()
                    while (s.next()) when (s.field) { 1 -> list.add(readDevice(s.message())); else -> s.skip() }
                    ServerMessage.VirtualDeviceList(list)
                }
                21 -> {
                    var id = 0; var dev: VirtualDevice? = null
                    while (s.next()) when (s.field) { 1 -> id = s.uint32(); 2 -> dev = readDevice(s.message()); else -> s.skip() }
                    ServerMessage.VirtualDeviceOpened(id, dev)
                }
                30 -> {
                    var profile = ""; var total = 0; var cur = 0; val actions = mutableListOf<Action>()
                    while (s.next()) when (s.field) {
                        1 -> profile = s.string(); 2 -> total = s.uint32(); 3 -> cur = s.uint32(); 4 -> actions.add(readAction(s.message())); else -> s.skip()
                    }
                    ServerMessage.Context(profile, total, cur, actions)
                }
                31 -> {
                    var profile = ""; val replace = mutableListOf<Action>(); val remove = mutableListOf<Int>()
                    while (s.next()) when (s.field) {
                        1 -> profile = s.string(); 2 -> replace.add(readAction(s.message())); 3 -> s.repeatedUint32(remove); else -> s.skip()
                    }
                    ServerMessage.ContextUpdate(profile, replace, remove)
                }
                32 -> {
                    val list = mutableListOf<Shortcut>()
                    while (s.next()) when (s.field) { 1 -> list.add(readShortcut(s.message())); else -> s.skip() }
                    ServerMessage.Shortcuts(list)
                }
                33 -> {
                    val replace = mutableListOf<Shortcut>(); val remove = mutableListOf<String>()
                    while (s.next()) when (s.field) { 1 -> replace.add(readShortcut(s.message())); 2 -> remove.add(s.string()); else -> s.skip() }
                    ServerMessage.ShortcutsUpdate(replace, remove)
                }
                40 -> {
                    var idx = 0; var img: Image? = null
                    while (s.next()) when (s.field) { 1 -> idx = s.uint32(); 2 -> img = readImage(s.message()); else -> s.skip() }
                    ServerMessage.Icon(idx, img)
                }
                else -> throw IllegalStateException("unreachable")
            }
        }
        return result ?: ServerMessage.Unknown(unknown)
    }
}
