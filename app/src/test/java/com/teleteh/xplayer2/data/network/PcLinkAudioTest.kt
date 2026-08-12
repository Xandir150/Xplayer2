package com.teleteh.xplayer2.data.network

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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The audio half of the wire (`xplayer-link-server/docs/audio-design.md`): the `hello` capability
 * object, the `config.audio` negotiation result, the `set_audio` mute message, and — the one that
 * actually protects the video path — the routing of `0x04` frames.
 *
 * §4 of that design is the reason the routing test exists: an audio chunk that reached
 * `PcStreamDecoder` would be handed to MediaCodec as an access unit and would also overflow
 * `PcAuDropPolicy` at 50 chunks/s, collapsing perfectly good video into an IDR-request loop. So
 * "audio frames never reach `onVideoFrame`" is pinned end-to-end against a scripted PC rather than
 * by inspection.
 *
 * The scripted PC here speaks the unauthenticated (M1) flow, which is the shortest path to a
 * streaming session; authentication is covered by [PcLinkClientAuthTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcLinkAudioTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val codecs = listOf(PcCodecCapability("video/hevc", 3840, 1080, 60))
    private val capability = PcAudioCapability(
        codecs = listOf("pcm_s16le"),
        rates = listOf(48_000, 44_100),
        channels = 2
    )
    private val token = "a".repeat(64)

    private var pc: ScriptedPc? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        pc?.shutdown()
        scope.cancel()
        Dispatchers.resetMain()
    }

    // --- hello: the capability object ---------------------------------------------------------

    @Test
    fun `hello carries the audio capability when the device has one`() {
        val line = PcLinkProtocol.helloLine("Pixel 9 Pro", codecs, audio = capability)
        val audio = JSONObject(line.trim()).getJSONObject("audio")
        assertEquals("pcm_s16le", audio.getJSONArray("codecs").getString(0))
        assertEquals(48_000, audio.getJSONArray("rates").getInt(0))
        assertEquals(44_100, audio.getJSONArray("rates").getInt(1))
        assertEquals(2, audio.getInt("channels"))
    }

    @Test
    fun `hello omits audio entirely when there is nothing to play it with`() {
        // The whole compatibility gate (§4): no `audio` object means the server must not send a
        // single 0x04 frame, and the stream stays byte-identical to the pre-audio wire.
        val line = PcLinkProtocol.helloLine("Pixel 9 Pro", codecs, audio = null)
        assertFalse(line.contains("audio"))
    }

    // --- config: the negotiated format ---------------------------------------------------------

    @Test
    fun `config announces the negotiated audio format`() {
        val config = PcLinkProtocol.parseConfig(JSONObject(configJson(audio = """
            {"codec":"pcm_s16le","rate":48000,"channels":2}
        """.trimIndent())))
        assertNotNull(config)
        assertEquals(PcAudioFormat("pcm_s16le", 48_000, 2), config!!.audio)
        assertEquals(1920, config.audio!!.bytesForMs(10))
        assertEquals(10, config.audio!!.msForBytes(1920))
    }

    @Test
    fun `a config without audio is a silent stream, and still a valid config`() {
        val config = PcLinkProtocol.parseConfig(JSONObject(configJson(audio = null)))
        assertNotNull(config)
        assertNull(config!!.audio)
    }

    @Test
    fun `an unusable audio object costs the audio, never the video`() {
        // §3.3 is explicit that audio degrades independently and the receiver keeps rendering
        // video — so unlike the canvas fields, a broken `audio` must not drop the whole message.
        for (bad in listOf(
            """{"codec":"opus","rate":48000,"channels":2}""",
            """{"codec":"pcm_s16le","rate":0,"channels":2}""",
            """{"codec":"pcm_s16le","rate":48000,"channels":0}""",
            """{"rate":48000,"channels":2}"""
        )) {
            val config = PcLinkProtocol.parseConfig(JSONObject(configJson(audio = bad)))
            assertNotNull("config dropped over `$bad`", config)
            assertNull("audio should be off for `$bad`", config!!.audio)
        }
    }

    // --- set_audio -------------------------------------------------------------------------------

    @Test
    fun `set_audio is the mute message`() {
        assertEquals(
            """{"type":"set_audio","enabled":false}""",
            PcLinkProtocol.setAudioLine(false).trim()
        )
        assertEquals(
            """{"type":"set_audio","enabled":true}""",
            PcLinkProtocol.setAudioLine(true).trim()
        )
    }

    // --- framing ----------------------------------------------------------------------------------

    @Test
    fun `the audio flag is a payload type, not a video attribute`() {
        val audio = PcVideoFrame(PcLinkProtocol.FLAG_AUDIO, 0L, ByteArray(1920))
        assertTrue(audio.isAudio)
        val idr = PcVideoFrame(PcLinkProtocol.FLAG_IDR, 0L, ByteArray(4))
        assertFalse(idr.isAudio)
        assertTrue(idr.isIdr)
    }

    // --- the routing itself, end to end ---------------------------------------------------------

    @Test
    fun `audio frames reach the audio path and never the video decoder`() {
        val pc = start(ScriptedPc(audio = """{"codec":"pcm_s16le","rate":48000,"channels":2}"""))
        val listener = RecordingListener(expectVideo = 2, expectAudio = 2)
        val client = client(pc, listener)
        client.connect(scope)

        assertTrue("no video preamble arrived", pc.preamble.await(15, TimeUnit.SECONDS))
        pc.writeFrame(PcLinkProtocol.FLAG_IDR or PcLinkProtocol.FLAG_CODEC_CONFIG, 1_000L, videoAu(1))
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 2_000L, pcm(0x11))
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 12_000L, pcm(0x22))
        pc.writeFrame(0, 3_000L, videoAu(2))

        assertTrue("video never arrived", listener.video.await(15, TimeUnit.SECONDS))
        assertTrue("audio never arrived", listener.audio.await(15, TimeUnit.SECONDS))
        client.close()

        // Video got exactly the two access units, in order, with nothing spliced in.
        assertEquals(listOf(1_000L, 3_000L), listener.videoPts())
        assertTrue(
            "an audio payload reached the video decoder",
            listener.videoFrames.none { it.isAudio }
        )
        // Audio got exactly the two chunks, with the server's own pts and payload intact.
        assertEquals(listOf(2_000L, 12_000L), listener.audioPts())
        assertArrayEquals(pcm(0x11), listener.audioPayloads[0])
        assertEquals(2, client.audioChunks.get())
        assertEquals(0, client.audioDropped.get())
        // …and the frame counter still counts frames of video, so the fps readout is unaffected.
        assertEquals(2, client.videoFrames.get())
    }

    @Test
    fun `audio frames under no negotiated format are dropped, not delivered`() {
        // The benign race of §2.2: chunks already in flight when a `config` without `audio`
        // lands. They must be discarded silently — never played, never decoded, never a resync.
        val pc = start(ScriptedPc(audio = null))
        val listener = RecordingListener(expectVideo = 1, expectAudio = 1)
        val client = client(pc, listener)
        client.connect(scope)

        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 2_000L, pcm(0x33))
        pc.writeFrame(PcLinkProtocol.FLAG_IDR, 3_000L, videoAu(1))

        assertTrue("video never arrived", listener.video.await(15, TimeUnit.SECONDS))
        assertFalse("audio was delivered with no config.audio", listener.audio.await(1, TimeUnit.SECONDS))
        client.close()

        assertEquals(1, client.audioDropped.get())
        assertEquals(0, client.audioChunks.get())
        assertEquals(0, listener.audioPayloads.size)
        // The parser never resynced: content validation is not corruption.
        assertEquals(0, client.resyncBytes.get())
    }

    @Test
    fun `a chunk that is not whole sample-frames is dropped`() {
        val pc = start(ScriptedPc(audio = """{"codec":"pcm_s16le","rate":48000,"channels":2}"""))
        val listener = RecordingListener(expectVideo = 1, expectAudio = 1)
        val client = client(pc, listener)
        client.connect(scope)

        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))
        // 1919 bytes is not a whole number of 4-byte stereo sample-frames.
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 2_000L, ByteArray(1919))
        // …and 101 ms exceeds the per-frame ceiling.
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 3_000L, ByteArray(101 * 48 * 4))
        pc.writeFrame(PcLinkProtocol.FLAG_IDR, 4_000L, videoAu(1))

        assertTrue(listener.video.await(15, TimeUnit.SECONDS))
        assertFalse(listener.audio.await(1, TimeUnit.SECONDS))
        client.close()

        assertEquals(2, client.audioDropped.get())
        assertEquals(0, client.resyncBytes.get())
    }

    @Test
    fun `muting sends set_audio and unmuting sends it back`() {
        val pc = start(ScriptedPc(audio = """{"codec":"pcm_s16le","rate":48000,"channels":2}"""))
        val listener = RecordingListener(expectVideo = 1, expectAudio = 1)
        val client = client(pc, listener)
        client.connect(scope)
        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))

        // `hello` already asked for audio, so an unmuted session says nothing more.
        Thread.sleep(500)
        assertEquals(emptyList<Boolean>(), pc.setAudio())

        client.setAudioEnabled(false)
        assertTrue("mute never reached the PC", pc.awaitSetAudio(1))
        client.setAudioEnabled(true)
        assertTrue("unmute never reached the PC", pc.awaitSetAudio(2))
        client.close()

        assertEquals(listOf(false, true), pc.setAudio())
    }

    // --- audio the session gains after it started -----------------------------------------------

    /**
     * The probe must never be able to opt a session out of sound.
     *
     * The owner's sequence: unplug the glasses, plug them back in, connect. The output route is
     * still switching while `hello` is being built — and a `hello` without an `audio` object tells
     * the server "this client predates audio", which it honours for the life of the session. The
     * probe is allowed to refine what we ask for; it is not allowed to answer "nothing".
     *
     * (Under the stubbed android.jar every static `AudioTrack` call throws, so this test *is* the
     * failing-probe case rather than a simulation of one.)
     */
    @Test
    fun `a phone whose audio probe tells it nothing still offers what every PC can send`() {
        val probed = PcLinkClient.deviceAudio()
        assertEquals(listOf("pcm_s16le"), probed.codecs)
        assertEquals(listOf(48_000), probed.rates)
        assertEquals(2, probed.channels)
    }

    @Test
    fun `set_audio can carry a late offer, and says nothing extra without one`() {
        assertEquals(
            """{"type":"set_audio","enabled":true}""",
            PcLinkProtocol.setAudioLine(true).trim()
        )
        val offer = JSONObject(PcLinkProtocol.setAudioLine(true, capability).trim())
        assertTrue(offer.getBoolean("enabled"))
        val audio = offer.getJSONObject("audio")
        assertEquals("pcm_s16le", audio.getJSONArray("codecs").getString(0))
        assertEquals(48_000, audio.getJSONArray("rates").getInt(0))
        assertEquals(2, audio.getInt("channels"))
    }

    /**
     * **The field-bug regression (client side).** A session that started video-only must be able
     * to gain audio without being torn down.
     *
     * End to end against a scripted PC: `hello` is answered with a `config` that has no `audio`
     * (the route was not ready, or the PC's capture was not), the client's route settles, it
     * re-offers, the PC answers with a `config` that has `audio` — and the chunks that follow
     * reach the audio path on the connection that was already open. Before the fix the re-offer
     * had nowhere to go and every one of those chunks was dropped as "no negotiated format".
     */
    @Test
    fun `a session that started without audio gains it when the route settles`() {
        val pc = start(ScriptedPc(audio = null))
        pc.honourLateOffer = """{"codec":"pcm_s16le","rate":48000,"channels":2}"""
        val listener = RecordingListener(expectVideo = 1, expectAudio = 2)
        val client = client(pc, listener)
        client.connect(scope)
        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))

        // Video-only to start with: an audio chunk now would be dropped, correctly.
        pc.writeFrame(0, 0L, videoAu(1))
        assertTrue(listener.video.await(15, TimeUnit.SECONDS))
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 1_000L, pcm(9))
        Thread.sleep(300)
        assertEquals(0, listener.audioPts().size)

        // The glasses are plugged in and the route appears.
        client.reofferAudio()
        assertTrue("the late offer never reached the PC", pc.awaitSetAudio(1))
        val offered = pc.setAudioMessages().first().optJSONObject("audio")
        assertNotNull("set_audio must carry what this phone can play now", offered)
        assertEquals(48_000, offered!!.getJSONArray("rates").getInt(0))

        // The PC answers with a config that has audio, and the sound flows on the same sockets.
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 10_000L, pcm(1))
        pc.writeFrame(PcLinkProtocol.FLAG_AUDIO, 20_000L, pcm(2))
        assertTrue("audio never arrived after the late offer", listener.audio.await(15, TimeUnit.SECONDS))
        assertEquals(listOf(10_000L, 20_000L), listener.audioPts())
        client.close()
    }

    /** A phone with genuinely no output path never sends one, and never gets a 0x04 frame. */
    @Test
    fun `a client with no capability at all makes no late offer`() {
        val pc = start(ScriptedPc(audio = null))
        pc.honourLateOffer = """{"codec":"pcm_s16le","rate":48000,"channels":2}"""
        val listener = RecordingListener(expectVideo = 1, expectAudio = 1)
        val client = PcLinkClient(
            context = null,
            host = "127.0.0.1",
            controlPort = pc.controlPort,
            videoPort = pc.videoPort,
            listener = listener,
            clientName = "Pixel 9 Pro",
            codecs = codecs,
            nowMs = { System.nanoTime() / 1_000_000 },
            audioCapability = null,
            authProvider = { null }
        )
        client.connect(scope)
        assertTrue(pc.preamble.await(15, TimeUnit.SECONDS))

        client.reofferAudio()
        Thread.sleep(500)
        assertEquals(emptyList<JSONObject>(), pc.setAudioMessages())
        client.close()
    }

    // --- harness ------------------------------------------------------------------------------

    private fun configJson(audio: String?): String = buildString {
        append("""{"type":"config","mime":"video/hevc","width":3840,"height":1080,"fps":60.0,""")
        append(""""stereo":"mono","videoToken":"$token"""")
        if (audio != null) append(""","audio":$audio""")
        append("}")
    }

    private fun videoAu(marker: Int) = byteArrayOf(0, 0, 0, 1, 0x40, marker.toByte())

    /** 10 ms of stereo 48 kHz PCM, filled with [marker] so the payload can be identified. */
    private fun pcm(marker: Int) = ByteArray(1920) { marker.toByte() }

    private fun start(scripted: ScriptedPc): ScriptedPc {
        pc = scripted
        scripted.start()
        return scripted
    }

    private fun client(pc: ScriptedPc, listener: PcLinkClient.Listener) = PcLinkClient(
        context = null,
        host = "127.0.0.1",
        controlPort = pc.controlPort,
        videoPort = pc.videoPort,
        listener = listener,
        clientName = "Pixel 9 Pro",
        codecs = codecs,
        // SystemClock throws against the stubbed android.jar.
        nowMs = { System.nanoTime() / 1_000_000 },
        audioCapability = capability,
        authProvider = { null }
    )

    private class RecordingListener(
        expectVideo: Int,
        expectAudio: Int
    ) : PcLinkClient.Listener {
        val videoFrames: MutableList<PcVideoFrame> = Collections.synchronizedList(ArrayList())
        val audioPayloads: MutableList<ByteArray> = Collections.synchronizedList(ArrayList())
        val audioTimes: MutableList<Long> = Collections.synchronizedList(ArrayList())
        val video = CountDownLatch(expectVideo)
        val audio = CountDownLatch(expectAudio)

        override fun onState(state: PcLinkState) = Unit
        override fun onConfig(config: PcLinkStreamConfig) = Unit

        override fun onVideoFrame(frame: PcVideoFrame) {
            videoFrames += frame
            video.countDown()
        }

        override fun onAudioChunk(ptsUs: Long, payload: ByteArray) {
            audioTimes += ptsUs
            audioPayloads += payload
            audio.countDown()
        }

        fun videoPts(): List<Long> = ArrayList(videoFrames).map { it.ptsUs }
        fun audioPts(): List<Long> = ArrayList(audioTimes)
    }

    /**
     * A PC that answers `hello` with one `config` and then writes whatever frames the test asks
     * for down the video socket. Unauthenticated (M1) flow: the token lives in `config`.
     */
    private inner class ScriptedPc(private val audio: String?) {
        private val controlServer = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        private val videoServer = ServerSocket(0, 4, InetAddress.getLoopbackAddress())

        val controlPort: Int get() = controlServer.localPort
        val videoPort: Int get() = videoServer.localPort
        val preamble = CountDownLatch(1)

        @Volatile private var videoOut: OutputStream? = null
        private val setAudioSeen = Collections.synchronizedList(ArrayList<Boolean>())
        private val setAudioLines = Collections.synchronizedList(ArrayList<JSONObject>())
        private val threads = ArrayList<Thread>()
        private val sockets = Collections.synchronizedList(ArrayList<Socket>())

        /**
         * Answer a `set_audio` that carries a late offer with a `config` that has audio — a
         * server with the §2.17 fix, as against one that ignores the object.
         */
        @Volatile var honourLateOffer: String? = null

        fun setAudio(): List<Boolean> = ArrayList(setAudioSeen)

        /** Every `set_audio` message as it arrived, so its `audio` object can be inspected. */
        fun setAudioMessages(): List<JSONObject> = ArrayList(setAudioLines)

        /** Waits for at least [count] `set_audio` messages to have arrived. */
        fun awaitSetAudio(count: Int): Boolean {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                if (setAudioSeen.size >= count) return true
                Thread.sleep(20)
            }
            return false
        }

        fun start() {
            threads += thread(name = "audio-pc-control") { acceptControl() }
            threads += thread(name = "audio-pc-video") { acceptVideo() }
        }

        fun shutdown() {
            runCatching { controlServer.close() }
            runCatching { videoServer.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            threads.forEach { it.interrupt() }
            threads.forEach { it.join(5_000) }
        }

        fun writeFrame(flags: Int, ptsUs: Long, payload: ByteArray) {
            val out = videoOut ?: error("video socket not connected yet")
            synchronized(this) {
                out.write(PcLinkProtocol.encodeFrame(flags, ptsUs, payload))
                out.flush()
            }
        }

        private fun acceptControl() {
            while (true) {
                val socket = try { controlServer.accept() } catch (_: Exception) { return }
                sockets += socket
                try {
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    val writer = socket.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val message = JSONObject(line)
                        when (message.optString("type")) {
                            "hello" -> {
                                writer.write(configJson(audio) + "\n")
                                writer.flush()
                            }
                            "set_audio" -> {
                                setAudioSeen += message.optBoolean("enabled")
                                setAudioLines += message
                                // The fixed server: a late offer is negotiated against and
                                // answered with a fresh `config` carrying `audio`, on the same
                                // connection the client is already streaming on.
                                val late = honourLateOffer
                                if (late != null && message.optJSONObject("audio") != null) {
                                    writer.write(configJson(late) + "\n")
                                    writer.flush()
                                }
                            }
                            else -> Unit
                        }
                    }
                } catch (_: Exception) {
                    // The client closing at the end of a test lands here.
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
                videoOut = socket.getOutputStream()
                preamble.countDown()
                Thread.sleep(15_000)
            } catch (_: Exception) {
                // Interrupted by shutdown(): the test already has what it needed.
            } finally {
                runCatching { socket.close() }
            }
        }
    }
}
