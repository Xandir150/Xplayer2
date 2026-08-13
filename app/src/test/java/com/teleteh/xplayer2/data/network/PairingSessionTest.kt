package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State-machine tests for [PairingSession], the phone-side client FSM.
 *
 * The peer is [ScriptedServer], a plain-Kotlin stand-in that runs the *real* derivations from
 * [PcLinkPairingCrypto] (already pinned to the cross-language vectors by
 * [PcLinkPairingCryptoTest]). So these are end-to-end ceremony tests, not mock theatre: when the
 * happy path asserts that both sides show the same six digits, those digits came out of two
 * independent transcript computations.
 *
 * Adversarial cases from the design's §14.4 are here too — tampered reveal, forged confirmation
 * tag, reflected proof, replayed proof, out-of-order messages, every timeout — because they are the
 * cases a real PC will never produce on demand.
 */
class PairingSessionTest {

    private var now = 0L
    private val clock: () -> Long = { now }

    /** Deterministic client nonce, so a failure prints the same bytes every run. */
    private val fixedRandom: (Int) -> ByteArray = { n -> ByteArray(n) { i -> (0xa0 + i).toByte() } }

    private val clientIdentity = PcLinkPairingCrypto.identityFromPrivateKey(
        Hex.decode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!!
    )!!

    private fun pairingSession(clientName: String = "Pixel 9 Pro") =
        PairingSession.pair(clientIdentity, clientName, clock = clock, random = fixedRandom)

    private fun authSession(candidates: List<PcLinkPairing>) =
        PairingSession.authenticate(clientIdentity, candidates, clock = clock, random = fixedRandom)

    /**
     * Drives a session through `pair_start` and both reveals, feeding each side the other's, and
     * leaves it in [PairingSession.State.AWAITING_USER] with the server's keys derived. Returns the
     * effects of the server's reveal.
     */
    private fun revealBoth(session: PairingSession, server: ScriptedServer): List<PairingEffect> {
        val pairStart = JSONObject(session.start().sends().single())
        val effects = session.onLine(server.onPairStart(pairStart))
        server.onClientReveal(JSONObject(effects.sends().single()))
        return effects
    }

    // ---- happy paths ---------------------------------------------------------------------------

    @Test
    fun freshPairingCompletesAndBothSidesShowTheSameCode() {
        val server = ScriptedServer()
        val session = pairingSession()

        val start = session.start()
        val pairStart = JSONObject(start.sends().single())
        assertEquals("pair_start", pairStart.getString("type"))
        assertEquals(1, pairStart.getInt("pairingVersion"))
        assertEquals("Pixel 9 Pro", pairStart.getString("clientName"))
        assertEquals(PairingSession.State.COMMIT_SENT, session.state)

        // Server reveals first, knowing only an opaque commitment.
        val afterReveal = session.onLine(server.onPairStart(pairStart))
        val clientReveal = JSONObject(afterReveal.sends().single())
        assertEquals("pair_pubkey", clientReveal.getString("type"))
        assertEquals("client", clientReveal.getString("role"))

        val shown = afterReveal.filterIsInstance<PairingEffect.ShowSas>().single()
        server.onClientReveal(clientReveal)
        assertEquals("both sides must derive the same code", server.sas, shown.sas)
        assertEquals("Living Room PC", shown.serverName)
        assertEquals(server.identity.fingerprint, shown.serverId)
        assertEquals(PairingSession.State.AWAITING_USER, session.state)

        // User compares the digits and taps Pair.
        val confirm = JSONObject(session.onUserAccept().sends().single())
        assertEquals("pair_confirm", confirm.getString("type"))
        assertEquals("client", confirm.getString("role"))
        assertTrue(server.verifyClientConfirm(confirm))

        val afterServerConfirm = session.onLine(server.pairConfirm())
        val persist = afterServerConfirm.filterIsInstance<PairingEffect.Persist>().single()
        assertEquals(server.identity.fingerprint, persist.serverId)
        assertEquals("Living Room PC", persist.serverName)
        assertArrayEquals(server.keys!!.ltk, persist.ltk)
        assertEquals(PairingSession.State.AWAITING_AUTH_OK, session.state)

        val done = session.onLine(server.authOk())
        val success = done.finishedSuccess()
        assertEquals(server.identity.fingerprint, success.serverId)
        assertEquals("Living Room PC", success.serverName)
        assertEquals(server.videoToken, success.videoToken)
        assertTrue(success.paired)
        assertEquals(PairingSession.State.DONE, session.state)
        // Success must not close the connection: it's the authenticated session the caller wanted.
        assertFalse(done.any { it is PairingEffect.Close })
    }

    @Test
    fun reconnectAuthenticatesSilently() {
        val server = ScriptedServer()
        val pairing = server.asStoredPairing()
        val session = authSession(listOf(pairing))

        val challenge = JSONObject(session.start().sends().single())
        assertEquals("auth_challenge", challenge.getString("type"))
        assertEquals(clientIdentity.fingerprint, challenge.getString("clientId"))
        assertEquals(1, challenge.getInt("pairingVersion"))
        assertEquals(PairingSession.State.CHALLENGE_SENT, session.state)

        val response = JSONObject(session.onLine(server.authResponse(challenge)).sends().single())
        assertEquals("auth_response", response.getString("type"))
        assertFalse("only the server's response carries a nonce", response.has("nonce"))
        assertTrue(server.verifyClientProof(response))
        assertEquals(PairingSession.State.RESPONSE_SENT, session.state)

        val success = session.onLine(server.authOk()).finishedSuccess()
        assertEquals(server.identity.fingerprint, success.serverId)
        assertEquals(server.videoToken, success.videoToken)
        assertFalse("reconnect stores nothing new", success.paired)
    }

    /** Address changed since we paired, so the right key isn't the first guess. */
    @Test
    fun authTriesEveryStoredKeyAndIdentifiesTheRightPc() {
        val server = ScriptedServer()
        val decoy = PcLinkPairing("d".repeat(64), "Other PC", ByteArray(32) { 0x77 }, "", "")
        val session = authSession(listOf(decoy, server.asStoredPairing()))

        val challenge = JSONObject(session.start().sends().single())
        assertTrue(session.onLine(server.authResponse(challenge)).sends().isNotEmpty())
        val success = session.onLine(server.authOk()).finishedSuccess()
        assertEquals(server.identity.fingerprint, success.serverId)
        assertEquals("Living Room PC", success.serverName)
    }

