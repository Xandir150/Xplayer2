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

        override fun onState(state: PcLinkState) {
            states += state
            if (state is PcLinkState.AuthFailed) authFailed.countDown()
            if (state is PcLinkState.Streaming) streaming.countDown()
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
                                    PcLinkPairingCrypto.authProof(
                                        ltk, PeerRole.SERVER, clientNonce, serverNonce
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
                        PcLinkPairingCrypto.authProof(ltk, PeerRole.CLIENT, clientNonce, serverNonce)
                    )
                    check(clientProofValid) { "client proof mismatch" }
                    // One write, two lines: a real server packs these together, which is exactly the
                    // case where a `config` can already be buffered by the time the client looks.
                    writer.write(
                        JSONObject().put("type", "auth_ok").put("videoToken", issuedToken).toString()
                    )
                    writer.write("\n")
                    writer.write(configLine())
                    writer.write("\n")
                    writer.flush()
                }
            }
            return true
        }

        private fun authFail(reason: String): String =
            JSONObject().put("type", "auth_fail").put("reason", reason).toString()

        private fun authResponse(proof: String): String = JSONObject()
            .put("type", "auth_response")
            .put("nonce", Hex.encode(serverNonce))
            .put("proof", proof)
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
