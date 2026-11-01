package dev.opendeck.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max

class DuotoneIconTest {
    private val blue = DuotoneIcon.Tones(0x3B4C66, 0x4C70A3, 0x67A4FF)
    private val purple = DuotoneIcon.Tones(0x4E3D66, 0x774EA3, 0xB367FF)
    private val pack = listOf(blue, purple)
    private val n = 16
    private fun at(px: IntArray, x: Int, y: Int) = px[y * n + x] and 0xFFFFFF
    private fun alpha(px: IntArray, x: Int, y: Int) = px[y * n + x] ushr 24

    /** Opaque [bg] tile with a [glyph] square in the middle; optional [inner] dot inside the glyph. */
    private fun icon(bg: Int, glyph: Int, inner: Int? = null) = IntArray(n * n) { i ->
        val x = i % n
        val y = i / n
        val c = when {
            inner != null && x in 7..8 && y in 7..8 -> inner
            x in 4..11 && y in 4..11 -> glyph
            else -> bg
        }
        if (c == 0) 0 else (0xFF000000.toInt() or c) // black arrives transparent from the decoder
    }

    @Test fun flatGlyphOnTransparentTileIsPrimary() {
        val px = icon(0, 0xFFFFFF)
        DuotoneIcon.recolor(px, n, n, blue, blue, pack)
        assertEquals(blue.pri, at(px, 5, 5))
        assertEquals(255, alpha(px, 5, 5))
        assertEquals(0, alpha(px, 0, 0))
    }

    @Test fun colouredTileBecomesTransparentAndTwoToneGlyphSplits() {
        val px = icon(0x1DB954, 0x202020, inner = 0xF0F0F0)
        DuotoneIcon.recolor(px, n, n, purple, purple, pack)
        assertEquals(0, alpha(px, 0, 0))
        assertEquals(purple.sec, at(px, 5, 5))
        assertEquals(purple.pri, at(px, 7, 7))
    }

    @Test fun gradientTileIsAllBackground() {
        val px = icon(0x0050FF, 0xFFFFFF)
        for (y in 0 until n) for (x in 0 until n) if (x !in 4..11 || y !in 4..11) px[y * n + x] = 0xFF000000.toInt() or (80 + x * 6 shl 8) or 0xFF
        DuotoneIcon.recolor(px, n, n, blue, blue, pack)
        assertEquals(0, alpha(px, 0, 0))
        assertEquals(0, alpha(px, n - 1, n - 1))
        assertEquals(blue.pri, at(px, 6, 6))
    }

    @Test fun enclosedTileColourIsAHoleButASpeckIsNot() {
        // A white ring on a pink tile with pink inside (a smiley outline): the inside is background.
        val n2 = 40
        val ring = IntArray(n2 * n2) { i ->
            val x = i % n2
            val y = i / n2
            val c = if (x in 8..31 && y in 8..31 && (x !in 12..27 || y !in 12..27)) 0xFFFFFF else 0xFF5ECF
            0xFF000000.toInt() or c
        }
        DuotoneIcon.recolor(ring, n2, n2, blue, blue, pack)
        assertEquals(0, ring[20 * n2 + 20] ushr 24)
        assertEquals(255, ring[10 * n2 + 20] ushr 24)
        // On a real 144 px icon, a 2x2 speck of tile colour inside a white square stays glyph.
        val n3 = 144
        val px = IntArray(n3 * n3) { i ->
            val x = i % n3
            val y = i / n3
            val c = when {
                x in 71..72 && y in 71..72 -> 0x2080FF
                x in 40..103 && y in 40..103 -> 0xFFFFFF
                else -> 0x2080FF
            }
            0xFF000000.toInt() or c
        }
        DuotoneIcon.recolor(px, n3, n3, blue, blue, pack)
        assertEquals(255, px[71 * n3 + 71] ushr 24)
    }

    @Test fun nearBlackOutlineOnBlackTileIsBackground() {
        val px = icon(0, 0x0A0A0A, inner = 0xFFFFFF) // white title with a black outline
        DuotoneIcon.recolor(px, n, n, blue, blue, pack)
        assertEquals(0, alpha(px, 5, 5))
        assertEquals(blue.pri, at(px, 7, 7))
        assertEquals(255, alpha(px, 7, 7))
    }

    @Test fun tilePocketCutOffAtTheBorderIsStillTile() {
        // Flat tile on the left (the border's main colour), smooth gradient on the right. A dark bar from the
        // bottom edge (like two title descenders and their outline) walls off a pocket of tile whose colour
        // is far from the main colour: without reseeding it stays as an opaque blob.
        val n2 = 32
        val px = IntArray(n2 * n2) { i ->
            val x = i % n2
            val y = i / n2
            when {
                (x == 20 || x == 26) && y >= 24 -> 0xFF253F5F.toInt()
                y == 24 && x in 20..26 -> 0xFF253F5F.toInt()
                else -> 0xFF000000.toInt() or (0x23 shl 16) or ((80 + max(0, x - 16) * 8) shl 8) or 0xFF
            }
        }
        DuotoneIcon.recolor(px, n2, n2, blue, blue, pack)
        assertEquals(0, px[28 * n2 + 23] ushr 24)
    }

    @Test fun blackTextOnWhiteTileStays() {
        val px = icon(0xFFFFFF, 0x101010)
        DuotoneIcon.recolor(px, n, n, blue, blue, pack)
        assertEquals(0, alpha(px, 0, 0)) // the white tile goes
        assertEquals(255, alpha(px, 7, 7)) // the black glyph stays
    }