    // ---- user and peer refusals ----------------------------------------------------------------

    @Test
    fun decliningSendsPairRejectAndStoresNothing() {
        val server = ScriptedServer()
        val session = pairingSession()
        revealBoth(session, server)

        val effects = session.onUserDecline()
        assertEquals("declined", JSONObject(effects.sends().single()).getString("reason"))
        assertEquals(PairingFailure.DECLINED_LOCALLY, effects.finishedFailure())
        assertNoPersist(effects)
        assertTrue(effects.any { it is PairingEffect.Close })
        // Nothing further happens on a dead session, whatever the peer says next.
        assertEquals(emptyList<PairingEffect>(), session.onLine(server.pairConfirm()))
        assertEquals(emptyList<PairingEffect>(), session.onUserAccept())
    }

    @Test
    fun pairRejectReasonsMapToDistinctFailures() {
        val cases = mapOf(
            "declined" to PairingFailure.DECLINED_BY_PC,
            "busy" to PairingFailure.PC_BUSY,
            "timeout" to PairingFailure.TIMEOUT,
            "rate_limited" to PairingFailure.RATE_LIMITED,
            "version" to PairingFailure.VERSION_UNSUPPORTED,
            "confirm_mismatch" to PairingFailure.CONFIRM_MISMATCH,
            "commitment_mismatch" to PairingFailure.CONFIRM_MISMATCH,
            "protocol" to PairingFailure.PROTOCOL,
            "something_from_the_future" to PairingFailure.PROTOCOL
        )
        for ((reason, expected) in cases) {
            val session = pairingSession()
            session.start()
            val effects = session.onLine("""{"type":"pair_reject","reason":"$reason"}""")
            assertEquals(reason, expected, effects.finishedFailure())
            assertNoPersist(effects)
        }
    }

    @Test
    fun authFailDistinguishesForgottenPairingFromABadProof() {
        val server = ScriptedServer()
        fun failureFor(reason: String): PairingFailure {
            val session = authSession(listOf(server.asStoredPairing()))
            session.start()
            return session.onLine("""{"type":"auth_fail","reason":"$reason"}""").finishedFailure()
        }
        // §8.4: only "unknown_client" may lead to a (user-gated) re-pair offer.
        assertEquals(PairingFailure.UNKNOWN_TO_PC, failureFor("unknown_client"))
        assertEquals(PairingFailure.AUTH_FAILED, failureFor("bad_proof"))
        assertEquals(PairingFailure.RATE_LIMITED, failureFor("rate_limited"))
        // auth_fail carries no "version" reason, so a server that doesn't speak our pairingVersion
        // says "protocol" — which must not read as "someone is interfering with your network".
        assertEquals(PairingFailure.PROTOCOL, failureFor("protocol"))
        // Anything we don't recognise stays in the loud bucket, and still offers no re-pair.
        assertEquals(PairingFailure.AUTH_FAILED, failureFor("who_knows"))
    }

    @Test
    fun authenticatingWithNothingStoredFailsImmediately() {
        val session = authSession(emptyList())
        val effects = session.start()
        assertEquals(PairingFailure.UNKNOWN_TO_PC, effects.finishedFailure())
        assertTrue(effects.sends().isEmpty())
    }

    // ---- adversarial ---------------------------------------------------------------------------

    /**
     * §14.4: a MITM that swaps the server's reveal gets a different code on the phone than the one
     * the real PC shows (the human catches it), and if it then forwards the real PC's confirmation
     * tag the crypto catches it too — before anything is stored.
     */
    @Test
    fun tamperedServerKeyChangesTheCodeAndFailsKeyConfirmation() {
        val honest = ScriptedServer()
        val session = pairingSession()
        val pairStart = JSONObject(session.start().sends().single())

        val honestReveal = JSONObject(honest.onPairStart(pairStart))
        val tampered = JSONObject(honestReveal.toString()).apply {
            val mitmKey = PcLinkPairingCrypto.generateIdentity().publicKey
            put("pubkey", Hex.encode(mitmKey))
        }

        val tamperedEffects = session.onLine(tampered.toString())
        val shown = tamperedEffects.filterIsInstance<PairingEffect.ShowSas>().single()
        // The reported fingerprint follows the key actually revealed, never the PC we thought we
        // were reaching. Nothing in this FSM can be fed a hint today, so this exists to give a
        // future refactor that threads one through something to break: substituting a hint for the
        // authoritative id is what makes a re-paired PC store under one id and be looked up under
        // another (see PairingOutcome.Success).
        assertEquals(
            PcLinkPairingCrypto.fingerprintOf(Hex.decode(tampered.getString("pubkey"))!!),
            shown.serverId
        )
        assertNotEquals(honest.identity.fingerprint, shown.serverId)
        // The MITM forwards the phone's (unchanged) reveal to the real PC, which computes its own
        // code from the key it actually sent.
        honest.onClientReveal(JSONObject(tamperedEffects.sends().single()))
        assertNotEquals("the phone's code must differ from the PC's", honest.sas, shown.sas)

        // The user doesn't notice and taps Pair; the MITM forwards the real PC's tag.
        session.onUserAccept()
        val effects = session.onLine(honest.pairConfirm())
        assertEquals(PairingFailure.CONFIRM_MISMATCH, effects.finishedFailure())
        assertEquals("confirm_mismatch", JSONObject(effects.sends().single()).getString("reason"))
        assertNoPersist(effects)
    }

