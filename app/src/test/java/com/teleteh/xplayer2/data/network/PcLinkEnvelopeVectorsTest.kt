package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.ChaCha20Poly1305
import com.teleteh.xplayer2.util.crypto.Hex
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-implementation known-answer tests for the §2.18 control-channel envelope.
 *
 * `src/test/resources/pclink_envelope_vectors.json` is a verbatim copy of
 * `xplayer-link-server/crates/xpl-proto/test-vectors/envelope_vectors.json` — literally the same
 * bytes the Rust `envelope_kat` example and the Swift suite consume, so no implementation can drift
 * without the others noticing. Same arrangement as [PcLinkPairingCryptoTest] and the pairing
 * vectors it drives.
 *
 * Four sections, and the last is the one implementers get wrong:
 *
 * * `keySchedule` — the HKDF derivation of both direction keys (§2.18.3).
 * * `authV2` — the negotiation-bound auth proofs (§2.18.2).
 * * `seal` — the exact nonce, ciphertext and envelope line a sender must produce (§2.18.4).
 * * `sessions` — sequences a *receiver* must refuse: a replayed counter, a gap, a step backwards, a
 *   tampered tag, the other direction's key, another pairing's key, uppercase hex, a nested
 *   envelope, and plaintext spliced into an engaged session. Every one of them is terminal
 *   (§2.18.6), which is asserted here as well as the failure code — a receiver that named the right
 *   tripwire and then carried on reading has resynchronized past the attack.
 */
class PcLinkEnvelopeVectorsTest {

