package dev.opendeck.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Recolours key icons in the Duotone Essentials style (as in the Volume Controller NEO keys): the glyph
 * in two tones of one hue, the tile behind it made transparent so the keypad background shows through.
 * Pixels are ARGB ints (Bitmap.getPixels order); colours are 0xRRGGBB, any alpha byte is ignored.
 */
object DuotoneIcon {
    data class Tones(val bg: Int, val sec: Int, val pri: Int) {
        fun blend(o: Tones, t: Float) = Tones(mix(bg, o.bg, t), mix(sec, o.sec, t), mix(pri, o.pri, t))
        val list get() = listOf(bg, sec, pri)
    }

    /** Per-channel linear blend of two colours. */
    fun mix(a: Int, b: Int, t: Float): Int {
        var out = 0
        for (s in 0..16 step 8) {
            val ca = a shr s and 0xFF
            val cb = b shr s and 0xFF
            out = out or ((ca + (cb - ca) * t).roundToInt() shl s)
        }
        return out
    }

    /**
     * In place. The glyph tones run from [left] at the icon's left edge to [right] at its right edge, so
     * neighbouring keys continue one smooth gradient. Icons already drawn in one of the [remap] palettes
     * only swap palettes; icons drawn in one of the [keep] palettes keep their colours, which mean something
     * (Volume Controller NEO's red for a muted app), and only lose their tile.
     */
    fun recolor(px: IntArray, w: Int, h: Int, left: Tones, right: Tones, remap: List<Tones>, keep: List<Tones> = emptyList()) {
        val rgb = IntArray(px.size) { rgb(px[it]) }
        val glyph = GlyphTones(left, right, w)
        // The palette the icon fits best, not the first one over the bar: a red (muted) key's dark bg is also
        // within reach of the orange palette, and turned orange when orange was checked first.
        val fits = (remap.map { it to glyph } + keep.map { it to GlyphTones(it, it, w) }).map { Triple(it.first, it.second, packShare(px, rgb, it.first)) }
        val fit = fits.maxByOrNull { it.third }
        if (fit != null && fit.third >= PACK_SHARE) return swapPalette(px, rgb, w, fit.first, fit.second)
        val tile = tileMask(rgb, w, h)
        val near = IntArray(px.size)
        val depth = nearestTile(rgb, tile, w, h, near)
        // The glyph core: glyph pixels deeper than the edge band. Pixels in the band (antialiasing, soft emoji
        // edges) are a blend of their nearest tile colour and their nearest core colour: unmixing them gives
        // their coverage, and their tone comes from the core. Judged on their own colour, the light edges of a
        // red "100" on a grey tile came out primary around a secondary stroke.
        val edgePx = max(2, w / EDGE_DIVISOR)
        val core = BooleanArray(px.size) { !tile[it] && depth[it] > edgePx }
        val coreNear = if (core.any { it }) IntArray(px.size).also { nearestTile(rgb, core, w, h, it) } else rgb
        // Where two glyph parts meet the tile (an eye's white and its iris) the nearest core can be the wrong
        // owner: the soft edge of the white unmixed against the dark iris came out as a dark notch. Among the
        // core pixels within a few px, a band pixel takes the colour that best explains it as a tile blend.
        if (coreNear !== rgb) {
            val r = edgePx + 2
            for (i in px.indices) {
                if (tile[i] || core[i]) continue
                val x = i % w
                val y = i / w
                var best = blendness(rgb[i], near[i], coreNear[i])
                var owner = coreNear[i]
                for (dy in -r..r) for (dx in -r..r) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h || !core[ny * w + nx]) continue
                    val b = blendness(rgb[i], near[i], rgb[ny * w + nx])
                    if (b > best + 1e-3f) {
                        best = b
                        owner = rgb[ny * w + nx]
                    }
                }
                coreNear[i] = owner
            }
        }
        // On a black (transparent) tile, near-black is background too: title outlines and shadows, dark glyph
        // edges. On a coloured tile it stays glyph, or black text on yellow would vanish with its tile.
        val darkTile = lum(ringColor(rgb, w, h)) < DARK_TILE_LUM
        // Band pixels a little darker than their tile and close to its colour (the grey rim of the 👀 eyes) are
        // the icon's own antialiasing against the tile: background. Decided per pixel, so nothing flickers along
        // the rim. A drawn outline (a title's dark navy) or a glyph colour (the poop's brown) is far from the tile.
        val rim = BooleanArray(px.size) { !tile[it] && !core[it] && dist(rgb[it], near[it]) <= RIM_MAX && lum(rgb[it]) < lum(near[it]) }
        // How much a band pixel is a blend of tile and core (on the line between them, 1) versus a colour of
        // its own (0), like a title's dark outline, which stays glyph with its own tone. Graded, not a yes/no.
        val blend = FloatArray(px.size) { if (tile[it] || core[it] || rim[it]) 0f else blendness(rgb[it], near[it], coreNear[it]) }
        // 1 = tile background, 0 = glyph.
        val wt = FloatArray(px.size) {
            val c = rgb[it]
            val toTile = if (tile[it] || rim[it]) 1f else blend[it] * (1f - coverage(c, near[it], coreNear[it]))
            if (darkTile) max(toTile, ((BLACK_LUM - lum(c)) / BLACK_RAMP + 1f).coerceIn(0f, 1f)) else toTile
        }
        // Where each pixel's tone is read from: the core's colour for blends, its own colour otherwise.
        val source = IntArray(px.size) { if (blend[it] >= 0.5f) coreNear[it] else rgb[it] }

        // Otsu over the clearly-glyph pixels: the luminance cut that best splits them into a dark and a bright group.
        val hist = IntArray(BINS)
        var n = 0
        for (i in px.indices) if (wt[i] < 0.25f) {
            hist[(lum(source[i]) * BINS).toInt().coerceAtMost(BINS - 1)]++
            n++
        }
        val total = hist.indices.sumOf { hist[it].toDouble() * it }
        var best = 0.0
        var cut = 0.5f
        var contrast = 0.0
        var minority = 0.0
        var w0 = 0
        var s0 = 0.0
        for (k in 0 until BINS - 1) {
            w0 += hist[k]
            s0 += hist[k].toDouble() * k
            val w1 = n - w0
            if (w0 == 0 || w1 == 0) continue
            val m0 = s0 / w0
            val m1 = (total - s0) / w1
            val v = w0.toDouble() * w1 * (m1 - m0) * (m1 - m0)
            if (v > best) {
                best = v
                // Midway between the group means (bin centres), not at the split bin: it sits right on the dark group.
                cut = ((m0 + m1) / 2 + 0.5).toFloat() / BINS
                contrast = (m1 - m0) / BINS
                minority = minOf(w0, w1).toDouble() / n
            }
        }
        // One group with the contrast, or the other under MIN_GROUP_SHARE of the glyph (the grey gaps in a brush
        // stroke): a one-colour glyph, all primary like the pack's main glyphs.
        val twoTone = contrast >= MIN_CONTRAST && minority >= MIN_GROUP_SHARE
        // One linear map: the dark group's mean on sec, the bright group's mean on pri. Shading stays visible in
        // proportion to its real lightness difference (a poop's ridges, a palette's blobs), flat icons get exactly
        // sec and pri, and area-weighted means can't be hijacked by a thin outline or a brush handle the way the
        // groups' extremes were. Blends are judged by their core's lightness.
        // The groups' exact mean lightness (the Otsu histogram only places the split).
        var sum0 = 0.0
        var sum1 = 0.0
        var n0 = 0
        var n1 = 0
        for (i in px.indices) if (wt[i] < 0.25f) {
            val v = lum(source[i])
            if (v < cut) { sum0 += v; n0++ } else { sum1 += v; n1++ }
        }
        val mean0 = if (n0 > 0) (sum0 / n0).toFloat() else 0f
        val mean1 = if (n1 > 0) (sum1 / n1).toFloat() else 1f
        val t = FloatArray(px.size) {
            if (!twoTone) return@FloatArray 1f
            val own = lum(rgb[it])
            val l = if (blend[it] == 0f) own else own + (lum(coreNear[it]) - own) * blend[it]
            // Past the means the scale goes on (see GlyphTones.color): a yellow blob lighter than the palette
            // body it sits on clamped to the same pri as the body and vanished.
            val v = ((l - mean0) / (mean1 - mean0)).coerceIn(-BEYOND, 1 + BEYOND)
            // Snap float noise at the means so they land exactly on the pack's sec and pri.
            if (abs(v) < SNAP) 0f else if (abs(v - 1) < SNAP) 1f else v
        }
        for (i in px.indices) px[i] = withTile(px[i], glyph.color(i % w, i / w, t[i]), wt[i])
    }

    /** Sec/pri running from [left] to [right] across [w] columns, dithered so the gradient has no bands. */
    private class GlyphTones(left: Tones, right: Tones, private val w: Int) {
        private val bg = channels(left.bg, right.bg)
        private val sec = channels(left.sec, right.sec)
        private val pri = channels(left.pri, right.pri)

        private fun channels(a: Int, b: Int) = FloatArray(w * 3) { k ->
            val x = k / 3
            val s = 16 - 8 * (k % 3)
            val t = if (w > 1) x / (w - 1f) else 0f
            (a shr s and 0xFF) + ((b shr s and 0xFF) - (a shr s and 0xFF)) * t
        }

        /**
         * The glyph colour at ([x], [y]), [t] of the way from secondary to primary. Below 0 the secondary
         * deepens towards the tile colour, above 1 the primary lightens towards white: shading darker or lighter
         * than the group means stays visible inside the same hue.
         */
        fun color(x: Int, y: Int, t: Float): Int {
            val k = x * 3
            fun ch(c: Int) = when {
                t < 0 -> sec[k + c] + (bg[k + c] - sec[k + c]) * -t
                t > 1 -> pri[k + c] + (255 - pri[k + c]) * (t - 1)
                else -> sec[k + c] + (pri[k + c] - sec[k + c]) * t
            }
            return Dither.rgb(ch(0), ch(1), ch(2), x, y)
        }
    }

    /**
     * How much of [c] is glyph colour [g] over tile colour [t]: its position on the line from t to g, 0..1.
     * A glyph core about as close to the tile as antialiasing goes can't be unmixed: then it is all or nothing.
     */
    /**
     * 1 when [c] lies on the segment from tile colour [t] to glyph colour [g] (or past g: a darker rim of the
     * same glyph), falling to 0 as it moves off that line (UNMIX_NEAR..UNMIX_FAR) or behind t.
     */
    private fun blendness(c: Int, t: Int, g: Int): Float {
        if (dist(g, t) <= TILE_NEAR) return 1f
        var dot = 0f
        var len = 0f
        for (s in 0..16 step 8) {
            val tg = (g shr s and 0xFF) - (t shr s and 0xFF)
            dot += ((c shr s and 0xFF) - (t shr s and 0xFF)) * tg.toFloat()
            len += tg * tg.toFloat()
        }
        val u = dot / len
        val off = kotlin.math.sqrt(dist2(c, mix(t, g, u.coerceIn(0f, 1f))))
        val onLine = ((UNMIX_FAR - off) / (UNMIX_FAR - UNMIX_NEAR)).coerceIn(0f, 1f)
        return onLine * (1f + u / UNMIX_BEHIND).coerceIn(0f, 1f)
    }

    private fun coverage(c: Int, t: Int, g: Int): Float {
        if (dist(g, t) <= TILE_NEAR) return if (dist(c, t) > TILE_NEAR) 1f else 0f
        return project(c, t, g)
    }

    /** [glyph] colour, with the [tile] share of the pixel taken out of its alpha. */
    private fun withTile(p: Int, glyph: Int, tile: Float): Int =
        ((p ushr 24) * (1 - tile)).roundToInt().coerceIn(0, 255) shl 24 or glyph

    /**
     * Share of the opaque pixels on [pal]'s colours or the lines between them. The lines count too: Volume
     * Controller NEO draws dimmed parts (an inactive app's icon, the unfilled part of a fader) as bg/sec blends
     * like (66,93,130); counting only the three colours missed those icons, and the general path then lost
     * their low-contrast parts to the tile.
     */
    private fun packShare(px: IntArray, rgb: IntArray, pal: Tones): Float {
        val segments = listOf(pal.bg to pal.sec, pal.sec to pal.pri, pal.bg to pal.pri)
        var opaque = 0
        var on = 0
        for (i in px.indices) if (px[i] ushr 24 >= 128) {
            opaque++
            if (segments.any { (a, b) -> dist(rgb[i], mix(a, b, project(rgb[i], a, b))) <= TILE_SEED }) on++
        }
        return if (opaque == 0) 0f else on.toFloat() / opaque
    }

    /**
     * Re-expresses every pixel as a mix of two of [from]'s bg/sec/pri (its projection on the nearest of the
     * three segments between them) and repaints it with the same mix of the target sec/pri, the bg share
     * becoming transparency: antialiasing and low-contrast parts survive.
     */
    private fun swapPalette(px: IntArray, rgb: IntArray, w: Int, from: Tones, target: GlyphTones) {
        val (fb, fs, fp) = from.list
        // Not a 3-colour barycentric solve: the pack's three tones lie almost on one line, so that system is
        // ill-conditioned and turned a pixel a few levels off the bg into a visible secondary tone.
        val segments = listOf(Triple(fb, fs, 0 to 1), Triple(fs, fp, 1 to 2), Triple(fb, fp, 0 to 2))
        val wgt = FloatArray(3)
        for (i in px.indices) {
            var best = Float.MAX_VALUE
            var bestU = 0f
            var bestPair = 0 to 1
            for ((a, b, pair) in segments) {
                val u = project(rgb[i], a, b)
                val d = dist2(rgb[i], mix(a, b, u))
                if (d < best) {
                    best = d
                    bestU = u
                    bestPair = pair
                }
            }
            wgt.fill(0f)
            wgt[bestPair.first] = 1 - bestU
            wgt[bestPair.second] = bestU
            val glyph = wgt[1] + wgt[2]
            val t = if (glyph < 1e-6f) 0f else wgt[2] / glyph
            px[i] = withTile(px[i], target.color(i % w, i / w, t), wgt[0])
        }
    }

    /** Position (0..1) of [c]'s projection on the segment from [a] to [b]. */
    private fun project(c: Int, a: Int, b: Int): Float {
        var dot = 0f
        var len = 0f
        for (s in 0..16 step 8) {
            val ab = (b shr s and 0xFF) - (a shr s and 0xFF)
            dot += ((c shr s and 0xFF) - (a shr s and 0xFF)) * ab.toFloat()
            len += ab * ab.toFloat()
        }
        return if (len == 0f) 0f else (dot / len).coerceIn(0f, 1f)
    }

    private fun dist2(a: Int, b: Int): Float {
        var d = 0f
        for (s in 0..16 step 8) {
            val x = ((a shr s and 0xFF) - (b shr s and 0xFF)).toFloat()
            d += x * x
        }
        return d
    }

    /**
     * The tile background: a flood fill from the border pixels close to the border's main colour, spreading
     * to neighbours of nearly the same colour. It follows smooth gradient tiles and stops at glyph edges.
     */
    private fun tileMask(rgb: IntArray, w: Int, h: Int): BooleanArray {
        val main = ringColor(rgb, w, h)
        val tile = BooleanArray(rgb.size)
        val queue = IntArray(rgb.size)
        var tail = 0
        forRing(w, h) { i ->
            if (!tile[i] && dist(rgb[i], main) <= TILE_SEED) {
                tile[i] = true
                queue[tail++] = i
            }
        }
        var head = 0
        fun flood() {
            while (head < tail) {
                val i = queue[head++]
                forNeighbours(i, w, h) { j ->
                    if (!tile[j] && dist(rgb[j], rgb[i]) <= TILE_STEP) {
                        tile[j] = true
                        queue[tail++] = j
                    }
                }
            }
        }
        flood()
        // Pockets of tile cut off by glyph parts touching the border (between two title descenders) are
        // seeded again from any border pixel with a colour the tile has, even far from its main colour.
        val tileColours = HashSet<Int>()
        for (i in rgb.indices) if (tile[i]) tileColours += rgb[i] shr 4 and 0x0F0F0F
        forRing(w, h) { i ->
            if (!tile[i] && (rgb[i] shr 4 and 0x0F0F0F) in tileColours) {
                tile[i] = true
                queue[tail++] = i
            }
        }
        flood()
        // Enclosed areas of tile colour (inside a ring, letter counters, between strokes) are background too.
        // An area is a patch of continuous colour (spreading along gradients like the tile fill). It qualifies
        // only if it is mostly tile colour (not the white of an eye whose shading touches the tile grey, not a
        // display gradient only partly tile cyan, which came out striped) and solid (not a speck of brush
        // texture in a stroke, not a thin rim).
        val colours = HashSet<Int>()
        for (i in rgb.indices) if (tile[i]) colours += rgb[i] shr 3 and 0x1F1F1F
        fun tileish(i: Int) = (rgb[i] shr 3 and 0x1F1F1F) in colours
        val seen = BooleanArray(rgb.size)
        val minBlob = max(MIN_BLOB_PX, w * h / BLOB_DIVISOR)
        for (start in rgb.indices) {
            if (tile[start] || seen[start] || !tileish(start)) continue
            val from = tail
            seen[start] = true
            queue[tail++] = start
            var k = from
            var matching = 0
            var solid = 0 // tile-coloured pixels with all four neighbours tile-coloured
            while (k < tail) {
                val i = queue[k++]
                val ti = tileish(i)
                if (ti) matching++
                var around = 0
                forNeighbours(i, w, h) { j ->
                    if (ti && !tile[j] && tileish(j)) around++
                    if (!tile[j] && !seen[j] && dist(rgb[j], rgb[i]) <= TILE_STEP) {
                        seen[j] = true
                        queue[tail++] = j
                    }
                }
                if (around == 4) solid++
            }
            if (matching >= ENCLOSED_SHARE * (tail - from) && solid >= minBlob) for (q in from until tail) tile[queue[q]] = true
            tail = from
        }
        return tile
    }

    /**
     * Breadth-first from the tile: fills [near] with each pixel's nearest tile colour and returns its
     * distance in pixel steps (0 on the tile).
     */
    private fun nearestTile(rgb: IntArray, tile: BooleanArray, w: Int, h: Int, near: IntArray): IntArray {
        val depth = IntArray(rgb.size) { -1 }
        val queue = IntArray(rgb.size)
        var tail = 0
        for (i in rgb.indices) if (tile[i]) {
            near[i] = rgb[i]
            depth[i] = 0
            queue[tail++] = i
        }
        if (tail == 0) {
            near.fill(ringColor(rgb, w, h))
            return IntArray(rgb.size) { Int.MAX_VALUE }
        }
        var head = 0
        while (head < tail) {
            val i = queue[head++]
            forNeighbours(i, w, h) { j ->
                if (depth[j] < 0) {
                    depth[j] = depth[i] + 1
                    near[j] = near[i]
                    queue[tail++] = j
                }
            }
        }
        return depth
    }

    /** The most common colour on the icon's outer ring, averaged over its 16-level bucket. */
    private fun ringColor(rgb: IntArray, w: Int, h: Int): Int {
        val buckets = HashMap<Int, IntArray>() // quantised colour -> count, r, g, b sums
        forRing(w, h) { i ->
            val c = rgb[i]
            val s = buckets.getOrPut(c shr 4 and 0x0F0F0F) { IntArray(4) }
            s[0]++
            s[1] += c shr 16 and 0xFF
            s[2] += c shr 8 and 0xFF
            s[3] += c and 0xFF
        }
        val s = buckets.values.maxBy { it[0] }
        return (s[1] / s[0]) shl 16 or (s[2] / s[0] shl 8) or (s[3] / s[0])
    }

    private inline fun forRing(w: Int, h: Int, f: (Int) -> Unit) {
        for (x in 0 until w) {
            f(x)
            f((h - 1) * w + x)
        }
        for (y in 1 until h - 1) {
            f(y * w)
            f(y * w + w - 1)
        }
    }

    private inline fun forNeighbours(i: Int, w: Int, h: Int, f: (Int) -> Unit) {
        val x = i % w
        if (x > 0) f(i - 1)
        if (x < w - 1) f(i + 1)
        if (i >= w) f(i - w)
        if (i < (h - 1) * w) f(i + w)
    }

    /** Transparent counts as black: the decoder turns pure black transparent. */
    private fun rgb(p: Int) = if (p ushr 24 < 128) 0 else p and 0xFFFFFF

    private fun dist(a: Int, b: Int): Int = max(
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)),
        max(abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
    )

    private fun lum(c: Int) = (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f

    private const val BINS = 64
    /** Border pixels this close to the border's main colour start the tile fill. */
    private const val TILE_SEED = 24
    /** The fill spreads to neighbours this close: follows smooth gradients, stops at glyph edges. */
    private const val TILE_STEP = 12
    /** Blendness: RGB (Euclidean) distance from the tile-to-core line where it starts and ends falling, and how far behind the tile it reaches 0. */
    private const val UNMIX_NEAR = 24f
    private const val UNMIX_FAR = 56f
    private const val UNMIX_BEHIND = 0.15f
    /** A rim pixel is at most this far (max channel difference) from its tile colour. */
    private const val RIM_MAX = 40
    /** A glyph core within this max channel difference of its tile can't be unmixed from it. */
    private const val TILE_NEAR = 24
    /** Glyph pixels within width / this of the tile are its blended edge band (3 px on a 144 px icon). */
    private const val EDGE_DIVISOR = 48
    /** Share of an enclosed patch that must be tile colour for it to count as background. */
    private const val ENCLOSED_SHARE = 0.8f
    /** Enclosed areas smaller than width * height / BLOB_DIVISOR (13 px on a 144 px icon) are specks. */
    private const val BLOB_DIVISOR = 1600
    private const val MIN_BLOB_PX = 4
    /** A tile darker than this counts as black, see BLACK_LUM. */
    private const val DARK_TILE_LUM = 0.15f
    /** On a black tile, luminance below which a pixel is fully background, ramping to glyph over BLACK_RAMP above it. */
    private const val BLACK_LUM = 0.06f
    private const val BLACK_RAMP = 0.08f
    /** Share of opaque pixels on one pack palette that marks an icon as already Duotone. */
    private const val PACK_SHARE = 0.85f
    /** Glyph groups closer than this in mean luminance are treated as one colour. */
    private const val MIN_CONTRAST = 0.15
    /** How far past sec (towards the tile colour) and pri (towards white) shading can go. */
    private const val BEYOND = 0.5f
    /** Tone values this close to 0 or 1 are snapped to exactly sec or pri. */
    private const val SNAP = 0.002f
    /** A glyph whose smaller lightness group is under this share of it counts as one colour. */
    private const val MIN_GROUP_SHARE = 0.03
}