    /**
     * §4.5: the phone's Pair button is not cosmetic — it is what stops a **hostile PC** pairing
     * itself to this phone.
     *
     * Note what does *not* save us in that scenario: the two codes match, legitimately, because
     * there is no man in the middle to make them differ. A directly-connected attacker shares our
     * transcript and derives our SAS. The code is the anti-MITM gate; the button is the
     * anti-stranger gate, and only the button applies here.
     *
     * So a PC that runs a flawless ceremony and then sends a *cryptographically valid*
     * confirmation tag must still get nothing until the user taps — the tag being genuine is
     * exactly why this is worth asserting, since it means the state check is the only thing
     * standing between that PC and a stored pairing. This is the assertion most likely to be
     * streamlined away for a smoother demo, which is why it says so out loud.
     * (Mirrors `xpl-pairing`'s `a_server_never_pairs_without_its_own_users_accept`.)
     */
    @Test
    fun nothingIsPairedWithoutTheUsersTap() {
        val server = ScriptedServer()
        val session = pairingSession()
        val revealed = revealBoth(session, server)

        // The code is on screen and our key is revealed — but our confirmation tag is not.
        assertTrue(revealed.any { it is PairingEffect.ShowSas })
        assertEquals(PairingSession.State.AWAITING_USER, session.state)
        assertTrue(
            "the confirmation tag must not go out ahead of the user's tap",
            revealed.sends().none { JSONObject(it).optString("type") == "pair_confirm" }
        )

        // A genuine tag from a PC that did everything right, arriving one tap too early.
        val genuineTag = server.pairConfirm()
        val effects = session.onLine(genuineTag)
        assertEquals(PairingFailure.PROTOCOL, effects.finishedFailure())
        assertNoPersist(effects)
        assertTrue(
            "a valid tag must not extract our own",
            effects.sends().none { JSONObject(it).optString("type") == "pair_confirm" }
        )
        assertTrue(effects.any { it is PairingEffect.Close })

        // Prove those bytes really were a tag that verifies. The state check above runs *before*
        // the tag is ever examined, so without this the test would pass exactly as happily on junk
        // — and would quietly rot into asserting nothing the day the scripted PC stopped deriving
        // real keys. Same bytes (the fixtures are deterministic, so the transcript is identical),
        // delivered to a session whose user did tap: it must pair.
        now = 0
        val tapped = pairingSession()
        revealBoth(tapped, ScriptedServer())
        tapped.onUserAccept()
        assertTrue(
            "the refused tag must be one a tapped session accepts",
            tapped.onLine(genuineTag).any { it is PairingEffect.Persist }
        )

        // And ignoring the sheet expires the ceremony rather than lapsing into a pairing.
        now = 0
        val ignored = pairingSession()
        revealBoth(ignored, ScriptedServer())
        now += PairingSession.CONFIRM_TIMEOUT_MS
        val expired = ignored.onTick()
        assertEquals(PairingFailure.TIMEOUT, expired.finishedFailure())
        assertNoPersist(expired)
    }

    @Test
    fun forgedConfirmationTagIsRejected() {
        val server = ScriptedServer()
        val session = pairingSession()
        session.onLine(server.onPairStart(JSONObject(session.start().sends().single())))
        session.onUserAccept()

        val forged = JSONObject()
            .put("type", "pair_confirm").put("role", "server")
            .put("confirm", Hex.encode(ByteArray(32) { 0x5a })).toString()
        val effects = session.onLine(forged)
        assertEquals(PairingFailure.CONFIRM_MISMATCH, effects.finishedFailure())
        assertNoPersist(effects)
    }

    /** A "server" that can't prove it holds our key never gets a proof out of us. */
    @Test
    fun badServerProofAbortsBeforeWeRevealOurOwn() {
        val server = ScriptedServer()
        val session = authSession(listOf(server.asStoredPairing()))
        val challenge = JSONObject(session.start().sends().single())

        val forged = JSONObject(server.authResponse(challenge))
            .put("proof", Hex.encode(ByteArray(32) { 0x11 })).toString()
        val effects = session.onLine(forged)
        assertEquals(PairingFailure.AUTH_FAILED, effects.finishedFailure())
        assertTrue("must not send proofC to an unverified peer", effects.sends().isEmpty())
        assertTrue(effects.any { it is PairingEffect.Close })
    }

    /**
     * §2.12: the server's `auth_response` must carry a usable 16-byte nonce, and a client that
     * proceeded without one — assuming zeroes, say — would be fixing half the input of *both*
     * proofs for an attacker, which is exactly what the freshness requirement exists to prevent.
     * So a missing or malformed nonce is a protocol abort, not something to paper over.
     *
     * Distinct from the neighbouring test: a well-formed proof that simply doesn't verify is
     * [PairingFailure.AUTH_FAILED] (an impostor), while a field we can't even parse is
     * [PairingFailure.PROTOCOL] (a broken peer). Neither reveals our own proof.
     */
    @Test
    fun aServerResponseWithoutAUsableNonceIsRefused() {
        val server = ScriptedServer()
        fun responseWith(field: String, value: String?): List<PairingEffect> {
            val session = authSession(listOf(server.asStoredPairing()))
            val response = JSONObject(server.authResponse(JSONObject(session.start().sends().single())))
                .apply { if (value == null) remove(field) else put(field, value) }
            return session.onLine(response.toString())
        }

        val cases = listOf(
            "nonce" to null,                        // absent entirely
            "nonce" to "",                          // present but empty
            "nonce" to "00".repeat(15),             // 15 bytes, not 16
            "nonce" to "00".repeat(17),             // 17 bytes
            "nonce" to "202122232425262728292A2B2C2D2E2F", // uppercase is not the wire format
            "nonce" to "zzzz22232425262728292a2b2c2d2e2f", // not hex at all
            "proof" to null,
            "proof" to "00".repeat(31),           // 31 bytes, not 32
            "proof" to "00".repeat(33),           // 33 bytes
            "proof" to "zz".repeat(32)            // not hex at all
        )
        for ((field, value) in cases) {
            val effects = responseWith(field, value)
            assertEquals("$field=$value", PairingFailure.PROTOCOL, effects.finishedFailure())
            assertTrue("$field=$value must not reveal proofC", effects.sends().isEmpty())
            assertTrue("$field=$value must close", effects.any { it is PairingEffect.Close })
        }
    }

    /** §5: the direction labels are what stop a proof being bounced back at its own author. */
    @Test
    fun reflectedClientProofIsRejected() {
        val server = ScriptedServer()
        val session = authSession(listOf(server.asStoredPairing()))
        val challenge = JSONObject(session.start().sends().single())

        val nonceS = ByteArray(16) { 0x30 }
        val reflected = JSONObject()
            .put("type", "auth_response")
            .put("nonce", Hex.encode(nonceS))
            .put(
                "proof",
                Hex.encode(
                    PcLinkPairingCrypto.authProof(
                        server.ltk, PeerRole.CLIENT,
                        Hex.decode(challenge.getString("nonce"))!!, nonceS
                    )
                )
            ).toString()
        assertEquals(PairingFailure.AUTH_FAILED, session.onLine(reflected).finishedFailure())
    }

