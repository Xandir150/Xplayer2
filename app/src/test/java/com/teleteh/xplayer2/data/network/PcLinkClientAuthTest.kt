package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedWriter
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Transcript tests for the authenticated half of [PcLinkClient], driven against a scripted PC on
 * loopback sockets that runs the same derivations the Rust server does.
 *
 * [PairingSessionTest] pins the state machine and [PcLinkPairingClientTest] pins the ceremony's own
 * socket wrapper; what these add is the *streaming* client's session shape — that `hello` is
 * followed by an `auth_challenge` and only then by the wait for `config`, that the token which ends
 * up in the video preamble is the right one, that a refusal is terminal instead of reconnecting
 * forever, and that an unpaired PC still gets the untouched M1 flow.
 *
 * `context = null` skips the ConnectivityManager binding, `codecs` is passed explicitly because the
 * real list asks MediaCodec, and `nowMs` is injected because `SystemClock` throws against the
 * stubbed android.jar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcLinkClientAuthTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codecs = listOf(PcCodecCapability("video/hevc", 3840, 1080, 60))

    private val clientIdentity = PcLinkPairingCrypto.identityFromPrivateKey(
        Hex.decode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!!
    )!!

    private val serverIdentity = PcLinkPairingCrypto.identityFromPrivateKey(
        Hex.decode("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")!!
    )!!

    /** The LTK a completed ceremony would have left on both sides. */
    private val ltk = ByteArray(32) { (it * 7 + 3).toByte() }

    /** What `auth_ok` hands out, and what a conforming `config` then repeats. */
    private val authToken = Hex.encode(ByteArray(32) { (0x40 + it).toByte() })

    private val storedPairing = PcLinkPairing(
        serverId = serverIdentity.fingerprint,
        name = "Living Room PC",
        ltk = ltk,
        createdAt = "2026-08-11T14:03:00Z",
        lastSeenAt = "2026-08-11T14:03:00Z",
        lastHost = "127.0.0.1"
    )

    private var pc: ScriptedPc? = null

    @Before
    fun setUp() {
        // PcLinkClient reports state and config on the main thread; there's no Looper here.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        pc?.shutdown()
        scope.cancel()
        Dispatchers.resetMain()
    }

    // ---- the authenticated path ------------------------------------------------------------------

    @Test
    fun `authenticates with a stored pairing before waiting for config`() {
        val pc = start(ScriptedPc(AuthReply.PROVE))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = PcLinkAuth(clientIdentity, listOf(storedPairing)))
        client.connect(scope)
        assertTrue("no video preamble arrived", pc.preamble.await(15, TimeUnit.SECONDS))
        // Streaming is emitted just after the preamble goes out, so the latch above can win the
        // race with it — wait for the state itself rather than assuming it has landed.
        assertTrue("never reported Streaming", listener.streaming.await(15, TimeUnit.SECONDS))
        client.close()

        assertEquals(
            "hello first, then one authentication exchange, and nothing else pre-config",
            listOf("hello", "auth_challenge", "auth_response"),
            pc.clientMessageTypes()
        )
        assertEquals(1, pc.helloProtocolVersion)
        assertEquals(clientIdentity.fingerprint, pc.challengeClientId)
        assertEquals(PcLinkPairingCrypto.PAIRING_VERSION, pc.challengePairingVersion)
        // §2.11: 16 fresh random bytes per connection.
        assertEquals(2 * PcLinkPairingCrypto.NONCE_LEN, pc.challengeNonce?.length)
        assertTrue("the PC must accept our proof", pc.clientProofValid)
        // §2.12: the client's auth_response carries a proof and no nonce of its own.
        assertNull("the client must not send a nonce back", pc.clientResponseNonce)

        assertArrayEquals(
            "the preamble must present the session's token",
            PcLinkProtocol.videoPreamble(authToken),
            pc.preambleBytes
        )
        assertTrue("config never reached the listener", listener.configs.isNotEmpty())
        assertTrue(
            "an authenticated session must not report an auth failure",
            listener.states.none { it is PcLinkState.AuthFailed }
        )
    }

    /**
     * §2.2, "whichever message most recently carried one": a server is free to issue a fresh token
     * in `config` instead of repeating the still-unspent one from `auth_ok`, and that fresher token
     * is the one the video connection must present.
     */
    @Test
    fun `a fresher token in config supersedes the auth_ok one`() {
        val configToken = Hex.encode(ByteArray(32) { (0x90 + it).toByte() })
        val pc = start(ScriptedPc(AuthReply.PROVE, configToken = configToken))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = PcLinkAuth(clientIdentity, listOf(storedPairing)))
        client.connect(scope)
        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))
        client.close()

        assertArrayEquals(PcLinkProtocol.videoPreamble(configToken), pc.preambleBytes)
    }

    // ---- refusals: terminal, and only one of them may offer a re-pair ------------------------------

    @Test
    fun `unknown_client is terminal and asks for a re-pair`() {
        val pc = start(ScriptedPc(AuthReply.UNKNOWN_CLIENT))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = PcLinkAuth(clientIdentity, listOf(storedPairing)))
        client.connect(scope)
        assertTrue("never reported a failure", listener.authFailed.await(15, TimeUnit.SECONDS))

        assertEquals(PairingFailure.UNKNOWN_TO_PC, listener.authFailure())
        assertNoRetry(pc, listener)
        client.close()
    }

    @Test
    fun `bad_proof is terminal and must never ask for a re-pair`() {
        val pc = start(ScriptedPc(AuthReply.BAD_PROOF))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = PcLinkAuth(clientIdentity, listOf(storedPairing)))
        client.connect(scope)
        assertTrue(listener.authFailed.await(15, TimeUnit.SECONDS))

        // §8.4: an impostor or corruption signal, not a forgotten pairing.
        assertEquals(PairingFailure.AUTH_FAILED, listener.authFailure())
        assertNoRetry(pc, listener)
        client.close()
    }

    /** §2.12: the server proves first, and a proof that doesn't verify must stop us dead. */
    @Test
    fun `a server proof that does not verify is refused before we reveal our own`() {
        val pc = start(ScriptedPc(AuthReply.WRONG_PROOF))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = PcLinkAuth(clientIdentity, listOf(storedPairing)))
        client.connect(scope)
        assertTrue(listener.authFailed.await(15, TimeUnit.SECONDS))

        assertEquals(PairingFailure.AUTH_FAILED, listener.authFailure())
        assertEquals(
            "our own proof must never go out to a PC that failed to prove itself",
            listOf("hello", "auth_challenge"),
            pc.clientMessageTypes()
        )
        assertNoRetry(pc, listener)
        client.close()
    }

    /**
     * A refusal costs the whole client, not just the connection: another TCP attempt would present
     * the same key for the same answer, and would spend the server's per-IP auth budget (§8.5).
     */
    private fun assertNoRetry(pc: ScriptedPc, listener: RecordingListener) {
        // Comfortably past RECONNECT_BASE_DELAY_MS, so a reconnecting client would have landed.
        Thread.sleep(2 * PcLinkClient.RECONNECT_BASE_DELAY_MS)
        assertEquals("a refusal must not be retried", 1, pc.controlConnections.get())
        assertTrue(
            "a refused session must not reach the generic reconnect states",
            listener.states.none { it is PcLinkState.Reconnecting || it is PcLinkState.Failed }
        )
    }

    // ---- the unpaired M1 flow, unchanged ----------------------------------------------------------

    @Test
    fun `an unpaired session never authenticates and takes its token from config`() {
        val pc = start(ScriptedPc(AuthReply.NONE))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = null)
        client.connect(scope)
        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))
        client.close()

        assertEquals(listOf("hello"), pc.clientMessageTypes())
        assertArrayEquals(PcLinkProtocol.videoPreamble(M1_TOKEN), pc.preambleBytes)
        assertEquals(1, listener.configs.size)
    }

    // ---- §2.18 encrypted sessions --------------------------------------------------------------------

    /**
     * A whole version-2 session over real sockets: the offer goes out on `auth_challenge`, the v2
     * proofs verify on both sides, and `auth_ok` and `config` arrive inside `enc` envelopes — which
     * the client opens well enough to reach the video preamble with the right token.
     *
     * The token assertion is the point of the section: the only place it exists in the clear is
     * inside the envelope and in the preamble on the *video* connection (§2.18.8).
     */
    @Test
    fun `runs a whole session inside the enc envelope`() {
        val pc = start(ScriptedPc(AuthReply.PROVE_V2))
        val listener = RecordingListener()
        val pinned = ArrayList<PairingEffect.Persist>()

        val client = client(
            pc, listener,
            auth = PcLinkAuth(clientIdentity, listOf(storedPairing)) { pinned += it }
        )
        client.connect(scope)
        assertTrue("no video preamble arrived", pc.preamble.await(15, TimeUnit.SECONDS))
        assertTrue("never reported Streaming", listener.streaming.await(15, TimeUnit.SECONDS))
        client.close()

        assertEquals(PcLinkEnvelope.AEAD, pc.challengeEncryption)
        assertTrue("the client's v2 proof must verify", pc.clientProofValid)
        assertEquals(
            listOf("hello", "auth_challenge", "auth_response"),
            pc.clientMessageTypes()
        )
        // The `config` inside the envelope is the one that reached the listener, token and all.
        assertArrayEquals(PcLinkProtocol.videoPreamble(authToken), pc.preambleBytes)
        assertEquals(1, listener.configs.size)
        assertEquals(3840, listener.configs.single().width)
    }

    /**
     * §2.18.7's record, written by the streaming client because nobody else can: this pairing was
     * made against a version-1 PC (its stored `encryption` is 1), so `auth_ok` on this reconnect is
     * the only moment its pin will ever be raised.
     */
    @Test
    fun `records the encryption pin out of auth_ok`() {
        val pc = start(ScriptedPc(AuthReply.PROVE_V2))
        val listener = RecordingListener()
        val pinned = ArrayList<PairingEffect.Persist>()

        val client = client(
            pc, listener,
            auth = PcLinkAuth(clientIdentity, listOf(storedPairing)) { pinned += it }
        )
        client.connect(scope)
        assertTrue(listener.streaming.await(15, TimeUnit.SECONDS))
        client.close()

        val persist = pinned.single()
        assertEquals(PcLinkEnvelope.AEAD, persist.encryption)
        assertEquals(storedPairing.serverId, persist.serverId)
        assertFalse("only the pin moves; this is no ceremony", persist.fresh)
        assertArrayEquals("and never the key", ltk, persist.ltk)
    }

    /** A version-1 PC gets the version-1 session, unchanged, and nothing is pinned. */
    @Test
    fun `a v1 PC still runs plaintext and pins nothing`() {
        val pc = start(ScriptedPc(AuthReply.PROVE))
        val listener = RecordingListener()
        val pinned = ArrayList<PairingEffect.Persist>()

        val client = client(
            pc, listener,
            auth = PcLinkAuth(clientIdentity, listOf(storedPairing)) { pinned += it }
        )
        client.connect(scope)
        assertTrue(listener.streaming.await(15, TimeUnit.SECONDS))
        client.close()

        assertEquals("the offer still goes out — it is additive", PcLinkEnvelope.AEAD, pc.challengeEncryption)
        assertTrue(pc.clientProofValid)
        assertTrue("a plaintext session has no pin to record", pinned.isEmpty())
    }

    /**
     * §2.18.6, end to end: a plaintext `config` spliced into an engaged session ends it. The client
     * must not act on the injected message — which a version-1 client would have done without
     * noticing — and must not resynchronize past it either.
     */
    @Test
    fun `a plaintext message spliced into an engaged session ends it`() {
        val pc = start(ScriptedPc(AuthReply.SPLICE_PLAINTEXT))
        val listener = RecordingListener()

        val client = client(pc, listener, auth = PcLinkAuth(clientIdentity, listOf(storedPairing)))
        client.connect(scope)
        // The session dies and the client comes back for a new one, which is the honest outcome:
        // the *session* is over, not the client, and a fresh connection means fresh nonces.
        assertTrue("never gave up on the spliced session", listener.reconnecting.await(15, TimeUnit.SECONDS))
        client.close()

        assertTrue(
            "the injected config must never reach the listener",
            listener.configs.isEmpty()
        )
        assertEquals(
            "and no video connection may be opened on it",
            0, listener.states.count { it is PcLinkState.Streaming }
        )
    }

    // ---- harness -----------------------------------------------------------------------------------

    private fun start(pc: ScriptedPc): ScriptedPc {
        this.pc = pc
        pc.start()
        return pc
    }

    private fun client(pc: ScriptedPc, listener: PcLinkClient.Listener, auth: PcLinkAuth?) =
        PcLinkClient(
            context = null,
            host = "127.0.0.1",
            controlPort = pc.controlPort,
            videoPort = pc.videoPort,
            listener = listener,
            clientName = "Pixel 9 Pro",
            codecs = codecs,
            // SystemClock throws against the stubbed android.jar; this is the same monotonic
            // millisecond clock PairingSession defaults to, which is what the FSM's timers need.
            nowMs = { System.nanoTime() / 1_000_000 },
            authProvider = { auth }
        )

    private class RecordingListener : PcLinkClient.Listener {
        val states: MutableList<PcLinkState> = Collections.synchronizedList(ArrayList())
        val configs: MutableList<PcLinkStreamConfig> = Collections.synchronizedList(ArrayList())
        val authFailed = CountDownLatch(1)
        val streaming = CountDownLatch(1)
        val reconnecting = CountDownLatch(1)

        override fun onState(state: PcLinkState) {
            states += state
            if (state is PcLinkState.AuthFailed) authFailed.countDown()
            if (state is PcLinkState.Streaming) streaming.countDown()
            if (state is PcLinkState.Reconnecting) reconnecting.countDown()
        }

        override fun onConfig(config: PcLinkStreamConfig) {
            configs += config
        }

        override fun onVideoFrame(frame: PcVideoFrame) = Unit

        fun authFailure(): PairingFailure? =
            states.filterIsInstance<PcLinkState.AuthFailed>().lastOrNull()?.reason
    }

    private enum class AuthReply {
        /** Prove possession honestly, verify the client's proof, then `auth_ok` + `config`. */
        PROVE,

        /**
         * The same, but selecting encryption 2 (§2.18): v2 proofs, and `auth_ok` and `config` sealed
         * into `enc` envelopes. This is the whole of a version-2 session over a real socket.
         */
        PROVE_V2,

        /**
         * Selects 2, engages — and then splices one **plaintext** `config` into the stream, which is
         * what an on-path attacker's injection looks like once the envelope is up (§2.18.6).
         */
        SPLICE_PLAINTEXT,

        /** `auth_fail {"reason":"unknown_client"}` — this PC has forgotten the phone. */
        UNKNOWN_CLIENT,

        /** `auth_fail {"reason":"bad_proof"}` — the PC rejected our proof. */
        BAD_PROOF,

        /** An `auth_response` whose proof was made with the wrong key: an impostor. */
        WRONG_PROOF,

        /** No pairing extension at all — the M1 interim server, which sends `config` after `hello`. */
        NONE
    }

    /**
     * The PC end: a control listener speaking newline-delimited JSON and a video listener that only
     * has to collect the 36-byte preamble. Records what it saw, in order, so the tests can assert on
     * the message sequence as well as the outcome.
     */
    private inner class ScriptedPc(
        private val reply: AuthReply,
        /** What `config` advertises. Null repeats the `auth_ok` token, as a conforming server does. */
        private val configToken: String? = null
    ) {
        private val controlServer = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val videoServer = ServerSocket(0, 4, InetAddress.getLoopbackAddress())

        val controlPort: Int get() = controlServer.localPort
        val videoPort: Int get() = videoServer.localPort

        val controlConnections = AtomicInteger()
        val preamble = CountDownLatch(1)

        @Volatile var preambleBytes: ByteArray? = null
        @Volatile var helloProtocolVersion: Int = -1
        @Volatile var challengeClientId: String? = null
        @Volatile var challengePairingVersion: Int = -1
        @Volatile var challengeNonce: String? = null
        @Volatile var clientResponseNonce: String? = null
        @Volatile var clientProofValid = false
        @Volatile var challengeEncryption: Int = -1

        /** Set once this PC has engaged the §2.18.4 envelope; seals everything it sends after that. */
        @Volatile private var sealer: PcLinkSealer? = null

        /** What this PC selects: [PcLinkEnvelope.AEAD] on the v2 replies, 1 (i.e. absent) otherwise. */
        private val selects: Int
            get() = if (reply == AuthReply.PROVE_V2 || reply == AuthReply.SPLICE_PLAINTEXT) {
                PcLinkEnvelope.AEAD
            } else {
                PcLinkEnvelope.PLAINTEXT
            }

        private val messageTypes = Collections.synchronizedList(ArrayList<String>())
        private val threads = ArrayList<Thread>()
        private val sockets = Collections.synchronizedList(ArrayList<Socket>())

        /** The token `auth_ok` issues, and the one `config` repeats unless [configToken] says otherwise. */
        private val issuedToken get() = if (reply == AuthReply.NONE) M1_TOKEN else authToken
        private var serverNonce = ByteArray(16) { (0x30 + it).toByte() }

        fun clientMessageTypes(): List<String> = ArrayList(messageTypes)

        fun start() {
            threads += thread(name = "scripted-pc-control") { acceptControl() }
            threads += thread(name = "scripted-pc-video") { acceptVideo() }
        }

        fun shutdown() {
            try { controlServer.close() } catch (_: Exception) { }
            try { videoServer.close() } catch (_: Exception) { }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            // Closing a socket doesn't wake the video thread's hold; interrupting it does.
            threads.forEach { it.interrupt() }
            threads.forEach { it.join(5_000) }
        }

        private fun acceptControl() {
            while (true) {
                val socket = try { controlServer.accept() } catch (_: Exception) { return }
                sockets += socket
                controlConnections.incrementAndGet()
                try {
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        if (!handle(JSONObject(line), writer)) break
                    }
                } catch (_: Exception) {
                    // The client closing mid-exchange ends several of these tests normally.
                } finally {
                    runCatching { socket.close() }
                }
            }
        }

        private fun acceptVideo() {
            val socket = try { videoServer.accept() } catch (_: Exception) { return }
            sockets += socket
            try {
                socket.soTimeout = 15_000
                val buf = ByteArray(PcLinkProtocol.PREAMBLE_LEN)
                DataInputStream(socket.getInputStream()).readFully(buf)
                preambleBytes = buf
                preamble.countDown()
                // Hold the connection open: an EOF here would restart the whole session.
                Thread.sleep(15_000)
            } catch (_: Exception) {
                // Interrupted or closed by shutdown(): the test already has what it needed.
            } finally {
                runCatching { socket.close() }
            }
        }

        /** Returns false when this connection is done. */
        private fun handle(message: JSONObject, writer: BufferedWriter): Boolean {
            val type = message.optString("type")
            messageTypes += type
            when (type) {
                "hello" -> {
                    helloProtocolVersion = message.optInt("protocolVersion", -1)
                    // The M1 interim server issues its token in `config` and nowhere else (§4).
                    if (reply == AuthReply.NONE) writer.send(configLine())
                }

                "auth_challenge" -> {
                    challengeClientId = message.optString("clientId")
                    challengePairingVersion = message.optInt("pairingVersion", -1)
                    challengeNonce = message.optString("nonce")
                    challengeEncryption = message.optInt("encryption", PcLinkEnvelope.PLAINTEXT)
                    val clientNonce = Hex.decode(challengeNonce!!)!!
                    when (reply) {
                        AuthReply.UNKNOWN_CLIENT -> {
                            writer.send(authFail("unknown_client"))
                            return false
                        }

                        AuthReply.BAD_PROOF -> {
                            writer.send(authFail("bad_proof"))
                            return false
                        }

                        AuthReply.WRONG_PROOF -> writer.send(
                            authResponse(Hex.encode(ByteArray(PcLinkPairingCrypto.MAC_LEN) { 0x5a }))
                        )

                        else -> writer.send(
                            authResponse(
                                Hex.encode(
                                    PcLinkPairingCrypto.negotiatedAuthProof(
                                        ltk, PeerRole.SERVER, clientNonce, serverNonce,
                                        challengeEncryption, selects
                                    )
                                )
                            )
                        )
                    }
                }

                "auth_response" -> {
                    // §2.12: the client's half carries a proof and no nonce.
                    clientResponseNonce = if (message.has("nonce")) message.getString("nonce") else null
                    val clientNonce = Hex.decode(challengeNonce!!)!!
                    clientProofValid = Hkdf.constantTimeEquals(
                        Hex.decode(message.getString("proof"))!!,
                        PcLinkPairingCrypto.negotiatedAuthProof(
                            ltk, PeerRole.CLIENT, clientNonce, serverNonce,
                            challengeEncryption, selects
                        )
                    )
                    check(clientProofValid) { "client proof mismatch" }
                    if (selects >= PcLinkEnvelope.AEAD) {
                        // §2.18.4: the server's last plaintext message was its `auth_response`, and
                        // `auth_ok` is the first envelope — which is what takes the token off the
                        // wire in the first place.
                        sealer = PcLinkSessionKeys.derive(ltk, clientNonce, serverNonce).serverLink().sealer
                    }
                    // One write, two lines: a real server packs these together, which is exactly the
                    // case where a `config` can already be buffered by the time the client looks.
                    writer.write(
                        dress(JSONObject().put("type", "auth_ok").put("videoToken", issuedToken).toString())
                    )
                    writer.write("\n")
                    if (reply == AuthReply.SPLICE_PLAINTEXT) {
                        // The injection: a perfectly well-formed `config` that simply isn't wearing
                        // the envelope. A v1 client would act on it.
                        writer.write(configLine())
                    } else {
                        writer.write(dress(configLine()))
                    }
                    writer.write("\n")
                    writer.flush()
                }
            }
            return true
        }

        /** Seals a line once this PC has engaged; passes it through unchanged before that. */
        private fun dress(line: String): String = sealer?.seal(line) ?: line

        private fun authFail(reason: String): String =
            JSONObject().put("type", "auth_fail").put("reason", reason).toString()

        private fun authResponse(proof: String): String = JSONObject()
            .put("type", "auth_response")
            .put("nonce", Hex.encode(serverNonce))
            .put("proof", proof)
            .apply { if (selects >= PcLinkEnvelope.AEAD) put("encryption", selects) }
            .toString()

        private fun configLine(): String = JSONObject()
            .put("type", "config")
            .put("mime", "video/hevc")
            .put("width", 3840)
            .put("height", 1080)
            .put("fps", 60.0)
            .put("stereo", "sbs")
            .put("canvasAngularWidthDeg", 45.0)
            .put("canvasDistanceM", 3.0)
            .put("videoToken", configToken ?: issuedToken)
            .toString()

        private fun BufferedWriter.send(line: String) {
            write(line)
            write("\n")
            flush()
        }
    }

    private companion object {
        /** What an M1 interim server (no pairing extension) puts in its `config`. */
        val M1_TOKEN = Hex.encode(ByteArray(32) { (0x70 + it).toByte() })
    }
}
