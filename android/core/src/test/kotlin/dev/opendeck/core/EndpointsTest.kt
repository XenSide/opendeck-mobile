package dev.opendeck.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointsTest {
    @Test fun qrWithAv2() {
        val qr = Endpoints.parseQr("https://streamdeck.elgato.com/connect?w=ABC%2D123&av2=192.168.1.5:30000&av2=10.0.0.7&a=1.2.3.4:1")!!
        assertEquals("ABC-123", qr.workstationID)
        assertEquals(listOf(Endpoint("192.168.1.5", 30000), Endpoint("10.0.0.7", 28198)), qr.endpoints)
    }

    @Test fun qrLegacyOnlyWhenAv2Absent() {
        val qr = Endpoints.parseQr("https://STREAMDECK.elgato.com/?a=1.2.3.4&a=5.6.7.8:9&w=X")!!
        assertEquals(listOf(Endpoint("1.2.3.4", 28197), Endpoint("5.6.7.8", 9)), qr.endpoints)
    }

    @Test fun qrRejections() {
        assertNull(Endpoints.parseQr("https://example.com/?w=X&av2=1.2.3.4"))
        assertNull(Endpoints.parseQr("https://streamdeck.elgato.com/?av2=1.2.3.4"))
        assertNull(Endpoints.parseQr("https://streamdeck.elgato.com/?w="))
        assertNull(Endpoints.parseQr("not a url"))
    }

    @Test fun hostPort() {
        assertEquals(Endpoint("10.0.0.1", 28198), Endpoints.parseHostPort("10.0.0.1"))
        assertEquals(Endpoint("pc.local", 1234), Endpoints.parseHostPort(" pc.local:1234 "))
        assertEquals(Endpoint("fe80::1", 28198), Endpoints.parseHostPort("fe80::1"))
        assertEquals(Endpoint("fe80::1", 99), Endpoints.parseHostPort("[fe80::1]:99"))
        assertNull(Endpoints.parseHostPort("host:0"))
        assertNull(Endpoints.parseHostPort("host:70000"))
        assertNull(Endpoints.parseHostPort("host:abc"))
        assertNull(Endpoints.parseHostPort(":80"))
        assertNull(Endpoints.parseHostPort("a b"))
        assertEquals("[fe80::1]:99", Endpoint("fe80::1", 99).toString())
        assertEquals("ws://10.0.0.1:28198/", Endpoint("10.0.0.1", 28198).wsUrl)
    }

    @Test fun hostInput() {
        assertTrue(Endpoints.parseHostInput("https://streamdeck.elgato.com/?w=X&av2=h") is Endpoints.HostInput.Qr)
        assertTrue(Endpoints.parseHostInput("192.168.0.2:28198") is Endpoints.HostInput.Manual)
        assertNull(Endpoints.parseHostInput("https://example.com/"))
    }

    @Test fun keypadIndexAndLayout() {
        assertEquals(7, Keypad.index(1, 2, 5))
        assertEquals(0, Keypad.index(0, 0, 8))
        assertTrue(Keypad.isValid(Layout(1, 8)))
        assertFalse(Keypad.isValid(Layout(9, 1)))
        assertEquals(Size(5, 3), Keypad.keypadConfig(Keypad.DEFAULT_LAYOUT, 144).layoutCrop)
    }
}
