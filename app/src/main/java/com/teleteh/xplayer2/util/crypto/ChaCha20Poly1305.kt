package com.teleteh.xplayer2.util.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 (RFC 8439) as PC Link's control-channel envelope uses it: a 32-byte key, a
 * 12-byte nonce, no associated data, and the 16-byte Poly1305 tag appended to the ciphertext.
 *
 * Plain `javax.crypto`, like [Hkdf] next door — no vendored code and no third-party provider. The
 * transformation is spelled differently by the two JCE implementations this has to run on:
 * Conscrypt (Android, API 28+) documents `ChaCha20/Poly1305/NoPadding`, SunJCE (the JVM the unit
 * tests run on, Java 11+) registers `ChaCha20-Poly1305`. Both names are tried once and the winner
 * cached, so the same code is exercised by the JVM vector tests and by the phone.
 *
 * [isAvailable] exists because the offer in `protocol.md` §2.18.1 has to be honest: a device whose
 * JCE cannot do this must offer encryption version 1 rather than promise an envelope it can't open.
 */
object ChaCha20Poly1305 {

    const val KEY_LEN = 32
    const val NONCE_LEN = 12

    /** The Poly1305 tag every ciphertext carries, last. */
    const val TAG_LEN = 16

    private const val KEY_ALGORITHM = "ChaCha20"

    private val TRANSFORMATIONS = listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")

    /** The transformation this JCE answers to, or null on a platform that has none. */
    private val transformation: String? by lazy {
        TRANSFORMATIONS.firstOrNull { name ->
            try {
                Cipher.getInstance(name)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Whether this device can run the cipher at all. False disqualifies us from offering v2. */
    val isAvailable: Boolean get() = transformation != null

    /**
     * `ciphertext || tag` for [plaintext] under [key] and [nonce].
     *
     * Throws only on a broken platform or a caller-supplied key/nonce of the wrong length — never
     * on the data, which is why the envelope's sealing path has no failure mode of its own beyond
     * the counter bound.
     */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce).doFinal(plaintext)

    /**
     * The plaintext of `ciphertext || tag`, or **null** when the tag does not verify — tampered
     * bytes, or bytes sealed under another key. Null is the only failure a receiver may act on;
     * everything else about a rejected envelope is the caller's §2.18.6 business.
     */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < TAG_LEN) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, nonce).doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray): Cipher {
        require(key.size == KEY_LEN) { "ChaCha20-Poly1305 needs a 32-byte key" }
        require(nonce.size == NONCE_LEN) { "ChaCha20-Poly1305 needs a 12-byte nonce" }
        val name = transformation ?: throw IllegalStateException("no ChaCha20-Poly1305 provider")
        return Cipher.getInstance(name).apply {
            init(mode, SecretKeySpec(key, KEY_ALGORITHM), IvParameterSpec(nonce))
        }
    }
}