    private val doc: JSONObject by lazy {
        val text = javaClass.classLoader!!
            .getResourceAsStream("pclink_envelope_vectors.json")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }
        JSONObject(text)
    }

    private fun section(name: String): List<JSONObject> {
        val array: JSONArray = doc.getJSONArray(name)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private val keySchedules: List<JSONObject> get() = section("keySchedule")

    /** The derived keys of a named `keySchedule` entry. */
    private fun keysNamed(name: String): PcLinkSessionKeys {
        val v = keySchedules.single { it.getString("name") == name }
        return PcLinkSessionKeys.derive(
            ltk = Hex.decode(v.getString("ltk"), 32)!!,
            nonceC = Hex.decode(v.getString("nonceC"), 16)!!,
            nonceS = Hex.decode(v.getString("nonceS"), 16)!!
        )
    }

    private fun keyFor(schedule: String, direction: String): ByteArray {
        val keys = keysNamed(schedule)
        return when (direction) {
            "c2s" -> keys.c2s
            "s2c" -> keys.s2c
            else -> error("unknown direction $direction")
        }
    }

    @Test
    fun fixtureCarriesEveryVectorTheReferenceDoes() {
        // Named rather than counted alone: a fixture silently trimmed to the easy half would still
        // pass a count, and the refusals are the half that matters.
        assertEquals(
            listOf("auth path", "pairing path", "other pairing"),
            keySchedules.map { it.getString("name") }
        )
        assertEquals(2, section("authV2").size)
        assertEquals(6, section("seal").size)
        assertEquals(
            listOf(
                "ordinary session",
                "server direction decrypts with kS2c",
                "a replayed envelope fails on the counter",
                "a gap is a deletion",
                "a step backwards after progress",
                "a tampered tag fails to open",
                "the other direction's key does not open",
                "another pairing's key does not open",
                "uppercase hex is not canonical",
                "json field order is free",
                "an envelope inside an envelope",
                "plaintext after engagement"
            ),
            section("sessions").map { it.getString("name") }
        )
    }

    // ---- §2.18.3 key schedule --------------------------------------------------------------------

    @Test
    fun keyScheduleDerivesBothDirectionKeys() {
        for (v in keySchedules) {
            val name = v.getString("name")
            val keys = keysNamed(name)
            assertEquals("$name / kC2s", v.getString("kC2s"), Hex.encode(keys.c2s))
            assertEquals("$name / kS2c", v.getString("kS2c"), Hex.encode(keys.s2c))
        }
    }

    /**
     * The pairing path and the auth path share one LTK in the fixture and differ only in which pair
     * of handshake nonces feeds the salt — which is exactly the claim §2.18.3 makes, and the reason
     * a freshly-paired session is encrypted without waiting for its first reconnect.
     */
    @Test
    fun thePairingAndAuthPathsShareAnLtkAndStillDeriveDifferentKeys() {
        val authPath = keySchedules.single { it.getString("name") == "auth path" }
        val pairingPath = keySchedules.single { it.getString("name") == "pairing path" }
        assertEquals(authPath.getString("ltk"), pairingPath.getString("ltk"))
        assertTrue(
            "one LTK, two sessions, four distinct keys",
            setOf(
                authPath.getString("kC2s"), authPath.getString("kS2c"),
                pairingPath.getString("kC2s"), pairingPath.getString("kS2c")
            ).size == 4
        )
    }

    // ---- §2.18.2 negotiation-bound proofs --------------------------------------------------------

    @Test
    fun v2AuthProofsBindTheOfferAndTheSelection() {
        for (v in section("authV2")) {
            val name = v.getString("name")
            val ltk = Hex.decode(v.getString("ltk"), 32)!!
            val nonceC = Hex.decode(v.getString("nonceC"), 16)!!
            val nonceS = Hex.decode(v.getString("nonceS"), 16)!!
            val offered = v.getInt("offered")
            val selected = v.getInt("selected")
            for (role in listOf(PeerRole.SERVER, PeerRole.CLIENT)) {
                val field = if (role == PeerRole.SERVER) "proofServer" else "proofClient"
                assertEquals(
                    "$name / $field",
                    v.getString(field),
                    Hex.encode(
                        PcLinkPairingCrypto.authProofV2(ltk, role, nonceC, nonceS, offered, selected)
                    )
                )
                // The one entry point the FSM uses must land on the same bytes at a selection of 2.
                assertEquals(
                    "$name / $field via negotiatedAuthProof",
                    v.getString(field),
                    Hex.encode(
                        PcLinkPairingCrypto.negotiatedAuthProof(
                            ltk, role, nonceC, nonceS, offered, selected
                        )
                    )
                )
            }
        }
    }

    /**
     * The two `authV2` vectors differ only in `offered` (2 vs 3) and have different proofs — which
     * is the whole downgrade argument of §2.18.2: rewrite the offer in flight and the two ends MAC
     * different bytes, so the exchange dies on a proof instead of quietly running plaintext.
     */
    @Test
    fun rewritingTheOfferChangesTheProof() {
        val (twoTwo, threeTwo) = section("authV2")
        assertEquals(2, twoTwo.getInt("offered"))
        assertEquals(3, threeTwo.getInt("offered"))
        assertEquals(twoTwo.getString("ltk"), threeTwo.getString("ltk"))
        assertEquals(twoTwo.getString("nonceC"), threeTwo.getString("nonceC"))
        assertTrue(twoTwo.getString("proofClient") != threeTwo.getString("proofClient"))
        assertTrue(twoTwo.getString("proofServer") != threeTwo.getString("proofServer"))
    }

    /** A selection of 1 keeps §2.12's proofs byte for byte — that is what a v1 PC still verifies. */
    @Test
    fun aSelectionOfOneFallsBackToTheV1Proof() {
        val v = section("authV2").first()
        val ltk = Hex.decode(v.getString("ltk"), 32)!!
        val nonceC = Hex.decode(v.getString("nonceC"), 16)!!
        val nonceS = Hex.decode(v.getString("nonceS"), 16)!!
        for (role in listOf(PeerRole.SERVER, PeerRole.CLIENT)) {
            assertEquals(
                Hex.encode(PcLinkPairingCrypto.authProof(ltk, role, nonceC, nonceS)),
                Hex.encode(
                    PcLinkPairingCrypto.negotiatedAuthProof(
                        ltk, role, nonceC, nonceS, offered = 2, selected = 1
                    )
                )
            )
        }
    }

    // ---- §2.18.4 sealing -------------------------------------------------------------------------

    @Test
    fun sealProducesTheExactNonceCiphertextAndLine() {
        for (v in section("seal")) {
            val name = v.getString("name")
            val n = v.getLong("n")
            val key = keyFor(v.getString("keySchedule"), v.getString("direction"))
            val nonce = PcLinkEnvelope.nonceFor(n)
            assertEquals("$name / nonce", v.getString("nonce"), Hex.encode(nonce))

            val sealed = ChaCha20Poly1305.seal(key, nonce, v.getString("plaintext").toByteArray(Charsets.UTF_8))
            assertEquals("$name / ciphertext", v.getString("ciphertext"), Hex.encode(sealed))
            assertEquals("$name / envelope", v.getString("envelope"), PcLinkEnvelope.line(n, sealed))

            // And it opens again under the same key, which is what the receiving half will do.
            val opened = ChaCha20Poly1305.open(key, nonce, sealed)
            assertNotNull("$name / round trip", opened)
            assertEquals("$name / round trip", v.getString("plaintext"), opened!!.toString(Charsets.UTF_8))
        }
    }

    /**
     * The same six vectors again, but through [PcLinkSealer] — so the counter this client actually
     * puts on the wire is pinned, not just the cipher underneath it. `counter 256 pins the byte
     * order` is the one that needs the seam: no test is going to seal 256 envelopes to reach it.
     */
    @Test
    fun theSealerCountsAndFormatsTheSameWay() {
        for (v in section("seal")) {
            val name = v.getString("name")
            val n = v.getLong("n")
            val sealer = PcLinkSealer(keyFor(v.getString("keySchedule"), v.getString("direction")))
            sealer.fastForwardTo(n)
            assertEquals(n, sealer.nextCounter)
            assertEquals("$name / envelope", v.getString("envelope"), sealer.seal(v.getString("plaintext")))
            assertEquals("$name / counter advances", n + 1, sealer.nextCounter)
        }
    }

    /** The first two client envelopes in order, from one sealer, with no help from the fixture's `n`. */
    @Test
    fun aSealerNumbersItsEnvelopesFromZero() {
        val vectors = section("seal").filter { it.getString("direction") == "c2s" && it.getLong("n") < 2 }
        val sealer = PcLinkSealer(keyFor("auth path", "c2s"))
        for (v in vectors.sortedBy { it.getLong("n") }) {
            assertEquals(v.getString("name"), v.getString("envelope"), sealer.seal(v.getString("plaintext")))
        }
        assertEquals(2L, sealer.nextCounter)
    }

    // ---- §2.18.5 / §2.18.6 receiving --------------------------------------------------------------

    /**
     * Every `sessions` vector, driven through the real [PcLinkSessionLink] — the same type the two
     * transports use, so this is the client's actual receive path and not a parallel one written for
     * the fixture.
     *
     * `direction` names the direction being *received*: `c2s` means the checker opens with the c2s
     * key, which is what a server does, so the link is built with that role.
     */
    @Test
    fun sessionsAcceptWhatTheyMustAndDieOnWhatTheyMustNot() {
        for (session in section("sessions")) {
            val name = session.getString("name")
            val direction = session.getString("direction")
            val link = PcLinkSessionLink(if (direction == "c2s") PeerRole.SERVER else PeerRole.CLIENT)
            link.engage(keysNamed(session.getString("keySchedule")))

            val steps = session.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val tag = "$name step $i"
                val line = step.getString("line")
                when (val expect = step.getString("expect")) {
                    "ok" -> {
                        assertNull("$tag: the link must still be alive", link.failure)
                        assertEquals(tag, step.getString("plaintext"), link.accept(line))
                    }

                    "error" -> {
                        val failure = failureOf(tag) { link.accept(line) }
                        assertEquals("$tag: wrong tripwire", step.getString("error"), failure.code)

                        // §2.18.6: "after a failed envelope there is no next line". The fixture puts
                        // every error last, so this is where the finality is checked — and it is
                        // checked on *both* halves, because a caller that ignored the exception
                        // must not be able to keep talking either.
                        assertEquals(failure.code, link.failure?.code)
                        val onRead = failureOf("$tag: reading on") { link.accept("""{"type":"ping","t_us":1}""") }
                        assertEquals("$tag: the failure must stick", failure.code, onRead.code)
                        val onSend = failureOf("$tag: sending on") { link.seal("""{"type":"idr"}""") }
                        assertEquals("$tag: a dead link may not send", failure.code, onSend.code)
                    }

                    else -> error("$tag: unknown expectation $expect")
                }
            }
        }
    }

    /**
     * The counter is checked *before* decryption (§2.18.5), which is not observable from the codes
     * alone — a replayed envelope's ciphertext authenticates perfectly, so an implementation that
     * decrypted first would still reach `counter` here. What proves the order is that a replay whose
     * ciphertext is *also* tampered with is still reported as a counter failure.
     */
    @Test
    fun theCounterIsCheckedBeforeTheCipher() {
        val replay = section("sessions").single { it.getString("name") == "a replayed envelope fails on the counter" }
        val link = PcLinkSessionLink(PeerRole.SERVER)
        link.engage(keysNamed(replay.getString("keySchedule")))
        val first = replay.getJSONArray("steps").getJSONObject(0)
        link.accept(first.getString("line"))

        val tampered = JSONObject(first.getString("line")).let { obj ->
            val c = obj.getString("c")
            obj.put("c", c.dropLast(1) + if (c.last() == 'a') 'b' else 'a').toString()
        }
        assertEquals("counter", failureOf("tampered replay") { link.accept(tampered) }.code)
    }

    private inline fun failureOf(tag: String, body: () -> Unit): PcLinkLinkFailure = try {
        body()
        error("$tag: expected the link to refuse this line")
    } catch (e: PcLinkLinkException) {
        e.failure
    }
}