    /** §5: a proof recorded off an earlier connection is dead once its nonces are. */
    @Test
    fun replayedServerProofFromAnEarlierSessionIsRejected() {
        val server = ScriptedServer()
        val recorded = authSession(listOf(server.asStoredPairing())).let { first ->
            val challenge = JSONObject(first.start().sends().single())
            server.authResponse(challenge)
        }

        // A fresh connection means a fresh client nonce, so the captured proof no longer applies.
        val session = PairingSession.authenticate(
            clientIdentity, listOf(server.asStoredPairing()), clock = clock,
            random = { n -> ByteArray(n) { 0x5e } }
        )
        session.start()
        assertEquals(PairingFailure.AUTH_FAILED, session.onLine(recorded).finishedFailure())
    }

    /** §4.1: a low-order key would let an attacker force the shared secret to a known value. */
    @Test
    fun lowOrderServerKeyAbortsTheCeremony() {
        val server = ScriptedServer()
        val session = pairingSession()
        val reveal = JSONObject(server.onPairStart(JSONObject(session.start().sends().single())))
            .put("pubkey", "0".repeat(64)).toString()
        val effects = session.onLine(reveal)
        assertEquals(PairingFailure.PROTOCOL, effects.finishedFailure())
        assertNoPersist(effects)
    }

    // ---- ordering and framing ------------------------------------------------------------------

    @Test
    fun pairingMessagesOutOfOrderAreRejected() {
        val server = ScriptedServer()

        // pair_confirm before either reveal (well-formed, just far too early).
        val earlyConfirm = JSONObject()
            .put("type", "pair_confirm").put("role", "server")
            .put("confirm", Hex.encode(ByteArray(32))).toString()
        pairingSession().let { session ->
            session.start()
            assertEquals(PairingFailure.PROTOCOL, session.onLine(earlyConfirm).finishedFailure())
        }
        // A second server reveal after the first.
        pairingSession().let { session ->
            val reveal = server.onPairStart(JSONObject(session.start().sends().single()))
            session.onLine(reveal)
            assertEquals(PairingFailure.PROTOCOL, session.onLine(reveal).finishedFailure())
        }
        // auth_ok before anything has been agreed.
        pairingSession().let { session ->
            session.start()
            assertEquals(PairingFailure.PROTOCOL, session.onLine(server.authOk()).finishedFailure())
        }
        // A server "reveal" claiming the client role.
        pairingSession().let { session ->
            val reveal = JSONObject(server.onPairStart(JSONObject(session.start().sends().single())))
                .put("role", "client").toString()
            assertEquals(PairingFailure.PROTOCOL, session.onLine(reveal).finishedFailure())
        }
        // A second auth_response on one connection.
        authSession(listOf(server.asStoredPairing())).let { session ->
            val response = server.authResponse(JSONObject(session.start().sends().single()))
            session.onLine(response)
            assertEquals(PairingFailure.PROTOCOL, session.onLine(response).finishedFailure())
        }
    }

    @Test
    fun malformedHexFieldsAreRejected() {
        val server = ScriptedServer()
        fun revealWith(field: String, value: String): PairingFailure {
            val session = pairingSession()
            val reveal = JSONObject(server.onPairStart(JSONObject(session.start().sends().single())))
                .put(field, value).toString()
            return session.onLine(reveal).finishedFailure()
        }
        // Uppercase is not the wire format, per protocol.md §2.6.
        assertEquals(PairingFailure.PROTOCOL, revealWith("pubkey", Hex.encode(server.identity.publicKey).uppercase()))
        assertEquals(PairingFailure.PROTOCOL, revealWith("pubkey", "abcd"))
        assertEquals(PairingFailure.PROTOCOL, revealWith("pubkey", "zz".repeat(32)))
        assertEquals(PairingFailure.PROTOCOL, revealWith("nonce", "00".repeat(15)))
        assertEquals(PairingFailure.PROTOCOL, revealWith("nonce", ""))
        assertEquals(PairingFailure.PROTOCOL, revealWith("name", ""))

        val session = pairingSession()
        session.start()
        assertEquals(PairingFailure.PROTOCOL, session.onLine("not json at all").finishedFailure())
    }

    /** protocol.md's forward-compatibility rule: unknown types are ignored, not fatal. */
    @Test
    fun unrelatedMessagesAreIgnoredWithoutDisturbingTheSession() {
        val session = pairingSession()
        val pairStart = JSONObject(session.start().sends().single())
        for (line in listOf(
            """{"type":"ping","t_us":12}""",
            """{"type":"pong","t_us":12}""",
            """{"type":"windows","windows":[]}""",
            """{"type":"something_from_2027","x":1}""",
            """{"type":"config","mime":"video/hevc"}"""
        )) {
            assertEquals(line, emptyList<PairingEffect>(), session.onLine(line))
        }
        assertEquals(PairingSession.State.COMMIT_SENT, session.state)

        // …and the ceremony still completes afterwards.
        val server = ScriptedServer()
        session.onLine(server.onPairStart(pairStart))
        assertEquals(PairingSession.State.AWAITING_USER, session.state)
    }

    @Test
    fun malformedVideoTokenStillAuthenticates() {
        val server = ScriptedServer()
        val session = authSession(listOf(server.asStoredPairing()))
        session.onLine(server.authResponse(JSONObject(session.start().sends().single())))
        val success = session.onLine("""{"type":"auth_ok","videoToken":"nope"}""").finishedSuccess()
        assertNull(success.videoToken)
    }

    // ---- timeouts and disconnects --------------------------------------------------------------

    @Test
    fun eachWaitHasItsOwnTimeout() {
        val server = ScriptedServer()

        // Server never reveals: 10 s step timeout.
        pairingSession().let { session ->
            session.start()
            assertEquals(emptyList<PairingEffect>(), session.onTick())
            now += PairingSession.STEP_TIMEOUT_MS - 1
            assertEquals(emptyList<PairingEffect>(), session.onTick())
            now += 1
            val effects = session.onTick()
            assertEquals("timeout", JSONObject(effects.sends().single()).getString("reason"))
            assertEquals(PairingFailure.TIMEOUT, effects.finishedFailure())
        }

        // Nobody presses anything: 90 s humans-comparing-codes window.
        now = 0
        pairingSession().let { session ->
            session.onLine(server.onPairStart(JSONObject(session.start().sends().single())))
            now += PairingSession.STEP_TIMEOUT_MS + 1
            assertEquals("the code sheet gets 90 s, not 10", emptyList<PairingEffect>(), session.onTick())
            now += PairingSession.CONFIRM_TIMEOUT_MS
            assertEquals(PairingFailure.TIMEOUT, session.onTick().finishedFailure())
        }

        // The whole auth exchange: 10 s.
        now = 0
        authSession(listOf(server.asStoredPairing())).let { session ->
            session.start()
            now += PairingSession.AUTH_TIMEOUT_MS
            val effects = session.onTick()
            assertEquals(PairingFailure.TIMEOUT, effects.finishedFailure())
            assertTrue("no pair_reject on the auth path", effects.sends().isEmpty())
        }
    }

