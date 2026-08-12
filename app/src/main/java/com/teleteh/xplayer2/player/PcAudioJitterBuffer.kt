package com.teleteh.xplayer2.player

import com.teleteh.xplayer2.data.network.PcAudioFormat
import com.teleteh.xplayer2.data.network.PcLinkProtocol
import kotlin.math.abs

/** What one [PcAudioJitterBuffer.pull] produced. Every outcome hands the sink a full chunk. */
enum class PcAudioPull {
    /** Real PCM from the stream. */
    AUDIO,

    /** Filling up to the prebuffer depth — the stream hasn't started (or has just restarted). */
    PREBUFFER,

    /** The stream ran dry. Silence keeps the sink paced while we wait for it to refill. */
    UNDERRUN,

    /** A drift correction: 10 ms of silence inserted because playback is running ahead. */
    DRIFT_INSERT;

    val isAudio: Boolean get() = this == AUDIO
}

/**
 * The PC Link audio jitter buffer and drift corrector — the receive-side half of
 * `xplayer-link-server/docs/audio-design.md` §8, written with no audio APIs in it at all so both
 * clients can run the identical state machine and unit-test it (the iOS twin is
 * `PcAudioJitterBuffer.swift`; keep the two in step).
 *
 * The contract, straight from the design:
 *
 * * chunks arrive from the network with `pts_us` on the **server's** clock (the same one video
 *   frames carry — that shared clock is the entire A/V sync mechanism);
 * * the consumer calls [pull] once per chunk period whenever its sink is ready for more, so the
 *   pull rate *is* the DAC's rate. Playback time is therefore counted in chunks pulled, which is
 *   why nothing here needs a wall clock — and why the tests are deterministic;
 * * the difference between the arrival rate and the pull rate is exactly the clock drift between
 *   the PC and the phone, and it shows up as the buffer's depth wandering. Correcting it is a
 *   matter of dropping or inserting one chunk once the wander is real rather than jitter.
 *
 * Numbers (all overridable, all defaulted to the design's): target depth 60 ms, start playing at
 * 40 ms, correct by one 10 ms chunk when a 1 s EMA of the depth sits outside ±20 ms of the target
 * for 2 s, hard-resync above 250 ms, and treat a pts gap of two chunk durations or more as a
 * discontinuity rather than as drift (§3.3 — mute and capture restarts produce exactly that).
 *
 * Thread-safe: [push] runs on the network reader, [pull] on the sink's feeder thread.
 */
