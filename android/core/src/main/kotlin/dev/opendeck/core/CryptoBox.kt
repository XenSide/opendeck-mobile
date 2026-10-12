package dev.opendeck.core

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.engines.XSalsa20Engine
import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom

class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray) {
    init {
        require(publicKey.size == 32 && secretKey.size == 32) { "keys must be 32 bytes" }
    }
}

class CryptoException(message: String) : Exception(message)

/** NaCl crypto_box (Curve25519 + XSalsa20-Poly1305), spec §3. */
object CryptoBox {
    const val NONCE_BYTES = 24
    const val MAC_BYTES = 16
    const val FRAME_OVERHEAD = NONCE_BYTES + MAC_BYTES

    fun generateKeyPair(random: SecureRandom = SecureRandom()): KeyPair {
        val sk = ByteArray(32)
        random.nextBytes(sk)
        return KeyPair(publicKeyOf(sk), sk)
    }

    fun publicKeyOf(secretKey: ByteArray): ByteArray {
        val pk = ByteArray(32)
        X25519.scalarMultBase(secretKey, 0, pk, 0)
        return pk
    }

    /** crypto_box_beforenm: HSalsa20(X25519(sk, pk), zero 16-byte input). */
    fun beforenm(theirPublicKey: ByteArray, mySecretKey: ByteArray): ByteArray {
        require(theirPublicKey.size == 32 && mySecretKey.size == 32) { "keys must be 32 bytes" }
        val shared = ByteArray(32)
        X25519.scalarMult(mySecretKey, 0, theirPublicKey, 0, shared, 0)
        if (shared.all { it == 0.toByte() }) throw CryptoException("degenerate public key")
        return hsalsa20(shared, ByteArray(16))
    }

