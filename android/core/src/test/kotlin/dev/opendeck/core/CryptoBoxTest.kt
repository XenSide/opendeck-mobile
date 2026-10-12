package dev.opendeck.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

/** Vectors from D. J. Bernstein, "Cryptography in NaCl" (alice/bob, box.c / box2.c). */
class CryptoBoxTest {
    private val aliceSk = Hex.decode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePk = Hex.decode("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobSk = Hex.decode("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPk = Hex.decode("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    private val firstKey = Hex.decode("1b27556473e985d462cd51197a9a46c76009549eac6474f206c4ee0844f68389")
    private val nonce = Hex.decode("69696ee955b62b73cd62bda875fc73d68219e0036b7a0b37")
    private val message = Hex.decode(
        "be075fc53c81f2d5cf141316ebeb0c7b5228c52a4c62cbd44b66849b64244ffc" +
            "e5ecbaaf33bd751a1ac728d45e6c61296cdc3c01233561f41db66cce314adb31" +
            "0e3be8250c46f06dceea3a7fa1348057e2f6556ad6b1318a024a838f21af1fde" +
            "048977eb48f59ffd4924ca1c60902e52f0a089bc76897040e082f93776384864" +
            "5e0705",
    )
    // MAC (16) || ciphertext (131)
    private val boxed = Hex.decode(
        "f3ffc7703f9400e52a7dfb4b3d3305d98e993b9f48681273c29650ba32fc76ce" +
            "48332ea7164d96a4476fb8c531a1186ac0dfc17c98dce87b4da7f011ec48c972" +
            "71d2c20f9b928fe2270d6fb863d51738b48eeee314a7cc8ab932164548e526ae" +
            "90224368517acfeabd6bb3732bc0e9da99832b61ca01b6de56244a9e88d5f9b3" +
            "7973f622a43d14a6599b1f654cb45a74e355a5",
    )

    @Test fun publicKeysDerive() {
        assertArrayEquals(alicePk, CryptoBox.publicKeyOf(aliceSk))
        assertArrayEquals(bobPk, CryptoBox.publicKeyOf(bobSk))
    }

    @Test fun beforenmMatchesFirstKeyFromBothSides() {
        assertArrayEquals(firstKey, CryptoBox.beforenm(bobPk, aliceSk))
        assertArrayEquals(firstKey, CryptoBox.beforenm(alicePk, bobSk))
    }

    @Test fun boxMatchesVector() {
        assertEquals(131, message.size)
        assertArrayEquals(boxed, CryptoBox.easyAfternm(message, nonce, firstKey))
        assertArrayEquals(message, CryptoBox.openEasyAfternm(boxed, nonce, firstKey))
    }

    @Test fun tamperedBoxRejected() {
        val bad = boxed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertThrows(CryptoException::class.java) { CryptoBox.openEasyAfternm(bad, nonce, firstKey) }
    }

    @Test fun frameRoundTripAndLength() {
        val rnd = SecureRandom()
        val frame = CryptoBox.sealFrame(message, firstKey, rnd)
        assertEquals(message.size + 40, frame.size)
        assertArrayEquals(message, CryptoBox.openFrame(frame, firstKey))
        assertThrows(CryptoException::class.java) { CryptoBox.openFrame(ByteArray(39), firstKey) }
    }

    @Test fun blake2bEmpty() {
        assertEquals(
            "0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8",
            Hex.encode(CryptoBox.blake2b256(ByteArray(0))),
        )
    }

    @Test fun pairingCodeIsUppercasedFingerprintPrefix() {
        val fp = CryptoBox.fingerprint(alicePk)
        assertEquals(64, fp.length)
        assertEquals(fp.substring(0, 6).uppercase(), CryptoBox.pairingCode(alicePk))
    }

    @Test fun generatedKeyPairsAgree() {
        val a = CryptoBox.generateKeyPair()
        val b = CryptoBox.generateKeyPair()
        assertArrayEquals(CryptoBox.beforenm(b.publicKey, a.secretKey), CryptoBox.beforenm(a.publicKey, b.secretKey))
    }
}
