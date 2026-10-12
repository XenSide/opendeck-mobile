package dev.opendeck.core

import java.io.ByteArrayOutputStream

class ProtoException(message: String) : Exception(message)

/** Minimal proto3 writer. Scalar setters skip default values; "present" variants always write. */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    /** uint32 and (non-negative) enums. */
    fun uint32(field: Int, value: Int) { if (value != 0) presentUint32(field, value) }

    fun presentUint32(field: Int, value: Int) {
        tag(field, WIRE_VARINT)
        rawVarint(value.toLong() and 0xffffffffL)
    }

    fun bool(field: Int, value: Boolean) { if (value) presentBool(field, true) }

    fun presentBool(field: Int, value: Boolean) {
        tag(field, WIRE_VARINT)
        rawVarint(if (value) 1L else 0L)
    }

    fun string(field: Int, value: String) { if (value.isNotEmpty()) presentBytes(field, value.toByteArray(Charsets.UTF_8)) }

    fun presentString(field: Int, value: String) = presentBytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray) { if (value.isNotEmpty()) presentBytes(field, value) }

    fun presentBytes(field: Int, value: ByteArray) {
        tag(field, WIRE_LEN)
        rawVarint(value.size.toLong())
        out.write(value, 0, value.size)
    }

    /** Embedded message: always written (message fields have presence). */
    fun message(field: Int, build: ProtoWriter.() -> Unit) = presentBytes(field, ProtoWriter().apply(build).toByteArray())

    /** Packed repeated uint32/enum (proto3 default). */
    fun packedUint32(field: Int, values: List<Int>) {
        if (values.isEmpty()) return
        val inner = ProtoWriter()
        for (v in values) inner.rawVarint(v.toLong() and 0xffffffffL)
        presentBytes(field, inner.toByteArray())
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_I64 = 1
        const val WIRE_LEN = 2
        const val WIRE_I32 = 5
    }
}

/** Minimal proto3 reader over buf[start, end). */
class ProtoReader(private val buf: ByteArray, start: Int = 0, private val end: Int = buf.size) {
    private var pos = start
    var field = 0
        private set
    var wireType = 0
        private set

    /** Advances to the next field; false at end of message. */
    fun next(): Boolean {
        if (pos >= end) return false
        val key = readVarint()
        field = (key ushr 3).toInt()
        wireType = (key and 7).toInt()
        if (field <= 0) throw ProtoException("invalid field number")
        return true
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= end) throw ProtoException("truncated varint")
            val b = buf[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoException("varint too long")
    }

    private fun expect(wt: Int) {
        if (wireType != wt) throw ProtoException("field $field: wire type $wireType, expected $wt")
    }

    fun uint32(): Int { expect(ProtoWriter.WIRE_VARINT); return readVarint().toInt() }

    fun bool(): Boolean { expect(ProtoWriter.WIRE_VARINT); return readVarint() != 0L }

    private fun lenRange(): IntRange {
        expect(ProtoWriter.WIRE_LEN)
        val len = readVarint()
        if (len < 0 || len > (end - pos).toLong()) throw ProtoException("length past end")
        val r = pos until pos + len.toInt()
        pos += len.toInt()
        return r
    }

    fun bytes(): ByteArray { val r = lenRange(); return buf.copyOfRange(r.first, r.last + 1) }

    fun string(): String { val r = lenRange(); return String(buf, r.first, r.last - r.first + 1, Charsets.UTF_8) }

    fun message(): ProtoReader { val r = lenRange(); return ProtoReader(buf, r.first, r.last + 1) }

    /** Repeated uint32/enum: accepts both packed and unpacked encodings. */
    fun repeatedUint32(into: MutableList<Int>) {
        if (wireType == ProtoWriter.WIRE_LEN) {
            val sub = message()
            while (sub.pos < sub.end) into.add(sub.readVarint().toInt())
        } else {
            into.add(uint32())
        }
    }

    fun skip() {
        when (wireType) {
            ProtoWriter.WIRE_VARINT -> readVarint()
            ProtoWriter.WIRE_I64 -> advance(8)
            ProtoWriter.WIRE_LEN -> lenRange()
            ProtoWriter.WIRE_I32 -> advance(4)
            else -> throw ProtoException("unsupported wire type $wireType") // groups are not used by proto3
        }
    }

    private fun advance(n: Int) {
        if (end - pos < n) throw ProtoException("truncated fixed field")
        pos += n
    }
}
