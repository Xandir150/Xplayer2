package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PcLinkSessionLink] — the §2.18.4 dress code and the §2.18.6 finality — for the rules the
 * committed vectors cannot reach.
 *
 * `envelope_vectors.json` is a *receiver* fixture: it drives one direction of one already-engaged
 * link with lines someone else produced. What it cannot express, and what is therefore here:
 *
 * * the plaintext half of a session's life — what a link does before [PcLinkSessionLink.engage],
 *   and that engaging is what changes it;
 * * the §2.18.4 `auth_fail` exception, which is role-dependent and lives outside the envelope;
 * * both directions at once, which is what makes a reflected envelope a wrong-key failure;
 * * the §2.18.5 counter bound, thousands of years of messages away on the wire;
 * * that a *send* on a dead link is refused, which no received-lines fixture can state.
 */
class PcLinkSessionLinkTest {

    private val ltk = Hex.decode("d744403b2350c525154ceb371584487c4345d884945074fedd31b52ca19a0315", 32)!!
    private val nonceC = Hex.decode("202122232425262728292a2b2c2d2e2f", 16)!!
    private val nonceS = Hex.decode("303132333435363738393a3b3c3d3e3f", 16)!!
    private val keys = PcLinkSessionKeys.derive(ltk, nonceC, nonceS)

    private fun client() = PcLinkSessionLink(PeerRole.CLIENT)
    private fun server() = PcLinkSessionLink(PeerRole.SERVER)

    private val idr = """{"type":"idr"}"""
    private val authFail = """{"type":"auth_fail","reason":"unknown_client"}"""

    // ---- before engagement: v1, unchanged ---------------------------------------------------------

    @Test
    fun aPlaintextLinkPassesLinesThroughUntouched() {
        val link = client()
        assertFalse(link.isEncrypted)
        assertEquals("$idr\n", link.seal(idr))
        // Newline framing belongs to the link, so handing it one back is not doubling it.
        assertEquals("$idr\n", link.seal("$idr\n"))
        assertEquals(idr, link.accept(idr))
        assertNull(link.failure)
    }

    /**
     * §2.18.6 carves `enc` out of §2's ignore-unknown-types rule: we know the type, so we do not get
     * the escape a version-1 receiver gets. An envelope arriving on a session that negotiated none
     * is somebody splicing, and it ends the session rather than being dropped as "some future type".
     */
    @Test
    fun anEnvelopeBeforeEngagementIsFatal() {
        val link = client()
        assertEquals("plaintext", failureOf { link.accept(PcLinkEnvelope.line(0, ByteArray(32))) }.code)
    }

    @Test
    fun anUnparseableLineBeforeEngagementIsFatal() {
        assertEquals("malformed", failureOf { client().accept("not json at all") }.code)
        assertEquals("malformed", failureOf { client().accept("") }.code)
    }

    // ---- engaging ---------------------------------------------------------------------------------

    @Test
    fun engagingSwitchesBothHalvesAtOnce() {
        val link = client()
        link.engage(keys)
        assertTrue(link.isEncrypted)

        val line = link.seal(idr).trimEnd('\n')
        val envelope = JSONObject(line)
        assertEquals("enc", envelope.getString("type"))
        assertEquals(0, envelope.getInt("n"))
        assertNotEquals("the message must not be readable on the wire", idr, line)
        assertFalse(line.contains("idr"))

        // And the receiving half now demands an envelope of its own.
        assertEquals("plaintext", failureOf { link.accept(idr) }.code)
    }

    @Test
    fun theTwoRolesTalkToEachOtherInBothDirections() {
        val phone = client()
        val pc = server()
        phone.engage(keys)
        pc.engage(keys)

        assertEquals(idr, pc.accept(phone.seal(idr).trimEnd('\n')))
        val authOk = """{"type":"auth_ok","videoToken":"${"ab".repeat(32)}"}"""
        assertEquals(authOk, phone.accept(pc.seal(authOk).trimEnd('\n')))
        // Counters advance per direction, independently.
        assertEquals("""{"type":"ping","t_us":7}""", pc.accept(phone.seal("""{"type":"ping","t_us":7}""").trimEnd('\n')))
    }

    /**
     * Two keys, not one: a client's own envelope played back at it is ciphertext under `k_c2s` while
     * the client opens with `k_s2c`. Reflection is the attack the direction split exists to kill,
     * and it fails as a wrong key rather than as a counter — the counter is right, which is the
     * point.
     */
    @Test
    fun aReflectedEnvelopeFailsOnTheKey() {
        val phone = client()
        phone.engage(keys)
        val own = phone.seal(idr).trimEnd('\n')
        assertEquals("decrypt", failureOf { phone.accept(own) }.code)
    }

