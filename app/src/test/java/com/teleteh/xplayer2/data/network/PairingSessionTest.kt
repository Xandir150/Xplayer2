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
    private inner class ScriptedServer(val name: String = "Living Room PC") {
        val identity = PcLinkPairingCrypto.identityFromPrivateKey(
            Hex.decode("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")!!
        )!!
        val nonce = ByteArray(16) { (0x10 + it).toByte() }
        val videoToken = Hex.encode(ByteArray(32) { (0x40 + it).toByte() })
        var keys: PcLinkPairingCrypto.SessionKeys? = null
        private var commitment: ByteArray = ByteArray(0)
        private var clientName: String = ""
        private var authNonces: Pair<ByteArray, ByteArray>? = null

        val sas: String get() = keys!!.sas

        /** The LTK a previous ceremony would have produced; used as a stored pairing for auth tests. */
        val ltk: ByteArray = ByteArray(32) { (it * 7 + 3).toByte() }

        fun asStoredPairing() = PcLinkPairing(
            serverId = identity.fingerprint, name = name, ltk = ltk,
            createdAt = "2026-08-11T14:03:00Z", lastSeenAt = "2026-08-11T14:03:00Z",
            lastHost = "192.168.1.10"
        )

        fun onPairStart(pairStart: JSONObject): String {
            commitment = Hex.decode(pairStart.getString("commitment"))!!
            clientName = pairStart.optString("clientName")
            return JSONObject()
                .put("type", "pair_pubkey").put("role", "server").put("name", name)
                .put("pubkey", Hex.encode(identity.publicKey))
                .put("nonce", Hex.encode(nonce))
                .toString()
        }

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
            return JSONObject()
                .put("type", "auth_response")
                .put("nonce", Hex.encode(nonceS))
                .put(
                    "proof",
                    Hex.encode(PcLinkPairingCrypto.authProof(ltk, PeerRole.SERVER, nonceC, nonceS))
                ).toString()
        }

        fun verifyClientProof(response: JSONObject): Boolean {
            val (nonceC, nonceS) = authNonces!!
            return Hkdf.constantTimeEquals(
                Hex.decode(response.getString("proof"))!!,
                PcLinkPairingCrypto.authProof(ltk, PeerRole.CLIENT, nonceC, nonceS)
            )
        }

        fun authOk(): String =
            JSONObject().put("type", "auth_ok").put("videoToken", videoToken).toString()
    }
}
