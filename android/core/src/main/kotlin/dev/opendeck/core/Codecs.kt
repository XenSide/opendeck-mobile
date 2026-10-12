package dev.opendeck.core

class CodecException(message: String) : Exception(message)

/** Raw LZ4 block format (no frame header). */
object Lz4 {
    /** Decompresses into at most [maxOut] bytes; returns exactly the bytes produced. */
    fun decompress(src: ByteArray, maxOut: Int): ByteArray {
        val dst = ByteArray(maxOut)
        var s = 0
        var d = 0
        while (s < src.size) {
            val token = src[s++].toInt() and 0xff
            var litLen = token ushr 4
            if (litLen == 15) {
                while (true) {
                    if (s >= src.size) throw CodecException("lz4: truncated literal length")
                    val b = src[s++].toInt() and 0xff
                    litLen += b
                    if (b != 255) break
                }
            }
            if (litLen > src.size - s) throw CodecException("lz4: literals past input end")
            if (litLen > maxOut - d) throw CodecException("lz4: output overflow")
            System.arraycopy(src, s, dst, d, litLen)
            s += litLen
            d += litLen
            if (s >= src.size) break // last sequence has literals only
            if (s + 2 > src.size) throw CodecException("lz4: truncated offset")
            val offset = (src[s].toInt() and 0xff) or ((src[s + 1].toInt() and 0xff) shl 8)
            s += 2
            if (offset == 0 || offset > d) throw CodecException("lz4: bad offset $offset")
            var matchLen = token and 15
            if (matchLen == 15) {
                while (true) {
                    if (s >= src.size) throw CodecException("lz4: truncated match length")
                    val b = src[s++].toInt() and 0xff
                    matchLen += b
                    if (b != 255) break
                }
            }
            matchLen += 4
            if (matchLen > maxOut - d) throw CodecException("lz4: output overflow")
            var ref = d - offset
            repeat(matchLen) { dst[d++] = dst[ref++] } // byte-wise: overlap is legal
        }
        return if (d == maxOut) dst else dst.copyOf(d)
    }
}

/** Classic liblzf block format. */
object Lzf {
    fun decompress(src: ByteArray, maxOut: Int): ByteArray {
        val dst = ByteArray(maxOut)
        var s = 0
        var d = 0
        while (s < src.size) {
            val ctrl = src[s++].toInt() and 0xff
            if (ctrl < 32) {
                val len = ctrl + 1
                if (len > src.size - s) throw CodecException("lzf: literals past input end")
                if (len > maxOut - d) throw CodecException("lzf: output overflow")
                System.arraycopy(src, s, dst, d, len)
                s += len
                d += len
            } else {
                var len = ctrl ushr 5
                var ref = d - ((ctrl and 0x1f) shl 8) - 1
                if (len == 7) {
                    if (s >= src.size) throw CodecException("lzf: truncated length")
                    len += src[s++].toInt() and 0xff
                }
                if (s >= src.size) throw CodecException("lzf: truncated offset")
                ref -= src[s++].toInt() and 0xff
                len += 2
                if (ref < 0) throw CodecException("lzf: bad back-reference")
                if (len > maxOut - d) throw CodecException("lzf: output overflow")
                repeat(len) { dst[d++] = dst[ref++] }
            }
        }
        return if (d == maxOut) dst else dst.copyOf(d)
    }
}

/** Icon decoding, spec §6.1. */
object IconCodec {
    const val MAX_DIMENSION = 1024

    /**
     * Returns tightly packed RGBA bytes of width*height*4, or null for formats we don't decode.
     * Throws [CodecException] on corrupt data.
     */
    fun decodeToRgba(image: Image): ByteArray? {
        val size = image.size ?: throw CodecException("icon without size")
        if (size.width !in 1..MAX_DIMENSION || size.height !in 1..MAX_DIMENSION) {
            throw CodecException("bad icon size ${size.width}x${size.height}")
        }
        val expected = size.width * size.height * 4
        val raw = when (image.format) {
            ImageFormat.LZ4 -> Lz4.decompress(image.image, expected)
            ImageFormat.LZF -> Lzf.decompress(image.image, expected)
            else -> return null
        }
        if (raw.size != expected) throw CodecException("icon decompressed to ${raw.size}, expected $expected")
        bgraToRgba(raw)
        return raw
    }

    /** In place: pure black (B=G=R=0) becomes transparent, otherwise swap bytes 0 and 2. */
    fun bgraToRgba(px: ByteArray) {
        var i = 0
        while (i + 3 < px.size) {
            val b = px[i]
            if (b.toInt() == 0 && px[i + 1].toInt() == 0 && px[i + 2].toInt() == 0) {
                px[i + 3] = 0
            } else {
                px[i] = px[i + 2]
                px[i + 2] = b
            }
            i += 4
        }
    }
}