    /** A different session's keys open nothing, which is what makes a recording worthless. */
    @Test
    fun anotherSessionsKeysDoNotOpen() {
        val phone = client()
        phone.engage(keys)
        val pc = server()
        pc.engage(PcLinkSessionKeys.derive(ltk, nonceC, ByteArray(16) { 0x7f }))
        assertEquals("decrypt", failureOf { pc.accept(phone.seal(idr).trimEnd('\n')) }.code)
    }

    // ---- §2.18.4's one exception -----------------------------------------------------------------

    /**
     * A client that has sent its proof is waiting on `auth_ok`, and a PC that refuses that proof has
     * no keys the client trusts — so its `auth_fail` is legal in the clear. The link passes it up;
     * whether to believe its `reason` is [PairingSession]'s judgement, not the transport's (and the
     * answer, on an encrypted session, is no).
     */
    @Test
    fun aClientStillAcceptsAPlaintextAuthFail() {
        val link = client()
        link.engage(keys)
        assertEquals(authFail, link.accept(authFail))
        assertNull("a legal message must not kill the link", link.failure)
        // Everything else in the clear is still refused.
        assertEquals("plaintext", failureOf { link.accept(idr) }.code)
    }

    /** The exception is the client's alone: nothing ever tells a server its proof was refused. */
    @Test
    fun aServerDoesNotGetTheAuthFailException() {
        val link = server()
        link.engage(keys)
        assertEquals("plaintext", failureOf { link.accept(authFail) }.code)
    }

    // ---- malformed envelopes ----------------------------------------------------------------------

    /**
     * A line labelled `enc` that has no readable counter, or no ciphertext string, is not an
     * envelope at all — so it is the *wrong dress* rather than a bad envelope, matching how the
     * reference's decoder classifies a message it cannot read. `optLong` would have turned every
     * one of these into a perfectly plausible counter 0.
     */
    @Test
    fun aLineLabelledEncThatIsNotOneIsTheWrongDress() {
        for (bad in listOf(
            """{"type":"enc","c":"${"00".repeat(32)}"}""",
            """{"type":"enc","n":"0","c":"${"00".repeat(32)}"}""",
            """{"type":"enc","n":0.5,"c":"${"00".repeat(32)}"}""",
            """{"type":"enc","n":-1,"c":"${"00".repeat(32)}"}""",
            """{"type":"enc","n":0}""",
            """{"type":"enc","n":0,"c":42}"""
        )) {
            val link = server()
            link.engage(keys)
            assertEquals("not an envelope: $bad", "plaintext", failureOf { link.accept(bad) }.code)
        }
    }

    /**
     * A counter past anything a sender may legally use is still just a counter that isn't the
     * expected one — the §2.18.5 bound governs senders, and a receiver has a simpler answer.
     */
    @Test
    fun anAbsurdlyLargeCounterIsACounterFailure() {
        val link = server()
        link.engage(keys)
        val line = """{"type":"enc","n":9007199254740992,"c":"${"00".repeat(32)}"}"""
        assertEquals("counter", failureOf { link.accept(line) }.code)
    }

    @Test
    fun aCiphertextShorterThanItsOwnTagIsMalformed() {
        val link = server()
        link.engage(keys)
        assertEquals("malformed", failureOf { link.accept(PcLinkEnvelope.line(0, ByteArray(15))) }.code)
    }

    /**
     * An envelope that opens to something that is not one JSON message. Only a keyed peer can
     * produce this, so it is a broken implementation rather than an attack — and it is still fatal,
     * because there is no way to know which.
     */
    @Test
    fun anEnvelopeThatOpensToNonJsonIsMalformed() {
        val pc = server()
        val phoneSealer = PcLinkSealer(keys.c2s)
        pc.engage(keys)
        assertEquals("malformed", failureOf { pc.accept(phoneSealer.seal("plain old text")!!) }.code)
    }

    @Test
    fun anEnvelopeInsideAnEnvelopeIsRefused() {
        val pc = server()
        val phone = client()
        pc.engage(keys)
        phone.engage(keys)
        val inner = phone.seal(idr).trimEnd('\n')
        // Seal that whole envelope a second time, from the same sealer so the counters stay honest:
        // the outer opens perfectly and what comes out is another `enc`, which is where it stops.
        val nested = phone.seal(inner).trimEnd('\n')
        assertEquals(idr, pc.accept(inner))
        assertEquals("nested", failureOf { pc.accept(nested) }.code)
    }

