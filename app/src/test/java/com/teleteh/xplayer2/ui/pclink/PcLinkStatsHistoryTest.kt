package com.teleteh.xplayer2.ui.pclink

import com.teleteh.xplayer2.player.PcLinkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the chips remember, and — more to the point — when they must forget it. */
class PcLinkStatsHistoryTest {

    private val eps = 1e-3f

    private fun stats(
        sessionId: Long = 1L,
        frames: Long = 0L,
        bytes: Long = 0L
    ) = PcLinkSession.Stats(
        sessionId = sessionId,
        serverName = "PC",
        link = PcLinkSession.Link.STREAMING,
        framesRendered = frames,
        videoBytes = bytes,
        droppedFrames = 0L,
        rttMs = 4f,
        codec = "avc",
        width = 1920,
        height = 1080,
        stereo = "mono",
        audioRateHz = 48_000,
        audioChannels = 2,
        audioBufferedMs = 80,
        audioDropouts = 0L,
        audioSkewMs = 0L,
        audioToGlasses = true,
        audioAvailable = true
    )

    @Test
    fun `the first reading is a baseline, not a point`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 100, bytes = 1_000_000), atMs = 1_000)
        assertEquals(0, h.fps.size)
        assertEquals(0, h.mbps.size)
    }

    @Test
    fun `rates come from the counters and the caller's own interval`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 0, bytes = 0), atMs = 1_000)
        h.sample(stats(frames = 60, bytes = 1_000_000), atMs = 2_000)
        assertEquals(60f, h.fps.latest()!!, eps)
        assertEquals(8f, h.mbps.latest()!!, eps)

        // Half the gap, half the counters: the same rate.
        h.sample(stats(frames = 90, bytes = 1_500_000), atMs = 2_500)
        assertEquals(60f, h.fps.latest()!!, eps)
        assertEquals(8f, h.mbps.latest()!!, eps)
    }

    @Test
    fun `a still desktop samples zero rather than nothing`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 500, bytes = 9_000_000), atMs = 1_000)
        // Nothing arrived for three seconds — three zeros, which is the answer the user came for.
        repeat(3) { h.sample(stats(frames = 500, bytes = 9_000_000), atMs = 2_000L + it * 1_000L) }
        assertEquals(3, h.fps.size)
        assertTrue(h.fps.samples().all { it == 0f })
        assertTrue(h.mbps.samples().all { it == 0f })
    }

    @Test
    fun `the history is dropped when the session ends`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 0), atMs = 1_000)
        h.sample(stats(frames = 60), atMs = 2_000)
        assertEquals(1, h.fps.size)

        h.sample(null, atMs = 3_000)
        assertEquals(0, h.fps.size)
        assertEquals(0, h.mbps.size)
    }

    @Test
    fun `a new session starting where the last left off starts a new line`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(sessionId = 1, frames = 0), atMs = 1_000)
        h.sample(stats(sessionId = 1, frames = 60), atMs = 2_000)
        h.sample(stats(sessionId = 1, frames = 120), atMs = 3_000)
        assertEquals(2, h.fps.size)

        // Same PC, same address, a second session: what came before describes something else.
        h.sample(stats(sessionId = 2, frames = 0), atMs = 4_000)
        assertEquals(0, h.fps.size)
        h.sample(stats(sessionId = 2, frames = 30), atMs = 5_000)
        assertEquals(1, h.fps.size)
        assertEquals(30f, h.fps.latest()!!, eps)
    }

    @Test
    fun `a reconnect inside one session draws a zero, not a negative dip`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 3_000, bytes = 90_000_000), atMs = 1_000)
        // The link dropped and was rebuilt: a new decoder and a new client, counting from zero.
        h.sample(stats(frames = 0, bytes = 0), atMs = 2_000)
        assertEquals(0f, h.fps.latest()!!, eps)
        assertEquals(0f, h.mbps.latest()!!, eps)
        // …and the next reading is measured against the new counters, not the old ones.
        h.sample(stats(frames = 60, bytes = 1_000_000), atMs = 3_000)
        assertEquals(60f, h.fps.latest()!!, eps)
    }

    @Test
    fun `a reading that arrives with no time between it and the last is ignored`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 0), atMs = 1_000)
        h.sample(stats(frames = 60), atMs = 1_000)
        assertEquals(0, h.fps.size)
    }

    @Test
    fun `a reading taken because something was tapped is not a second's worth of rate`() {
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 0, bytes = 0), atMs = 1_000)
        h.sample(stats(frames = 60, bytes = 1_000_000), atMs = 2_000)
        assertEquals(60f, h.fps.latest()!!, eps)

        // The remote re-reads the instant the sound switch is tapped, 20 ms into the second. One
        // frame has arrived in that time, which is not "50 fps" — and a strip whose axis is one
        // second per slot has no slot to spend on 20 ms.
        h.sample(stats(frames = 61, bytes = 1_020_000), atMs = 2_020)
        assertEquals(1, h.fps.size)
        assertEquals(60f, h.fps.latest()!!, eps)

        // And the baseline is untouched, so the next scheduled reading still measures a whole
        // second from the last real one rather than the 980 ms left over.
        h.sample(stats(frames = 120, bytes = 2_000_000), atMs = 3_000)
        assertEquals(2, h.fps.size)
        assertEquals(60f, h.fps.latest()!!, eps)
        assertEquals(8f, h.mbps.latest()!!, eps)
    }

    @Test
    fun `a tick that ran late is still a tick`() {
        // `postDelayed` cannot fire early but is free to fire late; the guard must reject taps, not
        // a busy main thread.
        val h = PcLinkStatsHistory()
        h.sample(stats(frames = 0), atMs = 1_000)
        h.sample(stats(frames = 90), atMs = 2_500)
        assertEquals(1, h.fps.size)
        assertEquals(60f, h.fps.latest()!!, eps)
    }

    @Test
    fun `the window never outgrows a minute`() {
        val h = PcLinkStatsHistory()
        var frames = 0L
        for (t in 0..120) {
            h.sample(stats(frames = frames), atMs = t * 1_000L)
            frames += 60
        }
        assertEquals(SparklineWindow.DEFAULT_CAPACITY, h.fps.size)
        assertEquals(SparklineWindow.DEFAULT_CAPACITY, h.mbps.size)
    }
}
