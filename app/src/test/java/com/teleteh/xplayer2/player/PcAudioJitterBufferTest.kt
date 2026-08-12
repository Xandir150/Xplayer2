package com.teleteh.xplayer2.player

import com.teleteh.xplayer2.data.network.PcAudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jitter buffer and drift corrector of `audio-design.md` §8, pinned at its exact numbers:
 * 60 ms target, 40 ms prebuffer, ±20 ms band held for 2 s before a one-chunk correction, hard
 * resync above 250 ms, and a pts gap of two chunk durations as a discontinuity.
 *
 * Every test drives the buffer the way the real feeder thread does — one [PcAudioJitterBuffer.pull]
 * per chunk period — so "time" here is playback time and nothing needs a clock. The iOS twin
 * (`PcAudioJitterBufferTests.swift`) runs the same scenarios against the same numbers; a change to
 * one belongs in both.
 */
class PcAudioJitterBufferTest {

    private val format = PcAudioFormat("pcm_s16le", 48_000, 2)

    /** 10 ms at 48 kHz stereo s16 = 480 sample-frames = 1920 bytes. */
    private val chunkBytes = 1920

    private val chunkUs = 10_000L

    /** One chunk, filled with [marker] so a pulled chunk can be traced back to what pushed it. */
    private fun chunk(marker: Int) = ByteArray(chunkBytes) { marker.toByte() }

    private fun markerOf(data: ByteArray): Int = data[0].toInt() and 0xFF

    private fun buffer() = PcAudioJitterBuffer(format)

    /** Pushes [count] contiguous chunks starting at chunk index [from]. */
    private fun PcAudioJitterBuffer.pushRun(from: Int, count: Int) {
        for (i in from until from + count) push(i * chunkUs, chunk(i % 251))
    }

    // --- the shape of the thing ------------------------------------------------------------------

    @Test
    fun `one pull is exactly one chunk of the negotiated format`() {
        val buffer = buffer()
        assertEquals(chunkBytes, buffer.chunkBytes)
        assertEquals(10, buffer.chunkMs)
    }

    @Test
    fun `plays nothing until the prebuffer depth is reached`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        // 30 ms is short of the 40 ms prebuffer: silence, and nothing consumed.
        buffer.pushRun(from = 0, count = 3)
        assertEquals(PcAudioPull.PREBUFFER, buffer.pull(out))
        assertEquals(PcAudioPull.PREBUFFER, buffer.pull(out))
        assertEquals(30, buffer.bufferedMs)
        assertFalse(buffer.isPlaying)

