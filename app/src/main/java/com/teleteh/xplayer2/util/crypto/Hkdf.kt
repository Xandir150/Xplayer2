package com.teleteh.xplayer2.util.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The SHA-256 half of PC Link's pairing primitives: HKDF (RFC 5869), HMAC, SHA-256, constant-time
 * comparison, hex, and CSPRNG bytes. X25519 itself is the vendored Tink code next door (see
 * `NOTICE.md`); everything here is plain `javax.crypto`, available since API 1.
 *
 * Kept free of Android imports so the pairing derivations are covered by plain JVM unit tests
 * against the same fixtures the Rust server uses (`PcLinkPairingCryptoTest`).
 */
object Hkdf {

    private const val HMAC_SHA256 = "HmacSHA256"

    /** RFC 5869 HKDF-Extract. `salt` may be empty; it is then all-zero per the RFC. */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        return hmacSha256(effectiveSalt, ikm)
    }

    /**
     * RFC 5869 HKDF-Expand. [length] must be at most 255 * 32 bytes (the RFC's cap; every caller
     * here asks for 8 or 32).
     */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        require(length <= 255 * 32) { "HKDF-Expand length must be <= 8160 bytes" }
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(prk, HMAC_SHA256))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var off = 0
        var i = 1
        while (off < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, length - off)
            System.arraycopy(t, 0, out, off, n)
            off += n
            i++
        }
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC_SHA256).run {
            // An empty HMAC key is legal per RFC 2104 but SecretKeySpec rejects it; no caller here
            // passes one, so failing loudly beats silently substituting something else.
            init(SecretKeySpec(key, HMAC_SHA256))
            doFinal(data)
        }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /**
     * Constant-time equality for MACs, commitments and tokens.
     *
     * [MessageDigest.isEqual] has been the constant-time comparison on every Android release we
     * support (the short-circuiting implementation was fixed in Android 4.4); it still returns
     * early on a *length* mismatch, which is fine — our lengths are public and fixed.
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** [count] fresh bytes from the OS CSPRNG. */
    fun randomBytes(count: Int): ByteArray = ByteArray(count).also { RANDOM.nextBytes(it) }

    private val RANDOM = SecureRandom()
}

/** Lowercase hex, the only binary encoding the PC Link wire format uses. */
object Hex {

    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0f]
        }
        return String(out)
    }

    /**
     * Strict decoder: lowercase hex only, even length, and — when [expectedLength] is given — that
     * exact byte count. Returns null instead of throwing, because every caller is parsing a field
     * off the wire where "reject the message" is the only sane response.
     *
     * Uppercase is rejected deliberately: the protocol says lowercase, and a receiver that quietly
     * accepts both invites the two implementations to drift on what they *send*.
     */
    fun decode(text: String, expectedLength: Int = -1): ByteArray? {
        if (text.length % 2 != 0) return null
        if (expectedLength >= 0 && text.length != expectedLength * 2) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = digit(text[i * 2])
            val lo = digit(text[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }
}