    // ---- §2.18.5's bound ---------------------------------------------------------------------------

    @Test
    fun theCounterIsExhaustedBeforeJsonPrecisionIs() {
        val sealer = PcLinkSealer(keys.c2s)
        sealer.fastForwardTo(PcLinkEnvelope.MAX_COUNTER)
        assertTrue("the last legal counter", sealer.seal("{}") != null)
        assertNull("2^53 is one too many", sealer.seal("{}"))
        assertNull("exhaustion is permanent", sealer.seal("{}"))
    }

    /**
     * And on the link itself the bound is a §2.18.6 failure like any other: the session ends rather
     * than reuse a (key, nonce) pair, and — because the failure sticks — it cannot be sent past.
     */
    @Test
    fun aSpentCounterEndsTheSessionRatherThanReuseANonce() {
        val link = client()
        link.engage(keys)
        link.fastForwardCounter(PcLinkEnvelope.MAX_COUNTER)
        link.seal(idr) // the last legal envelope
        assertEquals("exhausted", failureOf { link.seal(idr) }.code)
        assertEquals("exhausted", failureOf { link.accept(idr) }.code)
    }

    // ---- §2.18.6 finality ---------------------------------------------------------------------------

    /**
     * The rule the reference originally got wrong, stated as a test: a link that has failed refuses
     * *everything*, sends included, so a caller that swallowed the exception cannot carry on. Any
     * later call reports the original failure, not a new one — the first thing that went wrong is
     * the thing worth logging.
     */
    @Test
    fun aFailedLinkRefusesEverythingForEver() {
        val link = server()
        link.engage(keys)
        val first = failureOf { link.accept(idr) }
        assertEquals("plaintext", first.code)

        repeat(3) {
            assertEquals(first.code, failureOf { link.accept(idr) }.code)
            assertEquals(first.code, failureOf { link.seal(idr) }.code)
        }
        // Even a line that would have been perfectly good.
        val good = PcLinkSealer(keys.c2s).seal(idr)!!
        assertEquals(first.code, failureOf { link.accept(good) }.code)
        assertEquals(first.code, link.failure?.code)
    }

    /** A refused envelope does not advance the counter — the state stays coherent for the log. */
    @Test
    fun aRefusedEnvelopeDoesNotAdvanceTheCounter() {
        val opener = PcLinkOpener(keys.c2s)
        val sealer = PcLinkSealer(keys.c2s)
        val line = JSONObject(sealer.seal(idr)!!)
        val tampered = line.getString("c").let { it.dropLast(1) + if (it.last() == 'a') 'b' else 'a' }

        assertTrue(opener.open(0, tampered) is PcLinkOpener.OpenResult.Failed)
        assertEquals(0L, opener.expectedCounter)
        assertEquals(
            idr,
            (opener.open(0, line.getString("c")) as PcLinkOpener.OpenResult.Opened).plaintext
        )
        assertEquals(1L, opener.expectedCounter)
    }

    // ---- sizing --------------------------------------------------------------------------------------

    @Test
    fun envelopeLinesFitTheSpecifiedCapacity() {
        val sealer = PcLinkSealer(keys.c2s)
        sealer.fastForwardTo(PcLinkEnvelope.MAX_COUNTER) // the longest counter digits
        val inner = """{"type":"ping","t_us":${Long.MAX_VALUE}}"""
        val line = sealer.seal(inner)!!
        assertTrue(
            "${line.length} > ${PcLinkEnvelope.envelopeLineCapacity(inner.length)}",
            line.length <= PcLinkEnvelope.envelopeLineCapacity(inner.length)
        )
        // §2.18.4's floor for a receiver keeping the §2 inner cap.
        assertEquals(
            2 * PcLinkProtocol.MAX_LINE_LEN + 96,
            PcLinkEnvelope.envelopeLineCapacity(PcLinkProtocol.MAX_LINE_LEN)
        )
    }

    @Test
    fun sessionKeysDoNotPrintThemselves() {
        assertEquals("PcLinkSessionKeys(<redacted>)", keys.toString())
        assertFalse(keys.toString().contains(Hex.encode(keys.c2s).take(8)))
    }

    private inline fun failureOf(body: () -> Unit): PcLinkLinkFailure = try {
        body()
        error("expected the link to refuse this")
    } catch (e: PcLinkLinkException) {
        e.failure
    }
}
