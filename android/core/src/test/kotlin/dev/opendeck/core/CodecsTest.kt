package dev.opendeck.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CodecsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test fun lz4LiteralsThenMatchThenLiterals() {
        // token 0x44: 4 literals, match length 4+4; offset 4; then token 0x20: 2 literals
        val block = bytes(0x44) + ascii("abcd") + bytes(0x04, 0x00, 0x20) + ascii("xy")
        assertEquals("abcdabcdabcdxy", String(Lz4.decompress(block, 64), Charsets.US_ASCII))
    }

    @Test fun lz4OverlappingMatchWithExtendedLength() {
        // 1 literal 'z', offset 1, match length 15 + 11 + 4 = 30, then an empty final sequence
        val block = bytes(0x1F) + ascii("z") + bytes(0x01, 0x00, 0x0B, 0x00)
        assertEquals("z".repeat(31), String(Lz4.decompress(block, 31), Charsets.US_ASCII))
    }

    @Test fun lz4ExtendedLiteralLength() {
        val lit = ByteArray(20) { (it + 1).toByte() }
        val block = bytes(0xF0, 5) + lit
        assertArrayEquals(lit, Lz4.decompress(block, 20))
    }

    @Test fun lz4RejectsBadInput() {
        assertThrows(CodecException::class.java) { Lz4.decompress(bytes(0x10, 0x61, 0x00, 0x00), 16) } // offset 0
        assertThrows(CodecException::class.java) { Lz4.decompress(bytes(0x10, 0x61, 0x05, 0x00), 16) } // offset > produced
        assertThrows(CodecException::class.java) { Lz4.decompress(bytes(0x50) + ascii("abcde"), 4) } // overflow
    }

    @Test fun lzfLiteralAndBackReferences() {
        // literal run "abc"; backref len 6 offset 3; long backref len 7+11+2=20 offset 1
        val block = bytes(0x02) + ascii("abc") + bytes(0x80, 0x02, 0xE0, 11, 0x00)
        assertEquals("abcabcabc" + "c".repeat(20), String(Lzf.decompress(block, 64), Charsets.US_ASCII))
    }

    @Test fun lzfRejectsBadReference() {
        assertThrows(CodecException::class.java) { Lzf.decompress(bytes(0x00, 0x61, 0x20, 0x05), 16) }
    }

    @Test fun bgraPostProcessing() {
        val px = bytes(10, 20, 30, 255, 0, 0, 0, 255, 0, 0, 1, 128)
        IconCodec.bgraToRgba(px)
        assertArrayEquals(bytes(30, 20, 10, 255, 0, 0, 0, 0, 1, 0, 0, 128), px)
    }

    @Test fun iconDecodeLz4AndLzf() {
        val bgra = bytes(10, 20, 30, 255, 0, 0, 0, 255)
        val expected = bytes(30, 20, 10, 255, 0, 0, 0, 0)
        val lz4 = Image(ImageFormat.LZ4, ImageType.TRANSPARENT, Size(2, 1), 8, bytes(0x80) + bgra)
        assertArrayEquals(expected, IconCodec.decodeToRgba(lz4))
        val lzf = Image(ImageFormat.LZF, ImageType.TRANSPARENT, Size(2, 1), 8, bytes(0x07) + bgra)
        assertArrayEquals(expected, IconCodec.decodeToRgba(lzf))
        assertNull(IconCodec.decodeToRgba(Image(ImageFormat.PNG, 0, Size(2, 1), 8, ByteArray(0))))
        // wrong decompressed size
        val short = Image(ImageFormat.LZ4, 0, Size(4, 1), 16, bytes(0x80) + bgra)
        assertThrows(CodecException::class.java) { IconCodec.decodeToRgba(short) }
    }
}
