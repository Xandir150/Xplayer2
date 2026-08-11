package com.teleteh.xplayer2.data.network

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure half of the PC Link client: the XPV1 frame parser, the NDJSON line
 * splitter, the control-message codec and the video-token hex decode.
 *
 * The frame bytes are assembled BY HAND from the byte table in `protocol.md` §3 (magic `58 50 56
 * 31`, flags u8, len u32 BE, pts_us u64 BE, then the payload) rather than through
 * [PcLinkProtocol.encodeFrame], so the tests check the wire format itself and not merely that our
 * encoder and decoder agree with each other. The parser's expected behaviour is that of the
 * reference decoder in `xpl-proto/src/framing.rs` — incremental, resync-by-rescanning, one-byte
 * skip on an oversized length, 8 MiB cap.
 */
class PcLinkClientTest {

    // --- helpers --------------------------------------------------------------------------------

    /** One frame, built byte by byte from the spec's table. */
    private fun frameBytes(flags: Int, ptsUs: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(17 + payload.size)
        out[0] = 0x58; out[1] = 0x50; out[2] = 0x56; out[3] = 0x31 // "XPV1"
        out[4] = flags.toByte()
        val len = payload.size
        out[5] = (len ushr 24).toByte()
        out[6] = (len ushr 16).toByte()
        out[7] = (len ushr 8).toByte()
        out[8] = len.toByte()
        for (i in 0 until 8) out[9 + i] = (ptsUs ushr (56 - 8 * i)).toByte()
        payload.copyInto(out, 17)
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    private fun sampleFrames(): List<PcVideoFrame> = listOf(
        PcVideoFrame(0x03, 0L, bytes(0, 0, 0, 1, 0x40, 1, 2, 3)),
        PcVideoFrame(0x00, 16_683L, bytes(0, 0, 0, 1, 0x02, 9, 8, 7, 6)),
        PcVideoFrame(0x00, 33_366L, ByteArray(0)),
        PcVideoFrame(0x01, 50_050L, ByteArray(5000) { 0xAB.toByte() })
    )

    private fun encodeAll(frames: List<PcVideoFrame>): ByteArray =
        concat(*frames.map { frameBytes(it.flags, it.ptsUs, it.payload) }.toTypedArray())

    private fun drain(parser: PcVideoFrameParser): List<PcVideoFrame> {
        val out = ArrayList<PcVideoFrame>()
        while (true) out += (parser.nextFrame() ?: return out)
    }

    // --- framing: the documented byte layout ----------------------------------------------------

    @Test
    fun `encodes the frame header exactly as the spec table says`() {
        val encoded = PcLinkProtocol.encodeFrame(
            flags = PcLinkProtocol.FLAG_IDR or PcLinkProtocol.FLAG_CODEC_CONFIG,
            ptsUs = 0x0102030405060708L,
            payload = bytes(0xAA, 0xBB)
        )

        assertArrayEquals(
            bytes(
                0x58, 0x50, 0x56, 0x31,                         // magic "XPV1"
                0x03,                                           // flags: IDR | CODEC_CONFIG
                0x00, 0x00, 0x00, 0x02,                         // len u32 BE
                0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // pts_us u64 BE
                0xAA, 0xBB
            ),
            encoded
        )
    }

    @Test
    fun `flag bits mean IDR and codec config`() {
        assertTrue(PcVideoFrame(0x01, 0, ByteArray(0)).isIdr)
        assertTrue(!PcVideoFrame(0x01, 0, ByteArray(0)).hasCodecConfig)
        assertTrue(PcVideoFrame(0x02, 0, ByteArray(0)).hasCodecConfig)
        assertTrue(!PcVideoFrame(0x02, 0, ByteArray(0)).isIdr)
        // Reserved bits are carried through untouched, never rejected.
        assertTrue(PcVideoFrame(0xFF, 0, ByteArray(0)).isIdr)
    }

    // --- framing: round trips ------------------------------------------------------------------

    @Test
    fun `round trips a whole stream fed in one chunk`() {
        val frames = sampleFrames()
        val parser = PcVideoFrameParser()

        parser.feed(encodeAll(frames))

        assertEquals(frames, drain(parser))
        assertEquals(0L, parser.skippedBytes)
        assertEquals(0, parser.pendingBytes)
    }

    @Test
    fun `round trips a stream fed one byte at a time`() {
        val frames = sampleFrames()
        val stream = encodeAll(frames)
        val parser = PcVideoFrameParser()
        val decoded = ArrayList<PcVideoFrame>()

        for (b in stream) {
            parser.feed(byteArrayOf(b))
            decoded += drain(parser)
        }

        assertEquals(frames, decoded)
        assertEquals(0L, parser.skippedBytes)
    }

    @Test
    fun `round trips a stream fed in ragged chunks`() {
        val frames = sampleFrames()
        val stream = encodeAll(frames)
        val parser = PcVideoFrameParser()
        val decoded = ArrayList<PcVideoFrame>()

        var off = 0
        var chunk = 1
        while (off < stream.size) {
            val end = minOf(off + chunk, stream.size)
            parser.feed(stream, off, end - off)
            decoded += drain(parser)
            off = end
            chunk = (chunk * 3 + 7) % 97 + 1
        }

        assertEquals(frames, decoded)
        assertEquals(0, parser.pendingBytes)
    }

    @Test
    fun `a zero length payload is a real frame not an incomplete one`() {
        val parser = PcVideoFrameParser()

        parser.feed(frameBytes(0, 42L, ByteArray(0)))

        val frame = parser.nextFrame()
        assertNotNull(frame)
        assertEquals(42L, frame!!.ptsUs)
        assertEquals(0, frame.payload.size)
    }

    // --- framing: resync ------------------------------------------------------------------------

    @Test
    fun `resyncs past a garbage prefix and counts what it skipped`() {
        val frames = sampleFrames()
        val garbage = bytes(0xDE, 0xAD, 0xBE, 0xEF, 0x58, 0x50, 0x00) // ends in a fake partial magic
        val parser = PcVideoFrameParser()

        parser.feed(concat(garbage, encodeAll(frames)))

        assertEquals(frames, drain(parser))
        assertEquals(garbage.size.toLong(), parser.skippedBytes)
    }

    @Test
    fun `keeps a partial magic split across chunks`() {
        val frames = sampleFrames()
        val parser = PcVideoFrameParser()

        parser.feed(bytes(0xFF, 0xFF, 0x58, 0x50, 0x56)) // "..XPV" — might still become a magic
        assertNull(parser.nextFrame())
        assertEquals(3, parser.pendingBytes)             // the 3 magic-prefix bytes are retained
        parser.feed(bytes(0x31))                         // completes "XPV1", header follows as garbage
        parser.feed(encodeAll(frames))

        // The fake header's len bytes come from the first real frame's magic (0x56 0x31 …), which
        // is far over the cap, so the parser drops that byte and rescans — every real frame lives.
        assertEquals(frames, drain(parser))
    }

    @Test
    fun `recovers the tail after a corrupted length`() {
        val frames = sampleFrames()
        val stream = encodeAll(frames)
        // Claim a 3-byte payload where the real one is 8: everything after slides out of sync.
        stream[5] = 0x00; stream[6] = 0x00; stream[7] = 0x00; stream[8] = 0x03
        val parser = PcVideoFrameParser()

        parser.feed(stream)
        val decoded = drain(parser)

        assertTrue("only got ${decoded.size} frames", decoded.size >= frames.size - 1)
        assertEquals(frames.subList(1, frames.size), decoded.subList(decoded.size - 3, decoded.size))
    }

    @Test
    fun `rejects an oversized length and recovers the following frames`() {
        val frames = sampleFrames()
        val hostile = ByteArray(17)
        bytes(0x58, 0x50, 0x56, 0x31).copyInto(hostile)
        val tooBig = PcLinkProtocol.MAX_PAYLOAD_LEN + 1
        hostile[5] = (tooBig ushr 24).toByte()
        hostile[6] = (tooBig ushr 16).toByte()
        hostile[7] = (tooBig ushr 8).toByte()
        hostile[8] = tooBig.toByte()
        val parser = PcVideoFrameParser()

        parser.feed(concat(hostile, encodeAll(frames)))

        assertEquals(frames, drain(parser))
        // Exactly the bogus header was skipped: one byte at the magic, then the rescan.
        assertEquals(17L, parser.skippedBytes)
    }

    @Test
    fun `a u32 max length never makes the parser buffer for it`() {
        val hostile = concat(
            bytes(0x58, 0x50, 0x56, 0x31, 0x00, 0xFF, 0xFF, 0xFF, 0xFF),
            ByteArray(8)
        )
        val parser = PcVideoFrameParser()

        parser.feed(hostile)

        assertNull(parser.nextFrame())
        assertEquals(17L, parser.skippedBytes)
        assertEquals(0, parser.pendingBytes)
    }

    @Test
    fun `the payload cap is configurable`() {
        val frame = PcVideoFrame(0, 7L, bytes(1, 2, 3, 4, 5))
        val encoded = frameBytes(frame.flags, frame.ptsUs, frame.payload)

        val tooSmall = PcVideoFrameParser(maxPayloadLen = 4)
        tooSmall.feed(encoded)
        assertNull(tooSmall.nextFrame())
        assertTrue(tooSmall.skippedBytes > 0)

        val exact = PcVideoFrameParser(maxPayloadLen = 5)
        exact.feed(encoded)
        assertEquals(frame, exact.nextFrame())
    }

    @Test
    fun `a payload containing the magic is not mistaken for a frame boundary`() {
        // The magic legitimately occurs inside Annex-B data; only framing decides where a frame is.
        val payload = concat(bytes(0x58, 0x50, 0x56, 0x31), ByteArray(12) { 0x11 })
        val frames = listOf(PcVideoFrame(0, 1L, payload), PcVideoFrame(1, 2L, bytes(9)))
        val parser = PcVideoFrameParser()

        parser.feed(encodeAll(frames))

        assertEquals(frames, drain(parser))
        assertEquals(0L, parser.skippedBytes)
    }

    // --- control channel: hello ------------------------------------------------------------------

    @Test
    fun `hello carries the documented field names and preference order`() {
        val line = PcLinkProtocol.helloLine(
            clientName = "Pixel 8",
            codecs = listOf(
                PcCodecCapability("video/hevc", 3840, 2160, 60),
                PcCodecCapability("video/avc", 1920, 1080, 30)
            )
        )

        assertTrue("must be one NDJSON line", line.endsWith("\n") && line.count { it == '\n' } == 1)
        val obj = JSONObject(line.trim())
        assertEquals("hello", obj.getString("type"))
        assertEquals("Pixel 8", obj.getString("clientName"))
        assertEquals(1, obj.getInt("protocolVersion"))
        val codecs = obj.getJSONArray("codecs")
        assertEquals(2, codecs.length())
        assertEquals("video/hevc", codecs.getJSONObject(0).getString("mime"))
        assertEquals(3840, codecs.getJSONObject(0).getInt("maxWidth"))
        assertEquals(2160, codecs.getJSONObject(0).getInt("maxHeight"))
        assertEquals(60, codecs.getJSONObject(0).getInt("maxFps"))
        assertEquals("video/avc", codecs.getJSONObject(1).getString("mime"))
        assertEquals(1920, codecs.getJSONObject(1).getInt("maxWidth"))
        assertEquals(1080, codecs.getJSONObject(1).getInt("maxHeight"))
        assertEquals(30, codecs.getJSONObject(1).getInt("maxFps"))
    }

    @Test
    fun `idr ping and pong are the documented one liners`() {
        assertEquals("""{"type":"idr"}""" + "\n", PcLinkProtocol.idrLine())
        assertEquals("""{"type":"ping","t_us":123456789}""" + "\n", PcLinkProtocol.pingLine(123456789L))
        assertEquals("""{"type":"pong","t_us":123456789}""" + "\n", PcLinkProtocol.pongLine(123456789L))
    }

    // --- control channel: parsing ----------------------------------------------------------------

    private val token = "9f1c6c0d0a4b2e7d55aa31c28e4dd0f4b6a3c1e8d9027f5a4c3b2a1908f7e6d5"

    private fun configLine(
        extra: String = "",
        stereo: String = "sbs",
        videoToken: String? = token
    ): String {
        val tokenPart = if (videoToken == null) "" else ""","videoToken":"$videoToken""""
        return """{"type":"config","mime":"video/hevc","width":3840,"height":1080,"fps":60.0,""" +
            """"stereo":"$stereo","canvasAngularWidthDeg":45.0,"canvasDistanceM":3.0$tokenPart$extra}"""
    }

    @Test
    fun `parses a config message`() {
        val msg = PcLinkProtocol.parseControlLine(configLine())

        val config = (msg as PcControlMessage.Config).config
        assertEquals("video/hevc", config.mime)
        assertEquals(3840, config.width)
        assertEquals(1080, config.height)
        assertEquals(60f, config.fps, 0.001f)
        assertEquals("sbs", config.stereo)
        assertTrue(config.isSbs)
        assertEquals(45f, config.canvasAngularWidthDeg, 0.001f)
        assertEquals(3f, config.canvasDistanceM, 0.001f)
        assertEquals(token, config.videoToken)
    }

    @Test
    fun `ignores unknown fields inside a known message`() {
        val msg = PcLinkProtocol.parseControlLine(configLine(extra = ""","hdr":true,"future":{"a":1}"""))

        val config = (msg as PcControlMessage.Config).config
        assertEquals("video/hevc", config.mime)
        assertEquals(token, config.videoToken)
    }

    @Test
    fun `ignores unknown message types`() {
        assertSame(
            PcControlMessage.Unknown,
            PcLinkProtocol.parseControlLine("""{"type":"cursor","x":1,"y":2}""")
        )
        assertSame(PcControlMessage.Unknown, PcLinkProtocol.parseControlLine("""{"noType":1}"""))
    }

    @Test
    fun `malformed json is a protocol error not an unknown message`() {
        assertNull(PcLinkProtocol.parseControlLine("""{"type":"config",,}"""))
        assertNull(PcLinkProtocol.parseControlLine("not json at all"))
    }

    @Test
    fun `a config without a usable video token is rejected`() {
        assertSame(PcControlMessage.Unknown, PcLinkProtocol.parseControlLine(configLine(videoToken = null)))
        assertSame(PcControlMessage.Unknown, PcLinkProtocol.parseControlLine(configLine(videoToken = "abcd")))
        assertSame(
            PcControlMessage.Unknown,
            PcLinkProtocol.parseControlLine(configLine(videoToken = token.dropLast(1) + "z"))
        )
    }

    @Test
    fun `a config with an unknown stereo mode or non finite number is rejected`() {
        assertSame(PcControlMessage.Unknown, PcLinkProtocol.parseControlLine(configLine(stereo = "anaglyph")))
        val nanFps = configLine().replace(""""fps":60.0""", """"fps":"NaN"""")
        assertSame(PcControlMessage.Unknown, PcLinkProtocol.parseControlLine(nanFps))
    }

    @Test
    fun `parses ping pong and windows`() {
        assertEquals(
            PcControlMessage.Ping(123456789L),
            PcLinkProtocol.parseControlLine("""{"type":"ping","t_us":123456789}""")
        )
        assertEquals(
            PcControlMessage.Pong(123456789L),
            PcLinkProtocol.parseControlLine("""{"type":"pong","t_us":123456789}""")
        )
        val windows = PcLinkProtocol.parseControlLine(
            """{"type":"windows","windows":[{"id":42,"title":"Terminal","x":-20,"y":64,"w":1280,"h":720,"depth":0.25}]}"""
        ) as PcControlMessage.Windows
        assertEquals(1, windows.windows.size)
        assertEquals(42L, windows.windows[0].id)
        assertEquals("Terminal", windows.windows[0].title)
        assertEquals(-20, windows.windows[0].x)
        assertEquals(0.25f, windows.windows[0].depth, 0.0001f)
    }

    // --- video token / preamble --------------------------------------------------------------------

    @Test
    fun `decodes a video token from hex`() {
        val decoded = PcLinkProtocol.decodeToken(token)

        assertNotNull(decoded)
        assertEquals(32, decoded!!.size)
        assertEquals(0x9f.toByte(), decoded[0])
        assertEquals(0x1c.toByte(), decoded[1])
        assertEquals(0xd5.toByte(), decoded[31])
    }

    @Test
    fun `accepts uppercase hex but not a wrong length or a non hex digit`() {
        assertArrayEquals(
            PcLinkProtocol.decodeToken(token),
            PcLinkProtocol.decodeToken(token.uppercase())
        )
        assertNull(PcLinkProtocol.decodeToken(""))
        assertNull(PcLinkProtocol.decodeToken(token.dropLast(1)))
        assertNull(PcLinkProtocol.decodeToken(token + "00"))
        assertNull(PcLinkProtocol.decodeToken(token.dropLast(1) + "g"))
    }

    @Test
    fun `builds the 36 byte video preamble`() {
        val preamble = PcLinkProtocol.videoPreamble(token)

        assertNotNull(preamble)
        assertEquals(36, preamble!!.size)
        assertArrayEquals(bytes(0x58, 0x50, 0x56, 0x54), preamble.copyOfRange(0, 4)) // "XPVT"
        assertArrayEquals(PcLinkProtocol.decodeToken(token), preamble.copyOfRange(4, 36))
        assertNull(PcLinkProtocol.videoPreamble("nope"))
    }

    // --- control line splitting ---------------------------------------------------------------------

    @Test
    fun `splits ndjson lines across chunk boundaries`() {
        val splitter = PcLinkLineSplitter()

        splitter.feed("""{"type":"idr"}""" + "\n" + """{"ty""")
        assertEquals("""{"type":"idr"}""", splitter.nextLine())
        assertNull(splitter.nextLine())
        splitter.feed("""pe":"ping","t_us":1}""" + "\n")
        assertEquals("""{"type":"ping","t_us":1}""", splitter.nextLine())
        assertNull(splitter.nextLine())
    }

    @Test
    fun `tolerates crlf terminators`() {
        val splitter = PcLinkLineSplitter()

        splitter.feed("""{"type":"idr"}""" + "\r\n")

        assertEquals("""{"type":"idr"}""", splitter.nextLine())
    }

    @Test
    fun `drops an over long line instead of buffering it`() {
        val splitter = PcLinkLineSplitter(maxLineLen = 16)

        splitter.feed("x".repeat(64) + "\n" + """{"type":"idr"}""" + "\n")

        assertEquals("""{"type":"idr"}""", splitter.nextLine())
        assertEquals(1L, splitter.droppedLines)
    }

    @Test
    fun `counts an over long line with no terminator in sight and resumes after it`() {
        val splitter = PcLinkLineSplitter(maxLineLen = 16)

        splitter.feed("y".repeat(40))
        assertNull(splitter.nextLine())
        assertEquals(1L, splitter.droppedLines)
        splitter.feed("still the same line\n" + """{"type":"idr"}""" + "\n")

        assertEquals("""{"type":"idr"}""", splitter.nextLine())
        assertEquals(1L, splitter.droppedLines)
    }

    private fun PcLinkLineSplitter.feed(text: String) =
        feed(text.toByteArray(Charsets.UTF_8))
}