    @Test fun lightAntialiasedEdgeKeepsTheStrokeTone() {
        // A red stroke on a light grey tile with a 2 px antialiased edge (lighter than the stroke) and a white
        // detail, like the "100" emoji. The edge must fade the stroke's own tone, not turn primary.
        val n2 = 40
        val grey = 0xAAAAAA
        val red = 0xDC0000
        val px = IntArray(n2 * n2) { i ->
            val x = i % n2
            val y = i / n2
            val c = when {
                x in 30..33 && y in 18..21 -> 0xFFFFFF
                x in 10..29 && y in 10..29 -> red
                x in 8..31 && y in 8..31 -> DuotoneIcon.mix(grey, red, 0.5f)
                else -> grey
            }
            0xFF000000.toInt() or c
        }
        DuotoneIcon.recolor(px, n2, n2, blue, blue, pack)
        assertEquals(blue.sec, px[20 * n2 + 20] and 0xFFFFFF) // stroke core
        assertEquals(blue.sec, px[20 * n2 + 9] and 0xFFFFFF) // its edge: same tone...
        val a = px[20 * n2 + 9] ushr 24
        assert(a in 90..170) { "edge alpha $a" } // ...half covered
    }

    @Test fun ditheredGradientAveragesToTheExactRamp() {
        // 1 level over 64 px: every 8x8 block must average to the true value, so there are no flat bands.
        val strip = Dither.horizontalGradient(64, 0x000000, 0x000001)
        for (bx in 0 until 8) {
            val avg = (0 until 64).sumOf { strip[(it / 8) * 64 + bx * 8 + it % 8] and 0xFF } / 64.0
            val expected = (0 until 8).sumOf { (bx * 8 + it) / 63.0 } / 8
            assert(kotlin.math.abs(avg - expected) < 0.07) { "block $bx: $avg vs $expected" }
        }
    }

    @Test fun packIconSwapsPaletteToneForTone() {
        val px = icon(blue.bg, blue.sec, inner = blue.pri)
        DuotoneIcon.recolor(px, n, n, purple, purple, pack)
        assertEquals(0, alpha(px, 0, 0))
        assertEquals(purple.sec, at(px, 5, 5))
        assertEquals(purple.pri, at(px, 7, 7))
    }

    @Test fun dimmedPackPartsStayDimmed() {
        // A Volume Controller NEO key: pack bg tile, a dimmed (bg/sec blend) app icon covering a fifth of it,
        // a sec fader. The icon must stay, half transparent in sec, not vanish into the tile.
        val dim = DuotoneIcon.mix(blue.bg, blue.sec, 0.45f)
        val n2 = 40
        val px = IntArray(n2 * n2) { i ->
            val x = i % n2
            val y = i / n2
            val c = when {
                x in 12..29 && y in 12..29 -> dim
                x in 2..5 -> blue.sec
                else -> blue.bg
            }
            0xFF000000.toInt() or c
        }
        DuotoneIcon.recolor(px, n2, n2, purple, purple, pack)
        val a = px[20 * n2 + 20] ushr 24
        assert(a in 95..135) { "dimmed icon alpha $a" }
        assertEquals(purple.sec, px[20 * n2 + 20] and 0xFFFFFF)
        assertEquals(0, px[20 * n2 + 35] ushr 24)
    }

    @Test fun keptPaletteKeepsItsColours() {
        // A muted (red) Volume Controller NEO key keeps red; only its tile goes.
        val red = DuotoneIcon.Tones(0x623D46, 0xA14E5D, 0xFF6783)
        val px = icon(red.bg, red.sec, inner = red.pri)
        DuotoneIcon.recolor(px, n, n, purple, purple, pack, keep = listOf(red))
        assertEquals(0, alpha(px, 0, 0))
        assertEquals(red.sec, at(px, 5, 5))
        assertEquals(red.pri, at(px, 7, 7))
    }

    @Test fun redKeyIsNotTakenForOrange() {
        // The muted fader half: mostly red bg with a dimmed red label. Orange's bg is within reach of red's,
        // so the best-fitting palette must win, not the first one checked.
        val red = DuotoneIcon.Tones(0x623D46, 0xA14E5D, 0xFF6783)
        val orange = DuotoneIcon.Tones(0x6A5542, 0xA67B50, 0xFFB367)
        val px = icon(red.bg, DuotoneIcon.mix(red.bg, red.sec, 0.4f), inner = red.sec)
        DuotoneIcon.recolor(px, n, n, purple, purple, pack, keep = listOf(orange, red))
        assertEquals(red.sec, at(px, 7, 7))
    }

    @Test fun packPixelSlightlyOffTheBackgroundStaysNearlyTransparent() {
        // A pack icon whose edge column is a few levels off its bg (a blended edge): no visible line.
        val px = icon(blue.bg, blue.sec, inner = blue.pri)
        for (y in 0 until n) px[y * n] = 0xFF000000.toInt() or 0x3A516E // (58,81,110) vs bg (59,76,102)
        DuotoneIcon.recolor(px, n, n, purple, purple, pack)
        assert(alpha(px, 0, 8) < 40) { "edge alpha ${alpha(px, 0, 8)}" }
    }

    @Test fun glyphToneRunsFromLeftToRightEdge() {
        // A white band across the full width on a transparent tile.
        val px = IntArray(n * n) { if (it / n in 4..11) 0xFFFFFFFF.toInt() else 0 }
        DuotoneIcon.recolor(px, n, n, blue, purple, pack)
        assertEquals(blue.pri, at(px, 0, 8))
        assertEquals(purple.pri, at(px, n - 1, 8))
    }

    @Test fun blendRunsBetweenPalettes() {
        assertEquals(blue, blue.blend(purple, 0f))
        assertEquals(purple, blue.blend(purple, 1f))
        assertEquals(0x45, blue.blend(purple, 0.5f).bg shr 16) // (0x3B + 0x4E) / 2, rounded
    }
}
