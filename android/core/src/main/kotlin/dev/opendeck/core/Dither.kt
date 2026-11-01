package dev.opendeck.core

/**
 * 8x8 ordered (Bayer) dithering. The Duotone gradients are dark and span only ~20 levels per channel
 * across the screen, so plain rounding draws visible bands (measured on a P20: 45 flat bands up to
 * 80 px wide, nothing dithered by the platform).
 */
object Dither {
    private val BAYER = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /** Each pattern cell's rounding offset, (cell + 0.5) / 64. */
    private val OFFSET = FloatArray(64) { (BAYER[it] + 0.5f) / 64f }

    /** Rounds channel value [v] (0..255, fractional) up or down by the pattern at pixel ([x], [y]). */
    fun level(v: Float, x: Int, y: Int): Int =
        // Truncation is floor here: v is never negative. Hot path: three of these per glyph pixel.
        (v + OFFSET[(y and 7) * 8 + (x and 7)]).toInt().coerceIn(0, 255)

    /** 0xRRGGBB from fractional channels, dithered at pixel ([x], [y]). */
    fun rgb(r: Float, g: Float, b: Float, x: Int, y: Int): Int =
        level(r, x, y) shl 16 or (level(g, x, y) shl 8) or level(b, x, y)

    /**
     * A [w] x [PERIOD] strip of an opaque left-to-right gradient from [from] to [to] (0xRRGGBB), as ARGB;
     * the pattern repeats every PERIOD rows, so the strip tiles vertically.
     */
    fun horizontalGradient(w: Int, from: Int, to: Int): IntArray = IntArray(w * PERIOD) { i ->
        val x = i % w
        val t = if (w > 1) x / (w - 1f) else 0f
        fun ch(s: Int) = (from shr s and 0xFF) + ((to shr s and 0xFF) - (from shr s and 0xFF)) * t
        0xFF000000.toInt() or rgb(ch(16), ch(8), ch(0), x, i / w)
    }

    const val PERIOD = 8
}