        // The fourth chunk reaches 40 ms and playback starts, from the beginning of the stream.
        buffer.pushRun(from = 3, count = 1)
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
        assertTrue(buffer.isPlaying)
        assertEquals(0, markerOf(out))
        assertEquals(0, buffer.underruns)
    }

    // --- steady state ----------------------------------------------------------------------------

    @Test
    fun `a steady stream at the target depth plays through untouched`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        // Filled to the 60 ms target, then one chunk in per chunk out for 20 s of playback.
        buffer.pushRun(from = 0, count = 6)
        var next = 6
        for (i in 0 until 2000) {
            buffer.pushRun(from = next++, count = 1)
            assertEquals("pull $i", PcAudioPull.AUDIO, buffer.pull(out))
            assertEquals("chunk $i out of order", i % 251, markerOf(out))
        }

        assertEquals(2000, buffer.chunksPlayed)
        assertEquals(0, buffer.underruns)
        assertEquals(0, buffer.driftDrops)
        assertEquals(0, buffer.driftInserts)
        assertEquals(0, buffer.hardResyncs)
        assertEquals(0, buffer.discontinuities)
        // One in, one out holds the fill depth exactly, and 60 ms is the target — dead centre of
        // the band, so no correction ever fires.
        assertEquals(60, buffer.bufferedMs)
    }

    // --- underrun and a late burst -----------------------------------------------------------------

    @Test
    fun `an underrun outputs silence and playback restarts once the burst lands`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        buffer.pushRun(from = 0, count = 6)
        repeat(6) { assertEquals(PcAudioPull.AUDIO, buffer.pull(out)) }

        // The link stalls: the sink keeps asking, and keeps getting silence rather than a stall.
        assertEquals(PcAudioPull.UNDERRUN, buffer.pull(out))
        assertTrue(out.all { it.toInt() == 0 })
        repeat(20) { assertEquals(PcAudioPull.PREBUFFER, buffer.pull(out)) }
        assertEquals(1, buffer.underruns)

        // …then everything arrives at once. Under the 250 ms hard-resync ceiling, so it is kept
        // whole, and playback resumes from the first chunk of the burst.
        buffer.pushRun(from = 6, count = 12)
        assertEquals(0, buffer.hardResyncs)
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
        assertEquals(6, markerOf(out))
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
        assertEquals(7, markerOf(out))
    }

    @Test
    fun `a burst deeper than the hard-resync ceiling collapses to the newest chunk`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        // 300 ms in one go: the 26th chunk crosses 250 ms and everything older is thrown away.
        buffer.pushRun(from = 0, count = 30)
        assertEquals(1, buffer.hardResyncs)
        // The five chunks pushed after the resync are all that is left.
        assertEquals(50, buffer.bufferedMs)
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
        assertEquals(25, markerOf(out))
    }

    // --- discontinuity ------------------------------------------------------------------------------

    @Test
    fun `a pts gap of two chunks drains the old run and rebuilds the buffer`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        buffer.pushRun(from = 0, count = 6)
        repeat(3) { assertEquals(PcAudioPull.AUDIO, buffer.pull(out)) }

        // Half a second of nothing — a mute, a capture restart, a device switch. §3.3 says this
        // is a discontinuity, not something to be interpreted as drift.
        buffer.push(50 * chunkUs, chunk(200))
        assertEquals(1, buffer.discontinuities)
        assertEquals(0, buffer.hardResyncs)

        // What was already queued still plays out…
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out)); assertEquals(3, markerOf(out))
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out)); assertEquals(4, markerOf(out))
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out)); assertEquals(5, markerOf(out))

        // …and the new timeline starts a fresh prebuffer instead of being spliced on.
        assertEquals(PcAudioPull.PREBUFFER, buffer.pull(out))
        assertFalse(buffer.isPlaying)
        assertEquals(10, buffer.bufferedMs)

        buffer.push(51 * chunkUs, chunk(201))
        buffer.push(52 * chunkUs, chunk(202))
        buffer.push(53 * chunkUs, chunk(203))
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
        assertEquals(200, markerOf(out))
    }

    @Test
    fun `pts running backwards is a discontinuity too`() {
        val buffer = buffer()
        buffer.pushRun(from = 100, count = 4)
        buffer.push(0L, chunk(9))
        assertEquals(1, buffer.discontinuities)
    }

    @Test
    fun `a one-chunk pts wobble is not a discontinuity`() {
        val buffer = buffer()
        buffer.push(0L, chunk(0))
        // 19 999 µs where 10 000 was due: under two chunk durations, so it is drift, not a gap.
        buffer.push(19_999L, chunk(1))
        assertEquals(0, buffer.discontinuities)
    }

    // --- drift ----------------------------------------------------------------------------------

    @Test
    fun `a PC clock running fast drops one chunk at a time back toward the target`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        // Deliberately deep: 100 ms in, then one chunk in per chunk out, so the depth parks at
        // 90 ms — 30 ms above target, outside the ±20 ms band.
        buffer.pushRun(from = 0, count = 10)
        var next = 10
        repeat(100) {
            buffer.pushRun(from = next++, count = 1)
            buffer.pull(out)
        }
        // Nothing yet: the band has to be missed for a full 2 s before anything is corrected.
        assertEquals(0, buffer.driftDrops)

        repeat(900) {
            buffer.pushRun(from = next++, count = 1)
            buffer.pull(out)
        }
        assertTrue("expected a correction, got none", buffer.driftDrops >= 1)
        assertEquals("never insert while running deep", 0, buffer.driftInserts)
        assertEquals(0, buffer.underruns)
        assertTrue(
            "depth should have come back toward 60 ms, was ${buffer.bufferedMs}",
            buffer.bufferedMs in 60..80
        )
    }

    @Test
    fun `a PC clock running slow inserts silence rather than starving`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)

        // Filled just enough to start (the fourth chunk arrives with the first pull), so the
        // steady depth is 30 ms — 30 ms below target, outside the band.
        buffer.pushRun(from = 0, count = 3)
        var next = 3
        repeat(100) {
            buffer.pushRun(from = next++, count = 1)
            buffer.pull(out)
        }
        assertEquals(0, buffer.driftInserts)

        var inserts = 0
        repeat(900) {
            buffer.pushRun(from = next++, count = 1)
            if (buffer.pull(out) == PcAudioPull.DRIFT_INSERT) {
                inserts++
                // An insert is 10 ms of silence, not a repeat of the previous chunk.
                assertTrue(out.all { it.toInt() == 0 })
            }
        }
        assertTrue("expected an inserted chunk, got none", inserts >= 1)
        assertEquals(inserts.toLong(), buffer.driftInserts)
        assertEquals("never drop while running shallow", 0, buffer.driftDrops)
        assertEquals("inserting is what stops it starving", 0, buffer.underruns)
        assertTrue(
            "depth should have come up toward 60 ms, was ${buffer.bufferedMs}",
            buffer.bufferedMs in 40..60
        )
    }

    // --- content validation (§3.3) ---------------------------------------------------------------

    @Test
    fun `chunks that are not whole sample-frames are discarded`() {
        val buffer = buffer()
        assertFalse(buffer.push(0L, ByteArray(1919)))
        assertFalse(buffer.push(0L, ByteArray(0)))
        assertEquals(2, buffer.malformedChunks)
        assertEquals(0, buffer.bufferedMs)
    }

    @Test
    fun `chunks longer than 100 ms are discarded`() {
        val buffer = buffer()
        // 101 ms at 48 kHz stereo.
        assertFalse(buffer.push(0L, ByteArray(101 * 48 * 4)))
        assertEquals(1, buffer.malformedChunks)
        // Exactly 100 ms is legal, and the buffer re-chunks it into 10 ms pulls.
        assertTrue(buffer.push(0L, ByteArray(100 * 48 * 4)))
        assertEquals(100, buffer.bufferedMs)
    }

    @Test
    fun `oversized chunks are re-chunked into playback-sized pulls`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)
        // A server sending 40 ms frames is legal; playback granularity stays 10 ms.
        buffer.push(0L, ByteArray(40 * 48 * 4) { 7 })
        assertEquals(40, buffer.bufferedMs)
        repeat(4) {
            assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
            assertEquals(7, markerOf(out))
        }
        assertEquals(0, buffer.bufferedMs)
    }

    // --- the pts the sink is actually playing -----------------------------------------------------

    @Test
    fun `the played pts tracks the stream for the A-V skew readout`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)
        buffer.pushRun(from = 0, count = 6)

        buffer.pull(out)
        assertEquals(0L, buffer.lastPlayedPtsUs)
        buffer.pull(out)
        assertEquals(chunkUs, buffer.lastPlayedPtsUs)
        buffer.pull(out)
        assertEquals(2 * chunkUs, buffer.lastPlayedPtsUs)
    }

    @Test
    fun `reset drops everything and prebuffers again`() {
        val buffer = buffer()
        val out = ByteArray(buffer.chunkBytes)
        buffer.pushRun(from = 0, count = 6)
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))

        buffer.reset()
        assertEquals(0, buffer.bufferedMs)
        assertFalse(buffer.isPlaying)
        assertEquals(PcAudioPull.PREBUFFER, buffer.pull(out))

        // A fresh timeline after the reset is not counted as a discontinuity of the old one.
        val before = buffer.discontinuities
        buffer.pushRun(from = 900, count = 4)
        assertEquals(before, buffer.discontinuities)
        assertEquals(PcAudioPull.AUDIO, buffer.pull(out))
    }
}
