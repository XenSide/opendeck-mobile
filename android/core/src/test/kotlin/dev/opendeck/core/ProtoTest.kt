package dev.opendeck.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun clientRoundTrip(m: ClientMessage): ClientMessage {
        val enc = Proto.encodeClient(m)
        val dec = Proto.decodeClient(enc)
        assertArrayEquals(enc, Proto.encodeClient(dec))
        return dec
    }

    private fun serverRoundTrip(m: ServerMessage): ServerMessage {
        val enc = Proto.encodeServer(m)
        val dec = Proto.decodeServer(enc)
        assertArrayEquals(enc, Proto.encodeServer(dec))
        return dec
    }

    @Test fun keyPressExactBytes() {
        // field 20 (len) -> tag 0xA2 0x01, len 4, {1: 7, 2: true}
        assertArrayEquals(bytes(0xA2, 0x01, 0x04, 0x08, 0x07, 0x10, 0x01), Proto.encodeClient(ClientMessage.KeyPress(7, true)))
        // empty submessage still marks the oneof
        assertArrayEquals(bytes(0xAA, 0x01, 0x00), Proto.encodeClient(ClientMessage.NextPage))
        assertEquals(ClientMessage.NextPage, Proto.decodeClient(bytes(0xAA, 0x01, 0x00)))
    }

    @Test fun clientMessagesRoundTrip() {
        val cfg = Keypad.keypadConfig(Layout(5, 3), 144)
        val hello = clientRoundTrip(
            ClientMessage.Hello(ByteArray(32) { it.toByte() }, ClientInformation("Phone", "OpenDeckMobile/1.0", 2, "Pixel", "Android", "15")),
        ) as ClientMessage.Hello
        assertArrayEquals(ByteArray(32) { it.toByte() }, hello.publicKey)
        assertEquals(ClientInformation("Phone", "OpenDeckMobile/1.0", 2, "Pixel", "Android", "15"), hello.clientInformation)

        val msgs = listOf(
            ClientMessage.Authenticate(3),
            ClientMessage.CancelAuthenticate(3),
            ClientMessage.CreateVirtualDevice(4, "OpenDeck", cfg, true),
            ClientMessage.OpenVirtualDevice(5, "dev", cfg),
            ClientMessage.DeleteVirtualDevice,
            ClientMessage.KeyPress(0, false),
            ClientMessage.PreviousPage,
            ClientMessage.PageByIndex(2),
            ClientMessage.UpdateKeypadConfig(Size(144, 144), null, false, Size(3, 2)),
            ClientMessage.AssignShortcut("p", "s", 1),
            ClientMessage.RemoveShortcut(9, "s"),
            ClientMessage.TriggerShortcut("d", "s"),
            ClientMessage.ClientCapabilities(false),
        )
        for (m in msgs) assertEquals(m, clientRoundTrip(m))
    }

    @Test fun optionalBoolPresenceIsKept() {
        val m = Proto.decodeClient(Proto.encodeClient(ClientMessage.UpdateKeypadConfig(null, null, false, null)))
        assertEquals(false, (m as ClientMessage.UpdateKeypadConfig).highDPI)
        val n = Proto.decodeClient(Proto.encodeClient(ClientMessage.UpdateKeypadConfig(null, null, null, null)))
        assertNull((n as ClientMessage.UpdateKeypadConfig).highDPI)
    }

    @Test fun serverMessagesRoundTrip() {
        val img = Image(ImageFormat.LZ4, ImageType.OPAQUE, Size(2, 2), 8, bytes(1, 2, 3))
        val hello = serverRoundTrip(
            ServerMessage.Hello("WS", "desk", "7.0", 3, ByteArray(32) { 1 }, true, "Windows", null, ServiceEndpoints(listOf("10.0.0.2", "fe80::1"), 28198)),
        ) as ServerMessage.Hello
        assertEquals("WS", hello.workstationID)
        assertEquals(3, hello.protocolVersion)
        assertTrue(hello.clientNeedsAuthentication)
        assertNull(hello.osVersion)
        assertEquals(ServiceEndpoints(listOf("10.0.0.2", "fe80::1"), 28198), hello.endpoints)

        val msgs = listOf(
            ServerMessage.Error(1, ErrorCode.AUTHENTICATION_REJECTED, "no"),
            ServerMessage.Ok(0),
            ServerMessage.VirtualDeviceList(listOf(VirtualDevice("a", "A"), VirtualDevice("b", "B"))),
            ServerMessage.VirtualDeviceOpened(2, VirtualDevice("a", "A")),
            ServerMessage.Context("p", 3, 1, listOf(Action(0, "t", "n", "c", null), Action(4, "", "", "", "sc"))),
            ServerMessage.ContextUpdate("p", listOf(Action(1, "x", "", "", null)), listOf(0, 3, 200)),
            ServerMessage.ShortcutsUpdate(emptyList(), listOf("a", "b")),
        )
        for (m in msgs) assertEquals(m, serverRoundTrip(m))

        val icon = serverRoundTrip(ServerMessage.Icon(5, img)) as ServerMessage.Icon
        assertEquals(5, icon.index)
        assertEquals(Size(2, 2), icon.image!!.size)
        assertArrayEquals(bytes(1, 2, 3), icon.image!!.image)
        serverRoundTrip(ServerMessage.Shortcuts(listOf(Shortcut("id", "t", "n", "c", img))))
    }

    @Test fun repeatedEnumsDecodePackedAndUnpacked() {
        // KeypadConfig.supportedIconImageFormats unpacked: two separate varint fields 1 = LZ4, 1 = LZF
        val unpacked = bytes(0x08, 0x02, 0x08, 0x00)
        val packed = bytes(0x0A, 0x02, 0x02, 0x00)
        for (cfg in listOf(unpacked, packed)) {
            val open = bytes(0x5A, 2 + cfg.size, 0x1A, cfg.size) + cfg // field 11 { 3: cfg }
            val m = Proto.decodeClient(open) as ClientMessage.OpenVirtualDevice
            assertEquals(listOf(ImageFormat.LZ4, ImageFormat.LZF), m.keypadConfig!!.supportedIconImageFormats)
        }
    }

    @Test fun unknownFieldsAreSkipped() {
        // Ok{requestID=7} with unknown varint, fixed64, fixed32 and length-delimited fields inside and outside
        val inner = bytes(0x08, 0x07, 0x78, 0x01, 0x71) + ByteArray(8) + bytes(0x7D) + ByteArray(4) + bytes(0x7A, 0x01, 0x55)
        val msg = bytes(0xF8, 0x07, 0x05) + bytes(0x12, inner.size) + inner
        assertEquals(ServerMessage.Ok(7), Proto.decodeServer(msg))
        assertEquals(ServerMessage.Unknown(99), Proto.decodeServer(bytes(0x9A, 0x06, 0x00)))
    }

    @Test fun truncatedInputThrows() {
        assertThrows(ProtoException::class.java) { Proto.decodeServer(bytes(0x12, 0x05, 0x08)) }
        assertThrows(ProtoException::class.java) { Proto.decodeServer(bytes(0x12)) }
        assertThrows(ProtoException::class.java) { Proto.decodeServer(bytes(0x10, 0x01)) } // payload with wrong wire type
    }
}