class PcAudioJitterBuffer(
    val format: PcAudioFormat,
    /** Playback granularity. The server SHOULD send exactly this, but nothing here assumes it. */
    val chunkMs: Int = DEFAULT_CHUNK_MS,
    private val targetMs: Int = DEFAULT_TARGET_MS,
    private val prebufferMs: Int = DEFAULT_PREBUFFER_MS,
    private val toleranceMs: Int = DEFAULT_TOLERANCE_MS,
    private val driftWindowMs: Int = DEFAULT_DRIFT_WINDOW_MS,
    private val hardResyncMs: Int = DEFAULT_HARD_RESYNC_MS,
    private val emaWindowMs: Int = DEFAULT_EMA_WINDOW_MS,
    /** Absolute ceiling on what may sit here across runs, so a wedged sink can't grow the heap. */
    private val maxQueueMs: Int = DEFAULT_MAX_QUEUE_MS
) {

    /** One queued piece of the stream: a payload, how much of it is left, and where it belongs. */
    private class Segment(
        val data: ByteArray,
        var offset: Int,
        /** Presentation time of the first sample still in [data] at [offset]. */
        var ptsUs: Long,
        /** True on the first chunk of an uninterrupted run — i.e. right after a discontinuity. */
        var startsRun: Boolean
    ) {
        val remaining: Int get() = data.size - offset
    }

    private val lock = Any()
    private val queue = ArrayDeque<Segment>()

    /** Bytes of PCM one [pull] hands the sink. */
    val chunkBytes: Int = format.bytesForMs(chunkMs).coerceAtLeast(format.bytesPerFrame)

    private val chunkUs: Long = chunkMs * 1000L
    private val maxQueueBytes: Int = format.bytesForMs(maxQueueMs).coerceAtLeast(chunkBytes)

    /** EMA weight for one chunk, so the average has roughly [emaWindowMs] of memory. */
    private val emaAlpha: Double = (chunkMs.toDouble() / emaWindowMs.toDouble()).coerceIn(0.001, 1.0)

    private var playing = false
    private var queuedBytes = 0

    /** Where the next contiguous chunk would start; a chunk landing elsewhere is a discontinuity. */
    private var expectedPtsUs: Long? = null

    // Drift measurement, all in played-audio milliseconds.
    private var playedMs = 0L
    private var depthEmaMs = 0.0
    private var outOfBandSinceMs = -1L
    private var pendingCorrection = Correction.NONE

    private enum class Correction { NONE, DROP, INSERT }

    // --- counters, for the debug overlay -----------------------------------------------------

    @Volatile
    var chunksPlayed: Long = 0L
        private set

    @Volatile
    var underruns: Long = 0L
        private set

    @Volatile
    var driftDrops: Long = 0L
        private set

    @Volatile
    var driftInserts: Long = 0L
        private set

    @Volatile
    var hardResyncs: Long = 0L
        private set

    @Volatile
    var discontinuities: Long = 0L
        private set

    /** Chunks refused by content validation (§3.3): not whole sample-frames, or over 100 ms. */
    @Volatile
    var malformedChunks: Long = 0L
        private set

    /** Chunks evicted because the sink stopped consuming and the queue hit its ceiling. */
    @Volatile
    var overflowDrops: Long = 0L
        private set

    /** Presentation time of the last sample handed to the sink, or 0 before the first one. */
    @Volatile
    var lastPlayedPtsUs: Long = 0L
        private set

    /** How much of the current run is buffered right now, in milliseconds. */
    val bufferedMs: Int get() = synchronized(lock) { format.msForBytes(currentRunBytes()) }

    /** The smoothed depth the drift corrector is actually acting on. */
    val smoothedDepthMs: Double get() = synchronized(lock) { depthEmaMs }

    /** True once enough has arrived to be playing rather than filling. */
    val isPlaying: Boolean get() = synchronized(lock) { playing }

    // --- producer ------------------------------------------------------------------------------

    /**
     * Queues one chunk from the wire. Returns false when content validation refused it (§3.3:
     * discard the chunk, never resync — the framing was valid).
     */
    fun push(ptsUs: Long, payload: ByteArray): Boolean = synchronized(lock) {
        val bytesPerFrame = format.bytesPerFrame
        if (payload.isEmpty() || bytesPerFrame <= 0 || payload.size % bytesPerFrame != 0 ||
            format.msForBytes(payload.size) > PcLinkProtocol.MAX_AUDIO_CHUNK_MS
        ) {
            malformedChunks++
            return false
        }

        // A gap of two chunk durations or more is a discontinuity, not drift: mute, a capture
        // restart and a device switch all surface as exactly this, and §3.3 requires us to
        // rebuild the buffer rather than try to "catch up" to a timeline that moved.
        val expected = expectedPtsUs
        val startsRun = expected == null || abs(ptsUs - expected) >= 2 * chunkUs
        if (startsRun && expected != null) discontinuities++
        expectedPtsUs = ptsUs + durationUs(payload.size)

        queue.addLast(Segment(payload, 0, ptsUs, startsRun))
        queuedBytes += payload.size

        // Too deep to be jitter: the app was backgrounded, or the link burst. Throw away
        // everything but what just arrived and rebuild to the target from there.
        if (format.msForBytes(currentRunBytes()) > hardResyncMs) {
            val newest = queue.removeLast()
            queue.clear()
            newest.startsRun = true
            queue.addLast(newest)
            queuedBytes = newest.remaining
            hardResyncs++
            restartPrebuffer()
        }

        // Backstop for a sink that stopped pulling entirely (nobody should hit this: the hard
        // resync above bounds the live run, this bounds the sum of stale ones).
        while (queuedBytes > maxQueueBytes && queue.size > 1) {
            val head = queue.removeFirst()
            queuedBytes -= head.remaining
            overflowDrops++
        }
        true
    }

    /** Drops everything queued and goes back to prebuffering. For a format change or a teardown. */
    fun reset() = synchronized(lock) {
        queue.clear()
        queuedBytes = 0
        expectedPtsUs = null
        restartPrebuffer()
    }

    // --- consumer ------------------------------------------------------------------------------

    /**
     * Fills [out] with exactly [chunkBytes] for the sink and says where they came from. Silence
     * outcomes zero-fill, so the caller always writes the same number of bytes — keeping the sink
     * paced through an underrun is what lets playback resume without rebuilding it.
     */
    fun pull(out: ByteArray): PcAudioPull = synchronized(lock) {
        require(out.size >= chunkBytes) { "output buffer smaller than one chunk" }

        // A correction decided at the end of the previous pull, applied here so that "drop" and
        // "insert" are both one clean chunk on a chunk boundary.
        when (pendingCorrection) {
            Correction.INSERT -> {
                pendingCorrection = Correction.NONE
                driftInserts++
                reseedDrift()
                return silence(out, PcAudioPull.DRIFT_INSERT)
            }
            Correction.DROP -> {
                pendingCorrection = Correction.NONE
                if (consume(null)) {
                    driftDrops++
                    reseedDrift()
                }
            }
            Correction.NONE -> Unit
        }

        // The previous run has drained; the next chunk belongs to a new timeline (§3.3). Only an
        // untouched segment can be a boundary — consume() clears the flag as it crosses it.
        val head = queue.firstOrNull()
        if (playing && head != null && head.startsRun) {
            head.startsRun = false
            restartPrebuffer()
        }

        if (!playing) {
            if (format.msForBytes(currentRunBytes()) < prebufferMs) {
                return silence(out, PcAudioPull.PREBUFFER)
            }
            playing = true
            reseedDrift()
        }

        if (currentRunBytes() <= 0) {
            // Keep the sink alive and go back to filling: "resume when the buffer refills" means
            // refilled to the prebuffer depth, or the next pull would just underrun again.
            underruns++
            restartPrebuffer()
            return silence(out, PcAudioPull.UNDERRUN)
        }

        consume(out)
        chunksPlayed++
        measureDrift()
        playedMs += chunkMs
        return PcAudioPull.AUDIO
    }

    // --- internals -----------------------------------------------------------------------------

    /**
     * Takes one chunk out of the queue, into [out] or into the void (the drift drop). Stops at a
     * run boundary and zero-pads the rest — a partial chunk there is the run's own tail, and
     * splicing the next timeline onto it is exactly what the discontinuity rule forbids.
     *
     * Returns false when there was nothing to take.
     */
    private fun consume(out: ByteArray?): Boolean {
        var written = 0
        var took = false
        while (written < chunkBytes) {
            val head = queue.firstOrNull() ?: break
            if (head.startsRun && written > 0) break
            val n = minOf(head.remaining, chunkBytes - written)
            if (out != null) System.arraycopy(head.data, head.offset, out, written, n)
            if (!took) {
                lastPlayedPtsUs = head.ptsUs
                took = true
            }
            // The boundary this segment marked has now been crossed. Leaving the flag on a
            // half-played segment would make the next pull mistake it for a fresh discontinuity
            // and prebuffer in the middle of a run.
            head.startsRun = false
            head.offset += n
            head.ptsUs += durationUs(n)
            queuedBytes -= n
            written += n
            if (head.remaining == 0) queue.removeFirst()
        }
        if (out != null && written < chunkBytes) out.fill(0.toByte(), written, chunkBytes)
        return took
    }

    private fun silence(out: ByteArray, reason: PcAudioPull): PcAudioPull {
        out.fill(0.toByte(), 0, chunkBytes)
        playedMs += chunkMs
        return reason
    }

    /**
     * Feeds the depth EMA and arms a correction once the average has sat outside the target band
     * long enough to be drift rather than a burst. One chunk per decision, then re-measure —
     * ±100 ppm of clock error is ~6 ms/min, so this fires roughly once every 100 s and is
     * inaudible.
     */
    private fun measureDrift() {
        val depth = format.msForBytes(currentRunBytes()).toDouble()
        depthEmaMs += emaAlpha * (depth - depthEmaMs)
        if (abs(depthEmaMs - targetMs) > toleranceMs) {
            if (outOfBandSinceMs < 0) {
                outOfBandSinceMs = playedMs
            } else if (playedMs - outOfBandSinceMs >= driftWindowMs) {
                pendingCorrection = if (depthEmaMs > targetMs) Correction.DROP else Correction.INSERT
                outOfBandSinceMs = -1L
            }
        } else {
            outOfBandSinceMs = -1L
        }
    }

    /** Restarts the fill phase, which also throws away a drift measurement that no longer applies. */
    private fun restartPrebuffer() {
        playing = false
        reseedDrift()
    }

    /** Re-anchors the average on the depth as it is now, so one correction can't cascade. */
    private fun reseedDrift() {
        depthEmaMs = format.msForBytes(currentRunBytes()).toDouble()
        outOfBandSinceMs = -1L
        pendingCorrection = Correction.NONE
    }

    /**
     * Bytes of the run at the head of the queue — the only ones playable without crossing a
     * discontinuity, and therefore the only ones that count as depth.
     *
     * A linear scan of a queue that holds at most a few dozen chunks (the hard resync bounds it),
     * run once per 10 ms chunk: cheaper than the bookkeeping that would avoid it.
     */
    private fun currentRunBytes(): Int {
        var total = 0
        var first = true
        for (segment in queue) {
            if (!first && segment.startsRun) break
            total += segment.remaining
            first = false
        }
        return total
    }

    private fun durationUs(bytes: Int): Long =
        bytes.toLong() * 1_000_000L / format.bytesPerSecond.coerceAtLeast(1)

    companion object {
        const val DEFAULT_CHUNK_MS = 10
        const val DEFAULT_TARGET_MS = 60
        const val DEFAULT_PREBUFFER_MS = 40
        const val DEFAULT_TOLERANCE_MS = 20
        const val DEFAULT_DRIFT_WINDOW_MS = 2000
        const val DEFAULT_HARD_RESYNC_MS = 250
        const val DEFAULT_EMA_WINDOW_MS = 1000
        const val DEFAULT_MAX_QUEUE_MS = 2000
    }
}
