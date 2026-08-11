package com.teleteh.xplayer2.data.network

import com.teleteh.xplayer2.util.crypto.Hex
import com.teleteh.xplayer2.util.crypto.Hkdf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * End-to-end tests for [PcLinkPairingClient] over a real loopback socket, with a scripted PC on the
 * other end running the same derivations the Rust server does.
 *
 * [PairingSessionTest] already covers the state machine exhaustively; what these add is everything
 * the FSM deliberately doesn't know about — that `hello` really goes first, that messages are
 * newline-delimited JSON one per line, that user taps arriving from another thread reach the
 * session, and that the listener sees exactly one terminal callback.
 *
 * `context = null` skips the ConnectivityManager binding, and the codec list is passed explicitly
 * because the real one asks MediaCodec, which isn't available in a local unit test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcLinkPairingClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codecs = listOf(PcCodecCapability("video/hevc", 3840, 1080, 60))

    private val clientIdentity = PcLinkPairingCrypto.identityFromPrivateKey(
        Hex.decode("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")!!
    )!!

    @Before
    fun setUp() {
        // The client reports every listener callback on the main thread; there's no Looper here.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private class RecordingListener : PcLinkPairingClient.Listener {
        val finished = CountDownLatch(1)
        @Volatile var sas: String? = null
        @Volatile var serverName: String? = null
        @Volatile var persisted: Triple<String, String, ByteArray>? = null
        @Volatile var outcome: PairingOutcome? = null
        @Volatile var finishedCount = 0
        var onSas: (() -> Unit)? = null

        override fun onSasReady(sas: String, serverName: String, serverId: String) {
            this.sas = sas
            this.serverName = serverName
            onSas?.invoke()
        }

        override fun onPaired(serverId: String, serverName: String, ltk: ByteArray) {
            persisted = Triple(serverId, serverName, ltk)
        }

        override fun onFinished(outcome: PairingOutcome) {
            this.outcome = outcome
            finishedCount++
            finished.countDown()
        }
    }

    @Test
    fun runsTheWholeCeremonyOverARealSocket() {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val server = ScriptedPc(serverSocket)
        val serverThread = thread(name = "scripted-pc") { server.run() }

        val listener = RecordingListener()
        val session = PairingSession.pair(clientIdentity, "Pixel 9 Pro")
        val client = PcLinkPairingClient(
            context = null,
            host = "127.0.0.1",
            controlPort = serverSocket.localPort,
            session = session,
            listener = listener,
            clientName = "Pixel 9 Pro",
            codecs = codecs
        )
        // The tap that a user would make once the codes match, from the main thread as in the app.
        listener.onSas = { client.accept() }

        runBlocking {
            client.start(scope)
            assertTrue("ceremony didn't finish", listener.finished.await(15, TimeUnit.SECONDS))
        }
        serverThread.join(5_000)
        serverSocket.close()

        assertEquals("hello must precede everything", "hello", server.firstMessageType)
        assertEquals(1, server.helloProtocolVersion)
        assertEquals("Pixel 9 Pro", server.helloClientName)
        assertEquals("both sides derived the same code", server.sas, listener.sas)
        assertEquals("Living Room PC", listener.serverName)
        assertTrue("the PC must accept our confirmation tag", server.clientConfirmValid)

        val persisted = listener.persisted
        assertNotNull("the pairing must be handed to the store", persisted)
        assertEquals(server.identity.fingerprint, persisted!!.first)
        assertEquals("Living Room PC", persisted.second)
        assertArrayEquals(server.ltk, persisted.third)

        val outcome = listener.outcome as PairingOutcome.Success
        assertEquals(server.identity.fingerprint, outcome.serverId)
        assertEquals(server.videoToken, outcome.videoToken)
        assertTrue(outcome.paired)
        assertEquals("exactly one terminal callback", 1, listener.finishedCount)
    }

    @Test
    fun decliningTellsThePcAndStoresNothing() {
        val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val server = ScriptedPc(serverSocket)
        val serverThread = thread(name = "scripted-pc-decline") { server.run() }

        val listener = RecordingListener()
        val client = PcLinkPairingClient(
            context = null, host = "127.0.0.1", controlPort = serverSocket.localPort,
            session = PairingSession.pair(clientIdentity, "Pixel 9 Pro"),
            listener = listener, clientName = "Pixel 9 Pro", codecs = codecs
        )
        listener.onSas = { client.decline() }

        runBlocking {
            client.start(scope)
            assertTrue(listener.finished.await(15, TimeUnit.SECONDS))
        }
        serverThread.join(5_000)
        serverSocket.close()

        assertEquals(
            PairingFailure.DECLINED_LOCALLY,
            (listener.outcome as PairingOutcome.Failure).reason
        )
        assertEquals("nothing may be stored", null, listener.persisted)
        // The PC's dialog must come down now, not in 90 seconds.
        assertEquals("pair_reject", server.lastClientMessageType)
        assertEquals("declined", server.lastRejectReason)
    }

    @Test
    fun anUnreachablePcFailsWithoutHanging() {
        // Bind, note the port, close: nothing is listening there now.
        val deadPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        val listener = RecordingListener()
        val client = PcLinkPairingClient(
            context = null, host = "127.0.0.1", controlPort = deadPort,
            session = PairingSession.pair(clientIdentity, "Pixel 9 Pro"),
            listener = listener, clientName = "Pixel 9 Pro", codecs = codecs
        )
        runBlocking {
            client.start(scope)
            assertTrue(listener.finished.await(15, TimeUnit.SECONDS))
        }
        assertEquals(
            PairingFailure.CONNECTION_LOST,
            (listener.outcome as PairingOutcome.Failure).reason
        )
    }

    @Test
    fun deviceNameFallsBackSensibly() {
        // Build.MODEL/MANUFACTURER are null against the stubbed android.jar, which is exactly the
        // "we know nothing about this device" case the fallback exists for.
        assertEquals("XPlayer2", PcLinkPairingClient.defaultClientName())
    }

    /**
     * The PC end of the ceremony: one connection, newline-delimited JSON, the real derivations.
     * Records what it saw so the test can assert on ordering as well as outcome.
     */
    private inner class ScriptedPc(private val serverSocket: ServerSocket) {
        val identity = PcLinkPairingCrypto.identityFromPrivateKey(
            Hex.decode("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")!!
        )!!
        private val nonce = ByteArray(16) { (0x10 + it).toByte() }
        val videoToken = Hex.encode(ByteArray(32) { (0x40 + it).toByte() })

        @Volatile var firstMessageType: String? = null
        @Volatile var helloClientName: String? = null
        @Volatile var helloProtocolVersion: Int = -1
        @Volatile var lastClientMessageType: String? = null
        @Volatile var lastRejectReason: String? = null
        @Volatile var clientConfirmValid = false
        @Volatile var sas: String? = null
        @Volatile var ltk: ByteArray? = null

        private var keys: PcLinkPairingCrypto.SessionKeys? = null
        private var commitment: ByteArray? = null
        private var clientName = ""

        fun run() {
            try {
                serverSocket.accept().use { socket ->
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: return
                        if (line.isBlank()) continue
                        if (!handle(JSONObject(line), reader, writer)) return
                    }
                }
            } catch (_: Exception) {
                // The client closing mid-exchange is a normal end for several of these tests.
            }
        }

        /** Returns false when the exchange is over. */
        private fun handle(
            message: JSONObject,
            @Suppress("UNUSED_PARAMETER") reader: BufferedReader,
            writer: BufferedWriter
        ): Boolean {
            val type = message.optString("type")
            if (firstMessageType == null) firstMessageType = type
            lastClientMessageType = type
            when (type) {
                "hello" -> {
                    helloClientName = message.optString("clientName")
                    helloProtocolVersion = message.optInt("protocolVersion", -1)
                }

                "pair_start" -> {
                    commitment = Hex.decode(message.getString("commitment"))
                    clientName = message.optString("clientName")
                    writer.send(
                        JSONObject()
                            .put("type", "pair_pubkey").put("role", "server")
                            .put("name", "Living Room PC")
                            .put("pubkey", Hex.encode(identity.publicKey))
                            .put("nonce", Hex.encode(nonce))
                    )
                }

                "pair_pubkey" -> {
                    val clientPub = Hex.decode(message.getString("pubkey"))!!
                    val clientNonce = Hex.decode(message.getString("nonce"))!!
                    check(
                        Hkdf.constantTimeEquals(
                            commitment!!, PcLinkPairingCrypto.commitment(clientPub, clientNonce)
                        )
                    ) { "commitment mismatch" }
                    val th = PcLinkPairingCrypto.transcriptHash(
                        PcLinkDiscovery.PROTOCOL_VERSION, PcLinkPairingCrypto.PAIRING_VERSION,
                        clientName, "Living Room PC", clientPub, identity.publicKey,
                        clientNonce, nonce
                    )
                    val ss = PcLinkPairingCrypto.sharedSecret(identity.privateKey, clientPub)!!
                    keys = PcLinkPairingCrypto.deriveSessionKeys(PcLinkPairingCrypto.prk(th, ss), th)
                    sas = keys!!.sas
                    ltk = keys!!.ltk
                }

                "pair_confirm" -> {
                    clientConfirmValid = Hkdf.constantTimeEquals(
                        Hex.decode(message.getString("confirm"))!!, keys!!.confirmClient
                    )
                    check(clientConfirmValid) { "client confirm tag mismatch" }
                    writer.send(
                        JSONObject().put("type", "pair_confirm").put("role", "server")
                            .put("confirm", Hex.encode(keys!!.confirmServer))
                    )
                    writer.send(JSONObject().put("type", "auth_ok").put("videoToken", videoToken))
                }

                "pair_reject" -> {
                    lastRejectReason = message.optString("reason")
                    return false
                }
            }
            return true
        }

        private fun BufferedWriter.send(message: JSONObject) {
            write(message.toString())
            write("\n")
            flush()
        }
    }
}