    /**
     * The server persists before sending its `pair_confirm`, so once we've verified that tag the
     * pairing is real on both sides. Losing the connection before `auth_ok` costs only the video
     * token — reporting failure here would tell the user to re-pair something already paired.
     */
    @Test
    fun losingTheConnectionAfterPairingStillReportsSuccess() {
        val server = ScriptedServer()
        fun pairedSession(): PairingSession {
            val session = pairingSession()
            revealBoth(session, server)
            session.onUserAccept()
            assertTrue(session.onLine(server.pairConfirm()).any { it is PairingEffect.Persist })
            return session
        }

        pairedSession().let { session ->
            val success = session.onDisconnected().finishedSuccess()
            assertTrue(success.paired)
            assertNull(success.videoToken)
        }
        now = 0
        pairedSession().let { session ->
            now += PairingSession.STEP_TIMEOUT_MS
            val success = session.onTick().finishedSuccess()
            assertTrue(success.paired)
            assertNull(success.videoToken)
        }
    }

    @Test
    fun losingTheConnectionMidCeremonyFails() {
        val session = pairingSession()
        session.start()
        val effects = session.onDisconnected()
        assertEquals(PairingFailure.CONNECTION_LOST, effects.finishedFailure())
        assertNoPersist(effects)
        assertEquals(emptyList<PairingEffect>(), session.onDisconnected())
        assertEquals(emptyList<PairingEffect>(), session.onTick())
    }

    // ---- §2.18 encryption negotiation ------------------------------------------------------------

    /**
     * The offer is one additive field on the opener, and it is on **both** openers — the ceremony's
     * and the reconnect's — because §2.18.3 keys the envelope off whichever handshake ran.
     */
    @Test
    fun bothOpenersCarryTheEncryptionOffer() {
        val pairStart = JSONObject(pairingSession().start().sends().single())
        assertEquals("pair_start", pairStart.getString("type"))
        assertEquals(PcLinkEnvelope.AEAD, pairStart.getInt("encryption"))

        val challenge = JSONObject(
            authSession(listOf(ScriptedServer().asStoredPairing())).start().sends().single()
        )
        assertEquals("auth_challenge", challenge.getString("type"))
        assertEquals(PcLinkEnvelope.AEAD, challenge.getInt("encryption"))
        // Additive means additive: everything a version-1 PC reads is exactly where it was.
        assertEquals(PcLinkPairingCrypto.PAIRING_VERSION, challenge.getInt("pairingVersion"))
        assertEquals(clientIdentity.fingerprint, challenge.getString("clientId"))
    }

    /** A client that cannot do the cipher must say so rather than promise an envelope it can't open. */
    @Test
    fun aClientThatOffersOneLooksExactlyLikeAVersionOneClient() {
        val session = PairingSession.authenticate(
            clientIdentity, listOf(ScriptedServer().asStoredPairing()),
            clock = clock, random = fixedRandom, encryptionOffer = PcLinkEnvelope.PLAINTEXT
        )
        val challenge = JSONObject(session.start().sends().single())
        assertFalse("a 1 is omitted, not sent", challenge.has("encryption"))
    }

    /**
     * The compatibility claim, stated once and directly: a PC that ignores the new field answers
     * exactly as it always did, and the session that results is byte-for-byte the version-1 one —
     * v1 proofs, no envelope, and a happy client.
     */
    @Test
    fun aVersionOnePcStillAuthenticatesInPlaintext() {
        val server = ScriptedServer() // speaks = 1
        val session = authSession(listOf(server.asStoredPairing()))
        val challenge = JSONObject(session.start().sends().single())

        val response = session.onLine(server.authResponse(challenge))
        val proof = JSONObject(response.sends().single())
        assertTrue("the v1 proof is what a v1 PC verifies", server.verifyClientProof(proof))
        assertEquals(
            Hex.encode(
                PcLinkPairingCrypto.authProof(
                    server.ltk, PeerRole.CLIENT,
                    Hex.decode(challenge.getString("nonce"))!!, server.authNonceS()
                )
            ),
            proof.getString("proof")
        )
        assertTrue("nothing to engage", response.none { it is PairingEffect.EngageEncryption })

        val success = session.onLine(server.authOk()).finishedSuccess()
        assertEquals(PcLinkEnvelope.PLAINTEXT, success.encryption)
        assertEquals(server.videoToken, success.videoToken)
        assertNoPersist(session.onLine(server.authOk()))
    }

    @Test
    fun aVersionTwoPcAuthenticatesWithTheV2ProofsAndEngagesAfterOurs() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = authSession(listOf(server.asStoredPairing()))
        val challenge = JSONObject(session.start().sends().single())

        val effects = session.onLine(server.authResponse(challenge))
        assertEquals(2, server.selected)
        assertTrue(server.verifyClientProof(JSONObject(effects.sends().single())))

