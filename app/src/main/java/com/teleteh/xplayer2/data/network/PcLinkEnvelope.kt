package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.ChaCha20Poly1305
import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control-channel encryption, `protocol.md` §2.18 — the `enc` envelope and its key schedule,
 * transcribed from the reference implementation (`xpl-proto/src/envelope.rs`) and held to the same
 * bytes by the same fixture (`app/src/test/resources/pclink_envelope_vectors.json`, a verbatim copy
 * of the server's `envelope_vectors.json`).
 *
 * ```
 * prk2  = HKDF-Extract(salt = nonceC || nonceS, ikm = ltk)     (SHA-256)
 * k_c2s = HKDF-Expand(prk2, "XPL2 v2 c2s", 32)
 * k_s2c = HKDF-Expand(prk2, "XPL2 v2 s2c", 32)
 * nonce = 0x00000000 || u64be(n)
 * line  = {"type":"enc","n":<n>,"c":"<hex of ciphertext||tag>"}
 * ```
 *
 * Two keys, not one, and never interchangeable: each direction is sealed under a key its own
 * receiver never seals with, so a client's envelope reflected back at it is ciphertext under the
 * wrong key. Freshness needs nothing extra — both handshake nonces are drawn fresh per connection
 * by opposite sides, so a recorded session can never be replayed into a new one. The counter is the
 * nonce *and* the replay tripwire: TCP delivers this stream in order, so a receiver demands exactly
 * 0, 1, 2, … and treats any repeat or gap as tampering rather than something to skip past.
 *
 * Everything here is sans-io and pure: no sockets, no Android. [PcLinkSessionLink] adds the
 * §2.18.4/§2.18.6 dress code on top, and the transports ([PcLinkClient], [PcLinkPairingClient]) own
 * only the socket.
 */
object PcLinkEnvelope {

    /** The plaintext transport of every build before §2.18 existed. */
    const val PLAINTEXT = 1

    /** The ChaCha20-Poly1305 envelope of §2.18. */
    const val AEAD = 2

    /**
     * The highest encryption version this client speaks — what it offers in `pair_start` /
     * `auth_challenge`. Drops to [PLAINTEXT] on a device with no ChaCha20-Poly1305 provider, because
     * §2.18.1's offer is a promise: offering an envelope we cannot open would turn a working v1
     * session into a dead v2 one.
     */
    val VERSION: Int get() = if (ChaCha20Poly1305.isAvailable) AEAD else PLAINTEXT

    /** HKDF info string for the client→server key. */
    const val C2S_INFO = "XPL2 v2 c2s"

    /** HKDF info string for the server→client key. */
    const val S2C_INFO = "XPL2 v2 s2c"

    const val KEY_LEN = 32
    const val NONCE_LEN = ChaCha20Poly1305.NONCE_LEN
    const val TAG_LEN = ChaCha20Poly1305.TAG_LEN

    /** The envelope's own message type — carved out of §2's ignore-unknown-types rule by §2.18.6. */
    const val TYPE = "enc"

    /**
     * Largest counter a sender may use: 2⁵³ − 1, the biggest integer every JSON implementation
     * carries exactly (§2.18.5). A session ends rather than seal envelope number 2⁵³ — thousands of
     * years of messages away at any plausible rate, checked so it is a fact and not an assumption.
     */
    const val MAX_COUNTER = (1L shl 53) - 1

    /**
     * The outer line cap a receiver needs for envelopes whose inner lines it caps at [innerCap]
     * (§2.18.4): hex doubles the message, the tag adds 32 chars, the envelope's JSON at most 64 more.
     */
    fun envelopeLineCapacity(innerCap: Int): Int = 2 * innerCap + 96

    /** `0x00000000 || u64be(n)` — big-endian, like every multi-byte integer in this protocol. */
    fun nonceFor(counter: Long): ByteArray =
        ByteBuffer.allocate(NONCE_LEN).order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putLong(counter)
            .array()

    /** The `{"type":"enc",…}` line for an already-sealed ciphertext. Field order is the canonical one. */
    fun line(counter: Long, ciphertext: ByteArray): String =
        """{"type":"$TYPE","n":$counter,"c":"${Hex.encode(ciphertext)}"}"""
}

/**
 * Both directions of one encrypted session, from the §2.18.3 schedule.
 *
 * Derived once from the long-term key and the two handshake nonces — the §2.11/§2.12 auth nonces on
 * a reconnect, the ceremony's own `clientNonce`/`serverNonce` on a fresh pairing — and split by role
 * so "sealed with the wrong direction's key" is not a mistake a caller can express.
 *
 * Forward secrecy, stated honestly: none beyond those nonces. Whoever learns the LTK and recorded a
 * session can derive its keys — that is §2.6's trust model unchanged, and accepting it is what lets
 * version 2 ship with no new handshake and no pairing change.
 */
class PcLinkSessionKeys(val c2s: ByteArray, val s2c: ByteArray) {

    /** The phone's half: seals with `k_c2s`, opens with `k_s2c`. */
    fun clientLink(): PcLinkEnvelopeLink =
        PcLinkEnvelopeLink(PcLinkSealer(c2s), PcLinkOpener(s2c))

    /** The PC's half: seals with `k_s2c`, opens with `k_c2s`. Used by the tests and the vectors. */
    fun serverLink(): PcLinkEnvelopeLink =
        PcLinkEnvelopeLink(PcLinkSealer(s2c), PcLinkOpener(c2s))

    override fun equals(other: Any?): Boolean = other is PcLinkSessionKeys &&
        Hkdf.constantTimeEquals(c2s, other.c2s) && Hkdf.constantTimeEquals(s2c, other.s2c)

    override fun hashCode(): Int = c2s.contentHashCode() * 31 + s2c.contentHashCode()

    /** Never the key bytes: these end up in logs and crash reports. */
    override fun toString(): String = "PcLinkSessionKeys(<redacted>)"

    companion object {
        /**
         * Runs the §2.18.3 schedule. [nonceC] is the client's 16 handshake nonce bytes and [nonceS]
         * the server's; the salt is their concatenation in exactly that order.
         */
        fun derive(ltk: ByteArray, nonceC: ByteArray, nonceS: ByteArray): PcLinkSessionKeys {
            require(ltk.size == PcLinkEnvelope.KEY_LEN) { "ltk must be 32 bytes" }
            require(nonceC.size == PcLinkPairingCrypto.NONCE_LEN) { "nonceC must be 16 bytes" }
            require(nonceS.size == PcLinkPairingCrypto.NONCE_LEN) { "nonceS must be 16 bytes" }
            val prk = Hkdf.extract(nonceC + nonceS, ltk)
            return PcLinkSessionKeys(
                c2s = Hkdf.expand(prk, PcLinkEnvelope.C2S_INFO.toByteArray(Charsets.US_ASCII), PcLinkEnvelope.KEY_LEN),
                s2c = Hkdf.expand(prk, PcLinkEnvelope.S2C_INFO.toByteArray(Charsets.US_ASCII), PcLinkEnvelope.KEY_LEN)
            )
        }
    }
}

/** One side's envelope state: the [sealer] for what it sends, the [opener] for what it receives. */
class PcLinkEnvelopeLink(val sealer: PcLinkSealer, val opener: PcLinkOpener)

/** The sending half of one direction. */
class PcLinkSealer(private val key: ByteArray) {

    /** The counter the next envelope will carry. */
    var nextCounter: Long = 0
        private set

    /**
     * Seals one control message into its envelope line (no trailing newline), advancing the counter.
     * [plaintext] is the exact JSON text of the inner message.
     *
     * Null once the counter is spent (§2.18.5) — the session must end rather than reuse a nonce.
     */
    fun seal(plaintext: String): String? {
        if (nextCounter > PcLinkEnvelope.MAX_COUNTER) return null
        val n = nextCounter
        val sealed = ChaCha20Poly1305.seal(
            key,
            PcLinkEnvelope.nonceFor(n),
            plaintext.toByteArray(Charsets.UTF_8)
        )
        nextCounter = n + 1
        return PcLinkEnvelope.line(n, sealed)
    }

    /** Test seam for the §2.18.5 bound, which is otherwise thousands of years away. */
    internal fun fastForwardTo(counter: Long) {
        nextCounter = counter
    }
}

/** The receiving half of one direction. */
class PcLinkOpener(private val key: ByteArray) {

    /** The counter the next envelope must carry. */
    var expectedCounter: Long = 0
        private set

    /**
     * Opens one envelope given its decoded `n` and `c`, yielding the inner message's JSON text and
     * advancing the counter.
     *
     * Any failure is terminal for the session (§2.18.6): the counter does not advance, and the
     * caller must not try the next line. [PcLinkSessionLink] is what makes that unmissable.
     */
    fun open(n: Long, cHex: String): OpenResult {
        // The counter first, before any decryption: a replayed envelope authenticates perfectly,
        // which is exactly why it cannot be the cipher's job to catch it.
        if (n != expectedCounter) {
            return OpenResult.Failed(PcLinkLinkFailure.Counter(expectedCounter, n))
        }
        // §2.6's hex discipline applies to `c` too: lowercase only, and a ciphertext shorter than
        // its own tag cannot be anything.
        if (cHex.length < 2 * PcLinkEnvelope.TAG_LEN) return OpenResult.Failed(PcLinkLinkFailure.Malformed)
        val ciphertext = Hex.decode(cHex) ?: return OpenResult.Failed(PcLinkLinkFailure.Malformed)
        val plaintext = ChaCha20Poly1305.open(key, PcLinkEnvelope.nonceFor(n), ciphertext)
            ?: return OpenResult.Failed(PcLinkLinkFailure.Decrypt)
        val text = try {
            // Strict UTF-8: only a keyed peer can produce bytes that authenticate and aren't text,
            // so this is a broken implementation rather than an attack the cipher failed to stop —
            // the fixture files it under `malformed` with the rest of the unreadable.
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString()
        } catch (_: Exception) {
            return OpenResult.Failed(PcLinkLinkFailure.Malformed)
        }
        expectedCounter = n + 1
        return OpenResult.Opened(text)
    }

    sealed interface OpenResult {
        data class Opened(val plaintext: String) : OpenResult
        data class Failed(val failure: PcLinkLinkFailure) : OpenResult
    }
}

/**
 * Why a control line ended the session (§2.18.6).
 *
 * Every one of these is terminal; the distinctions exist for the log and for the vectors, which
 * name them exactly as [code] does, so a wrong implementation fails on the right step rather than
 * merely failing.
 */
sealed class PcLinkLinkFailure(val code: String, val detail: String) {

    /**
     * `counter` — `n` was not the expected next value: a replay, a deletion, or a broken sender.
     * A gap means something was removed in flight, because TCP would have delivered it.
     */
    class Counter(val expected: Long, val got: Long) :
        PcLinkLinkFailure("counter", "envelope counter $got where $expected was expected")

    /** `malformed` — not lowercase hex of at least a tag, or an envelope that opened to non-JSON. */
    data object Malformed : PcLinkLinkFailure("malformed", "an envelope that is not one readable message")

    /** `decrypt` — the tag did not verify: tampering, the other direction's key, another session's. */
    data object Decrypt : PcLinkLinkFailure("decrypt", "an envelope that failed authentication")

    /** `nested` — an envelope inside an envelope. */
    data object Nested : PcLinkLinkFailure("nested", "an envelope inside an envelope")

    /**
     * `plaintext` — a line in the wrong dress: anything but a well-formed envelope once encryption
     * engaged (the §2.18.4 `auth_fail` exception aside), or an `enc` line on a session that
     * negotiated none.
     */
    data object Plaintext : PcLinkLinkFailure("plaintext", "a control line in the wrong dress")

    /** `exhausted` — §2.18.5: this side's counter is spent, and a nonce is never reused. */
    data object Exhausted : PcLinkLinkFailure("exhausted", "the envelope counter is spent")

    override fun toString(): String = "$detail; the session is over"
}

/**
 * Thrown by [PcLinkSessionLink] on any §2.18.6 failure.
 *
 * An [IOException] on purpose: both transports already end a session on one, so a refused envelope
 * lands in the same teardown as a dropped socket instead of needing a parallel path that a caller
 * could forget to write.
 */
class PcLinkLinkException(val failure: PcLinkLinkFailure) : IOException(failure.toString())

/**
 * One control connection's dress code: §2.18.4 and §2.18.6.
 *
 * Holds the session's envelope state from the moment [engage] is called, dresses outgoing lines and
 * classifies incoming ones. It exists to make one rule unmissable, because it is the rule a
 * hand-written client gets wrong: **an envelope that cannot be accepted ends the session.** Not "is
 * skipped", not "is retried at the next line" — a receiver that reads on after a refused envelope
 * has resynchronized past exactly the tampering the envelope was there to catch.
 *
 * So [accept] throws a [PcLinkLinkException] rather than returning an absence (a timeout, an
 * ignorable line), the link *remembers* the failure, and every later call — receiving **or
 * sending** — throws that same failure instead of doing anything. Even a caller that swallows the
 * exception cannot get another message out of the link: after a failed envelope there really is no
 * next line. Timeouts, closes and the rest of the socket's business stay with the caller, which is
 * the only part of this a port should have to write itself.
 *
 * Not thread-safe by construction, but [seal] and [accept] are synchronized on the link: the
 * counters must follow wire order, and the two transports each have one reader and one writer
 * coroutine that could otherwise interleave.
 */
class PcLinkSessionLink(private val role: PeerRole = PeerRole.CLIENT) {

    private var crypto: PcLinkEnvelopeLink? = null

    /**
     * The failure that ended this link, if one has. Once set it never clears — that is the whole of
     * §2.18.6's finality, held in one field.
     */
    @Volatile
    var failure: PcLinkLinkFailure? = null
        private set

    /** Whether everything on this link is now enveloped. */
    val isEncrypted: Boolean get() = crypto != null

    /**
     * Switches to the §2.18.4 envelope, at exactly the point the FSM's
     * [PairingEffect.EngageEncryption] said to: after our own last handshake message.
     */
    @Synchronized
    fun engage(keys: PcLinkSessionKeys) {
        crypto = when (role) {
            PeerRole.CLIENT -> keys.clientLink()
            PeerRole.SERVER -> keys.serverLink()
        }
    }

    /**
     * Dresses one outgoing line for the wire: sealed if encryption is engaged, unchanged if not.
     * Newline-terminated either way, with or without one on the way in.
     *
     * Serializing stays the caller's business so a message we cannot even encode — a local bug — is
     * not dressed up as a session failure.
     */
    @Synchronized
    fun seal(line: String): String {
        failure?.let { throw PcLinkLinkException(it) }
        val plain = line.trimEnd('\r', '\n')
        val link = crypto ?: return plain + "\n"
        val sealed = link.sealer.seal(plain) ?: throw die(PcLinkLinkFailure.Exhausted)
        return sealed + "\n"
    }

    /**
     * Classifies one received line: the JSON text of the message to act on, or a thrown
     * [PcLinkLinkException].
     *
     * A line is required — "nothing arrived within the timeout" is the caller's business and must
     * never reach here, precisely so it cannot be confused with any of these answers.
     *
     * Once this has thrown it throws the same failure for ever, without looking at the line.
     */
    @Synchronized
    fun accept(line: String): String {
        failure?.let { throw PcLinkLinkException(it) }
        val message = runCatching { JSONObject(line) }.getOrNull()
        val link = crypto
        if (link == null) {
            // Before engagement the link is v1's: plain messages, and an `enc` line that has no
            // business existing yet. §2.18.6 carves `enc` out of §2's ignore-unknown-types rule for
            // exactly this — we know the type, so we do not get the escape.
            if (message == null) throw die(PcLinkLinkFailure.Malformed)
            if (message.optString("type") == PcLinkEnvelope.TYPE) throw die(PcLinkLinkFailure.Plaintext)
            return line
        }
        // After engagement every line must be an envelope; one that is not — including one that
        // does not parse at all — is the wrong dress, which is how the vectors classify it. The one
        // exception is §2.18.4's: the plaintext verdict of a server that rejected the proof we were
        // waiting on, on a session that therefore has no keys we trust. Its *reason* is not to be
        // trusted (that judgement is PairingSession's), but the message itself is legal.
        if (message == null || message.optString("type") != PcLinkEnvelope.TYPE) {
            if (role == PeerRole.CLIENT && message?.optString("type") == "auth_fail") return line
            throw die(PcLinkLinkFailure.Plaintext)
        }
        // A line that says `enc` but has no counter, or no ciphertext string, is not an envelope at
        // all — so it is the wrong dress rather than a bad envelope, which is how the reference
        // classifies it (its decoder simply fails to read the message). Both end the session; the
        // codes are named so three implementations can be held to the same one.
        val n = counterOf(message)
        val cHex = message.opt("c") as? String
        if (n == null || cHex == null) throw die(PcLinkLinkFailure.Plaintext)
        return when (val opened = link.opener.open(n, cHex)) {
            is PcLinkOpener.OpenResult.Failed -> throw die(opened.failure)
            is PcLinkOpener.OpenResult.Opened -> {
                val inner = runCatching { JSONObject(opened.plaintext) }.getOrNull()
                    ?: throw die(PcLinkLinkFailure.Malformed)
                if (inner.optString("type") == PcLinkEnvelope.TYPE) throw die(PcLinkLinkFailure.Nested)
                opened.plaintext
            }
        }
    }

    /**
     * `n` as the wire spells it: a non-negative whole number. `optLong` would quietly turn a
     * fractional, absent or quoted `n` into 0 and hand a *valid* counter to the opener, so the field
     * is read strictly and anything else refuses the line.
     *
     * No upper bound here on purpose. [PcLinkEnvelope.MAX_COUNTER] binds what a *sender* may use; a
     * receiver simply finds that a counter past its own expectation does not match, which is the
     * `counter` tripwire and the same answer the reference gives.
     */
    private fun counterOf(message: JSONObject): Long? = when (val value = message.opt("n")) {
        is Int -> value.toLong().takeIf { it >= 0 }
        is Long -> value.takeIf { it >= 0 }
        else -> null
    }

    /**
     * Test seam for the §2.18.5 bound: no test is going to seal 2⁵³ envelopes to reach it, and the
     * rule that a spent counter *ends the session* deserves a test more than it deserves to be
     * unreachable.
     */
    @Synchronized
    internal fun fastForwardCounter(counter: Long) {
        crypto?.sealer?.fastForwardTo(counter)
    }

    private fun die(failure: PcLinkLinkFailure): PcLinkLinkException {
        if (this.failure == null) this.failure = failure
        return PcLinkLinkException(this.failure!!)
    }
}