    /** crypto_box_easy_afternm: returns MAC(16) || ciphertext. */
    fun easyAfternm(plaintext: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES && key.size == 32)
        val engine = XSalsa20Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        val polyKey = ByteArray(32)
        engine.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val out = ByteArray(MAC_BYTES + plaintext.size)
        engine.processBytes(plaintext, 0, plaintext.size, out, MAC_BYTES)
        val mac = poly1305(polyKey, out, MAC_BYTES, plaintext.size)
        System.arraycopy(mac, 0, out, 0, MAC_BYTES)
        return out
    }

    /** crypto_box_open_easy_afternm; throws CryptoException on authentication failure. */
    fun openEasyAfternm(boxed: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == NONCE_BYTES && key.size == 32)
        if (boxed.size < MAC_BYTES) throw CryptoException("ciphertext too short")
        val engine = XSalsa20Engine()
        engine.init(false, ParametersWithIV(KeyParameter(key), nonce))
        val polyKey = ByteArray(32)
        engine.processBytes(ByteArray(32), 0, 32, polyKey, 0)
        val ctLen = boxed.size - MAC_BYTES
        val expected = poly1305(polyKey, boxed, MAC_BYTES, ctLen)
        if (!MessageDigest.isEqual(expected, boxed.copyOfRange(0, MAC_BYTES))) {
            throw CryptoException("authentication failed")
        }
        val out = ByteArray(ctLen)
        engine.processBytes(boxed, MAC_BYTES, ctLen, out, 0)
        return out
    }

    /** Wire frame: nonce(24) || box. */
    fun sealFrame(plaintext: ByteArray, key: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)
        return nonce + easyAfternm(plaintext, nonce, key)
    }

    fun openFrame(frame: ByteArray, key: ByteArray): ByteArray {
        if (frame.size < FRAME_OVERHEAD) throw CryptoException("frame shorter than 40 bytes")
        return openEasyAfternm(frame.copyOfRange(NONCE_BYTES, frame.size), frame.copyOfRange(0, NONCE_BYTES), key)
    }

    fun blake2b256(data: ByteArray): ByteArray {
        val d = Blake2bDigest(256)
        d.update(data, 0, data.size)
        val out = ByteArray(32)
        d.doFinal(out, 0)
        return out
    }

    /** Lowercase hex BLAKE2b-256 of the public key. */
    fun fingerprint(publicKey: ByteArray): String = Hex.encode(blake2b256(publicKey))

    /** The 6 characters the user compares with the desktop prompt. */
    fun pairingCode(publicKey: ByteArray): String = fingerprint(publicKey).take(6).uppercase()

    private fun poly1305(key: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
        val mac = Poly1305()
        mac.init(KeyParameter(key))
        mac.update(data, off, len)
        val out = ByteArray(MAC_BYTES)
        mac.doFinal(out, 0)
        return out
    }

    fun hsalsa20(key: ByteArray, input: ByteArray): ByteArray {
        require(key.size == 32 && input.size == 16)
        val x = IntArray(16)
        x[0] = 0x61707865; x[5] = 0x3320646e; x[10] = 0x79622d32; x[15] = 0x6b206574
        for (i in 0 until 4) {
            x[1 + i] = le32(key, 4 * i)
            x[11 + i] = le32(key, 16 + 4 * i)
            x[6 + i] = le32(input, 4 * i)
        }
        repeat(10) {
            // column round
            x[4] = x[4] xor rotl(x[0] + x[12], 7); x[8] = x[8] xor rotl(x[4] + x[0], 9)
            x[12] = x[12] xor rotl(x[8] + x[4], 13); x[0] = x[0] xor rotl(x[12] + x[8], 18)
            x[9] = x[9] xor rotl(x[5] + x[1], 7); x[13] = x[13] xor rotl(x[9] + x[5], 9)
            x[1] = x[1] xor rotl(x[13] + x[9], 13); x[5] = x[5] xor rotl(x[1] + x[13], 18)
            x[14] = x[14] xor rotl(x[10] + x[6], 7); x[2] = x[2] xor rotl(x[14] + x[10], 9)
            x[6] = x[6] xor rotl(x[2] + x[14], 13); x[10] = x[10] xor rotl(x[6] + x[2], 18)
            x[3] = x[3] xor rotl(x[15] + x[11], 7); x[7] = x[7] xor rotl(x[3] + x[15], 9)
            x[11] = x[11] xor rotl(x[7] + x[3], 13); x[15] = x[15] xor rotl(x[11] + x[7], 18)
            // row round
            x[1] = x[1] xor rotl(x[0] + x[3], 7); x[2] = x[2] xor rotl(x[1] + x[0], 9)
            x[3] = x[3] xor rotl(x[2] + x[1], 13); x[0] = x[0] xor rotl(x[3] + x[2], 18)
            x[6] = x[6] xor rotl(x[5] + x[4], 7); x[7] = x[7] xor rotl(x[6] + x[5], 9)
            x[4] = x[4] xor rotl(x[7] + x[6], 13); x[5] = x[5] xor rotl(x[4] + x[7], 18)
            x[11] = x[11] xor rotl(x[10] + x[9], 7); x[8] = x[8] xor rotl(x[11] + x[10], 9)
            x[9] = x[9] xor rotl(x[8] + x[11], 13); x[10] = x[10] xor rotl(x[9] + x[8], 18)
            x[12] = x[12] xor rotl(x[15] + x[14], 7); x[13] = x[13] xor rotl(x[12] + x[15], 9)
            x[14] = x[14] xor rotl(x[13] + x[12], 13); x[15] = x[15] xor rotl(x[14] + x[13], 18)
        }
        val out = ByteArray(32)
        intArrayOf(0, 5, 10, 15, 6, 7, 8, 9).forEachIndexed { i, idx -> st32(out, 4 * i, x[idx]) }
        return out
    }

    private fun rotl(v: Int, c: Int) = Integer.rotateLeft(v, c)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun st32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (v in b) sb.append(DIGITS[(v.toInt() shr 4) and 15]).append(DIGITS[v.toInt() and 15])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val clean = s.filterNot { it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { clean.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