        // §2.18.4's ordering is the contract: our proof leaves plaintext, and only then do we
        // switch. A batch that engaged first would seal the very proof the PC is waiting to read.
        assertEquals(2, effects.size)
        assertTrue(effects[0] is PairingEffect.Send)
        val engage = effects[1] as PairingEffect.EngageEncryption
        assertEquals(
            "keyed from this connection's own auth nonces",
            server.authSessionKeys(), engage.keys
        )
        assertEquals(PcLinkEnvelope.AEAD, session.encryption)
    }

    @Test
    fun aVersionTwoCeremonyEngagesAfterTheServersConfirmTag() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = pairingSession()
        revealBoth(session, server)
        server.verifyClientConfirm(JSONObject(session.onUserAccept().sends().single()))

        val effects = session.onLine(server.pairConfirm())
        val persist = effects.filterIsInstance<PairingEffect.Persist>().single()
        assertEquals(PcLinkEnvelope.AEAD, persist.encryption)
        assertTrue("a ceremony writes the pairing itself", persist.fresh)

        // Persist first, then engage: the record must be on disk before anything that follows it.
        assertEquals(2, effects.size)
        val engage = effects[1] as PairingEffect.EngageEncryption
        assertEquals(
            "the ceremony's own nonces fill the same slots (§2.18.3)",
            server.pairingSessionKeys(fixedRandom(16)), engage.keys
        )
        // And `auth_ok` — which arrives enveloped, unwrapped by the transport — still completes it.
        val success = session.onLine(server.authOk()).finishedSuccess()
        assertEquals(PcLinkEnvelope.AEAD, success.encryption)
        assertTrue(success.paired)
    }

    /** The transcript is deliberately unchanged across versions, or the two codes wouldn't match. */
    @Test
    fun theNegotiationDoesNotDisturbTheSixDigits() {
        val v1 = ScriptedServer()
        val v2 = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val fromV1 = pairingSession().let { revealBoth(it, v1); v1.sas }
        val fromV2 = pairingSession().let { revealBoth(it, v2); v2.sas }
        assertEquals("a v2 phone must derive the code a v1 PC displays", fromV1, fromV2)
    }

    /** §2.18.1: a selection above our offer is a peer talking past us, and the session is over. */
    @Test
    fun aSelectionAboveOurOfferIsRefused() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD, selectionOverride = 3)
        val session = authSession(listOf(server.asStoredPairing()))
        val effects = session.onLine(server.authResponse(JSONObject(session.start().sends().single())))
        assertEquals(PairingFailure.PROTOCOL, effects.finishedFailure())
        assertTrue("no proof of ours goes out", effects.sends().isEmpty())

        val ceremony = pairingSession()
        val reveal = ScriptedServer(speaks = PcLinkEnvelope.AEAD, selectionOverride = 3)
            .onPairStart(JSONObject(ceremony.start().sends().single()))
        val ceremonyEffects = ceremony.onLine(reveal)
        assertEquals(PairingFailure.PROTOCOL, ceremonyEffects.finishedFailure())
        assertTrue(
            "nothing of ours is revealed",
            ceremonyEffects.sends().none { JSONObject(it).getString("type") == "pair_pubkey" }
        )
    }

    @Test
    fun anUnreadableSelectionIsABrokenPeerRatherThanASilentOne() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = authSession(listOf(server.asStoredPairing()))
        val challenge = JSONObject(session.start().sends().single())
        val mangled = JSONObject(server.authResponse(challenge)).put("encryption", "two").toString()
        assertEquals(PairingFailure.PROTOCOL, session.onLine(mangled).finishedFailure())
    }

    // ---- §2.18.7: the pin ---------------------------------------------------------------------------

    /**
     * The downgrade layer that survives a store restored from a pre-upgrade backup: a pairing that
     * has completed an encrypted session refuses a plaintext one **before verifying anything**,
     * because a correct v1 proof from a pinned PC is precisely what a stripped negotiation looks
     * like.
     */
    @Test
    fun aPinnedPairingRefusesAPlaintextSelection() {
        val server = ScriptedServer() // rolled back, or someone stripped the field in flight
        val session = authSession(listOf(server.asStoredPairing(encryption = PcLinkEnvelope.AEAD)))
        val effects = session.onLine(server.authResponse(JSONObject(session.start().sends().single())))

        assertEquals(PairingFailure.ENCRYPTION_REQUIRED, effects.finishedFailure())
        assertTrue("we must not answer a stripped negotiation", effects.sends().isEmpty())
        assertNoPersist(effects)
    }

    /**
     * With a candidate *list* — discovery can still only guess by address — the pin belongs to the
     * PC that actually answered, not to the guess at the head of the list. An unpinned PC must not
     * be refused because some other stored PC happens to be pinned.
     */
    @Test
    fun thePinFollowsThePairingThatAnsweredNotTheGuess() {
        val answering = ScriptedServer(name = "Study PC")
        val pinnedElsewhere = PcLinkPairing(
            serverId = "f".repeat(64), name = "Living Room PC", ltk = ByteArray(32) { 0x11 },
            createdAt = "2026-08-01T00:00:00Z", lastSeenAt = "2026-08-01T00:00:00Z",
            encryption = PcLinkEnvelope.AEAD
        )
        val session = authSession(listOf(pinnedElsewhere, answering.asStoredPairing()))
        val effects = session.onLine(
            answering.authResponse(JSONObject(session.start().sends().single()))
        )
        assertEquals(
            "the unpinned PC that proved itself may run plaintext",
            1, effects.sends().size
        )
        assertEquals(
            PcLinkEnvelope.PLAINTEXT,
            session.onLine(answering.authOk()).finishedSuccess().encryption
        )
    }

    @Test
    fun aPinnedPairingIsRefusedEvenWhenItIsNotTheOnlyCandidate() {
        val answering = ScriptedServer(name = "Study PC")
        val unpinnedOther = PcLinkPairing(
            serverId = "f".repeat(64), name = "Other PC", ltk = ByteArray(32) { 0x11 },
            createdAt = "2026-08-01T00:00:00Z", lastSeenAt = "2026-08-01T00:00:00Z"
        )
        val session = authSession(
            listOf(unpinnedOther, answering.asStoredPairing(encryption = PcLinkEnvelope.AEAD))
        )
        val effects = session.onLine(
            answering.authResponse(JSONObject(session.start().sends().single()))
        )
        assertEquals(PairingFailure.ENCRYPTION_REQUIRED, effects.finishedFailure())
        assertTrue(effects.sends().isEmpty())
    }

    /**
     * The half the reference forgot. A pairing made against a version-1 PC selected 1 at its
     * ceremony, so `auth_ok` on a later reconnect is the only place its pin can ever be raised —
     * and it raises the pin *only*, never the key.
     */
    @Test
    fun authOkRaisesThePinForAPairingMadeBeforeEncryptionExisted() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val stored = server.asStoredPairing() // encryption = 1, as every pairing today is
        val session = authSession(listOf(stored))
        session.onLine(server.authResponse(JSONObject(session.start().sends().single())))

        val effects = session.onLine(server.authOk())
        val persist = effects.filterIsInstance<PairingEffect.Persist>().single()
        assertEquals(PcLinkEnvelope.AEAD, persist.encryption)
        assertFalse("this must not look like a fresh ceremony", persist.fresh)
        assertEquals(stored.serverId, persist.serverId)
        assertArrayEquals("the key is not rewritten", stored.ltk, persist.ltk)
        // And the pin is written before the session is reported done.
        assertTrue(effects.indexOfFirst { it is PairingEffect.Persist } <
            effects.indexOfFirst { it is PairingEffect.Finished })
    }

    @Test
    fun authOkDoesNotRewriteAPinThatAlreadySaysTwo() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = authSession(listOf(server.asStoredPairing(encryption = PcLinkEnvelope.AEAD)))
        session.onLine(server.authResponse(JSONObject(session.start().sends().single())))
        assertNoPersist(session.onLine(server.authOk()))
    }

    /** A plaintext session never writes a pin: it has nothing to record and would lower one. */
    @Test
    fun aPlaintextSessionWritesNoPin() {
        val server = ScriptedServer()
        val session = authSession(listOf(server.asStoredPairing()))
        session.onLine(server.authResponse(JSONObject(session.start().sends().single())))
        assertNoPersist(session.onLine(server.authOk()))
    }

    // ---- §2.18 refusals on the wire ------------------------------------------------------------------

    @Test
    fun encryptionRequiredIsUnderstoodOnBothRefusalMessages() {
        val server = ScriptedServer()
        val auth = authSession(listOf(server.asStoredPairing()))
        auth.start()
        assertEquals(
            PairingFailure.ENCRYPTION_REQUIRED,
            auth.onLine("""{"type":"auth_fail","reason":"encryption_required"}""").finishedFailure()
        )

        val ceremony = pairingSession()
        ceremony.start()
        assertEquals(
            PairingFailure.ENCRYPTION_REQUIRED,
            ceremony.onLine("""{"type":"pair_reject","reason":"encryption_required"}""").finishedFailure()
        )
    }

    /**
     * §2.18.4: after our proof has gone out on an encrypted session, a plaintext `auth_fail` is
     * still *accepted* — but its reason is not trusted. `unknown_client` can only genuinely arise
     * before the PC answered our challenge, so a forged one here is an attacker fishing for the
     * re-pairing prompt, and it must not get one.
     */
    @Test
    fun aPlaintextAuthFailAfterOurProofIsNotTrustedForItsReason() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = authSession(listOf(server.asStoredPairing()))
        session.onLine(server.authResponse(JSONObject(session.start().sends().single())))

        val failure = session
            .onLine("""{"type":"auth_fail","reason":"unknown_client"}""")
            .finishedFailure()
        assertEquals("never UNKNOWN_TO_PC, which is the only reason that offers a re-pair",
            PairingFailure.PROTOCOL, failure)
    }

    /** Before our proof — and on any plaintext session — the reason is still worth acting on. */
    @Test
    fun anAuthFailBeforeOurProofKeepsItsReason() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = authSession(listOf(server.asStoredPairing()))
        session.start()
        assertEquals(
            PairingFailure.UNKNOWN_TO_PC,
            session.onLine("""{"type":"auth_fail","reason":"unknown_client"}""").finishedFailure()
        )

        val v1 = ScriptedServer()
        val plaintext = authSession(listOf(v1.asStoredPairing()))
        plaintext.onLine(v1.authResponse(JSONObject(plaintext.start().sends().single())))
        assertEquals(
            "a v1 session's auth_fail is the only word we get, and it is honest",
            PairingFailure.UNKNOWN_TO_PC,
            plaintext.onLine("""{"type":"auth_fail","reason":"unknown_client"}""").finishedFailure()
        )
    }

    /** §2.18.6: no `pair_reject` accompanies a failure once the envelope is engaged. */
    @Test
    fun nothingIsAnnouncedAfterTheEnvelopeIsEngaged() {
        val server = ScriptedServer(speaks = PcLinkEnvelope.AEAD)
        val session = pairingSession()
        revealBoth(session, server)
        session.onUserAccept()
        session.onLine(server.pairConfirm())

        // An out-of-order message on the now-encrypted connection.
        val effects = session.onLine("""{"type":"pair_pubkey","role":"server"}""")
        assertEquals(PairingFailure.PROTOCOL, effects.finishedFailure())
        assertTrue("the handshake is long over; a reason buys nothing", effects.sends().isEmpty())
    }

    /** Local policy, independent of the pin: refuse a plaintext selection outright. */
    @Test
    fun requireEncryptionRefusesAPlaintextSelectionWithNoPinAtAll() {
        val server = ScriptedServer()
        val session = PairingSession.authenticate(
            clientIdentity, listOf(server.asStoredPairing()),
            clock = clock, random = fixedRandom, requireEncryption = true
        )
        val effects = session.onLine(server.authResponse(JSONObject(session.start().sends().single())))
        assertEquals(PairingFailure.ENCRYPTION_REQUIRED, effects.finishedFailure())

        val ceremony = PairingSession.pair(
            clientIdentity, "Pixel 9 Pro",
            clock = clock, random = fixedRandom, requireEncryption = true
        )
        val reveal = ScriptedServer().onPairStart(JSONObject(ceremony.start().sends().single()))
        val refused = ceremony.onLine(reveal)
        assertEquals(PairingFailure.ENCRYPTION_REQUIRED, refused.finishedFailure())
        // The ceremony is still plaintext and the PC has a dialog up, so it is told why.
        val sent = JSONObject(refused.sends().single())
        assertEquals("pair_reject", sent.getString("type"))
        assertEquals("encryption_required", sent.getString("reason"))
        assertTrue(
            "and nothing of ours is revealed",
            refused.none { it is PairingEffect.ShowSas || it is PairingEffect.Persist }
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun List<PairingEffect>.sends(): List<String> =
        filterIsInstance<PairingEffect.Send>().map { it.line }

    private fun List<PairingEffect>.finishedFailure(): PairingFailure {
        val outcome = filterIsInstance<PairingEffect.Finished>().single().outcome
        return (outcome as PairingOutcome.Failure).reason
    }

    private fun List<PairingEffect>.finishedSuccess(): PairingOutcome.Success =
        filterIsInstance<PairingEffect.Finished>().single().outcome as PairingOutcome.Success

    private fun assertNoPersist(effects: List<PairingEffect>) =
        assertTrue("nothing may be stored", effects.none { it is PairingEffect.Persist })

    /**
     * The PC side of the ceremony, running the same derivations the Rust server does. Fixed key
     * material and nonce so a failure is reproducible.
     */
    private inner class ScriptedServer(
        val name: String = "Living Room PC",
        /**
         * The highest encryption version this PC speaks (§2.18.1). **1 by default**, so every test
         * that doesn't say otherwise is playing the version-1 PC the field is full of right now —
         * which is what keeps the compatibility claim honest instead of asserted once and forgotten.
         */
        private val speaks: Int = PcLinkEnvelope.PLAINTEXT,
        /** Overrides the selection with something the negotiation rules should never accept. */
        private val selectionOverride: Int? = null
    ) {
        val identity = PcLinkPairingCrypto.identityFromPrivateKey(
            Hex.decode("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")!!
        )!!
        val nonce = ByteArray(16) { (0x10 + it).toByte() }
        val videoToken = Hex.encode(ByteArray(32) { (0x40 + it).toByte() })
        var keys: PcLinkPairingCrypto.SessionKeys? = null
        private var commitment: ByteArray = ByteArray(0)
        private var clientName: String = ""
        private var authNonces: Pair<ByteArray, ByteArray>? = null

        /** What the client offered, and what this PC answered — both inside the §2.18.2 proofs. */
        var offered: Int = PcLinkEnvelope.PLAINTEXT
            private set
        var selected: Int = PcLinkEnvelope.PLAINTEXT
            private set

        val sas: String get() = keys!!.sas

        /** The LTK a previous ceremony would have produced; used as a stored pairing for auth tests. */
        val ltk: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

        fun asStoredPairing(encryption: Int = PcLinkEnvelope.PLAINTEXT) = PcLinkPairing(
            serverId = identity.fingerprint, name = name, ltk = ltk,
            createdAt = "2026-08-11T14:03:00Z", lastSeenAt = "2026-08-11T14:03:00Z",
            lastHost = "192.168.1.10", encryption = encryption
        )

        /** `min(offer, what we speak)` — and a PC that can select 2 MUST (§2.18.1). */
        private fun select(opener: JSONObject): Int {
            offered = opener.optInt(PairingSession.FIELD_ENCRYPTION, PcLinkEnvelope.PLAINTEXT)
            selected = selectionOverride ?: minOf(offered, speaks)
            return selected
        }

        /** A version-1 PC omits the field entirely, which is the whole of "absent means 1". */
        private fun JSONObject.putSelection(): JSONObject =
            if (selected >= PcLinkEnvelope.AEAD) put(PairingSession.FIELD_ENCRYPTION, selected) else this

        fun onPairStart(pairStart: JSONObject): String {
            commitment = Hex.decode(pairStart.getString("commitment"))!!
            clientName = pairStart.optString("clientName")
            select(pairStart)
            return JSONObject()
                .put("type", "pair_pubkey").put("role", "server").put("name", name)
                .put("pubkey", Hex.encode(identity.publicKey))
                .put("nonce", Hex.encode(nonce))
                .putSelection()
                .toString()
        }

        /** The §2.18.3 keys for the ceremony path: the two `pair_pubkey` nonces. */
        fun pairingSessionKeys(clientNonce: ByteArray): PcLinkSessionKeys =
            PcLinkSessionKeys.derive(keys!!.ltk, clientNonce, nonce)

        /** The §2.18.3 keys for the auth path: the two auth nonces. */
        fun authSessionKeys(): PcLinkSessionKeys =
            authNonces!!.let { (c, s) -> PcLinkSessionKeys.derive(ltk, c, s) }

        /** This PC's `auth_response` nonce, for tests that recompute a proof by hand. */
        fun authNonceS(): ByteArray = authNonces!!.second

        /** Verifies the commitment (§4.3) and derives the session keys, exactly as the server does. */
        fun onClientReveal(reveal: JSONObject) {
            val clientPub = Hex.decode(reveal.getString("pubkey"))!!
            val clientNonce = Hex.decode(reveal.getString("nonce"))!!
            require(
                Hkdf.constantTimeEquals(
                    commitment, PcLinkPairingCrypto.commitment(clientPub, clientNonce)
                )
            ) { "commitment mismatch" }
            val th = PcLinkPairingCrypto.transcriptHash(
                PcLinkDiscovery.PROTOCOL_VERSION, PcLinkPairingCrypto.PAIRING_VERSION,
                clientName, name, clientPub, identity.publicKey, clientNonce, nonce
            )
            val ss = PcLinkPairingCrypto.sharedSecret(identity.privateKey, clientPub)!!
            keys = PcLinkPairingCrypto.deriveSessionKeys(PcLinkPairingCrypto.prk(th, ss), th)
        }

        fun verifyClientConfirm(confirm: JSONObject): Boolean = Hkdf.constantTimeEquals(
            Hex.decode(confirm.getString("confirm"))!!, keys!!.confirmClient
        )

        fun pairConfirm(): String = JSONObject()
            .put("type", "pair_confirm").put("role", "server")
            .put("confirm", Hex.encode(keys!!.confirmServer))
            .toString()

        fun authResponse(challenge: JSONObject): String {
            val nonceC = Hex.decode(challenge.getString("nonce"))!!
            val nonceS = ByteArray(16) { (0x30 + it).toByte() }
            authNonces = nonceC to nonceS
            select(challenge)
            return JSONObject()
                .put("type", "auth_response")
                .put("nonce", Hex.encode(nonceS))
                .put(
                    "proof",
                    Hex.encode(
                        PcLinkPairingCrypto.negotiatedAuthProof(
                            ltk, PeerRole.SERVER, nonceC, nonceS, offered, selected
                        )
                    )
                )
                .putSelection()
                .toString()
        }

        fun verifyClientProof(response: JSONObject): Boolean {
            val (nonceC, nonceS) = authNonces!!
            return Hkdf.constantTimeEquals(
                Hex.decode(response.getString("proof"))!!,
                PcLinkPairingCrypto.negotiatedAuthProof(
                    ltk, PeerRole.CLIENT, nonceC, nonceS, offered, selected
                )
            )
        }

        fun authOk(): String =
            JSONObject().put("type", "auth_ok").put("videoToken", videoToken).toString()
    }
}
