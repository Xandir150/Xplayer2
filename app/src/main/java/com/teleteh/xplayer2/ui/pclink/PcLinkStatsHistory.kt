package com.teleteh.xplayer2.ui.pclink

import com.teleteh.xplayer2.player.PcLinkSession

/**
 * The last minute of the two numbers the PC-Mirror chips draw.
 *
 * The session keeps no history of its own — it answers with cumulative counters and forgets the
 * question — so this is the only place the last minute exists. It is also the only place the two
 * *rates* exist: frames and bitrate are per-second quantities, and a per-second quantity needs two
 * readings and the interval between them, which belongs to whoever is sampling.
 *
 * **Sampled on the caller's own clock**, once a second, rather than off a change notification. A
 * desktop nobody is touching sends no frames for minutes at a time; a subscription would stop
 * drawing exactly when zero is the answer the user came to read.
 *
 * Pure — [sample] is handed the reading and the timestamp — so the axis rules in [SparklineWindow]
 * and the session-boundary rules here can both be tested without an Android runtime.
 */
class PcLinkStatsHistory(capacity: Int = SparklineWindow.DEFAULT_CAPACITY) {

    /** Frames per second, one sample a second. */
    val fps = SparklineWindow(capacity)

    /** Megabits per second off the video socket, one sample a second. */
    val mbps = SparklineWindow(capacity)

    private var lastSessionId: Long? = null
    private var lastAtMs = 0L
    private var lastFrames = 0L
    private var lastBytes = 0L

    /**
     * Takes the numbers as they stand at [atMs] (a monotonic clock — `SystemClock.elapsedRealtime`
     * in the app, a plain counter in tests).
     *
     * A null [stats] means the session ended: everything collected describes something that is no
     * longer running, and a chart that spliced the next session onto it would draw a minute that
     * never happened. Same for a session id that changed — a different session at the same address
     * is still a different session.
     *
     * The first reading of a session yields no point: one counter is not a rate. From the second
     * onward each reading a second or so apart contributes one, including a reading identical to
     * the last, which is a zero and the whole reason for sampling on a clock. A reading that
     * arrives sooner than that contributes none — see the interval guard below.
     */
    fun sample(stats: PcLinkSession.Stats?, atMs: Long) {
        if (stats == null) {
            reset()
            return
        }
        if (stats.sessionId != lastSessionId) {
            // A new session starting where another left off: keep the reading as a baseline, but
            // draw nothing from it.
            reset()
            remember(stats, atMs)
            return
        }
        val elapsedMs = atMs - lastAtMs
        // A rate for a one-second slot needs about a second behind it. Readings do not only arrive
        // on the ticker: the remote re-reads the moment the sound switch is tapped, so it can show
        // what the tap did without waiting out the second — and one frame over 20 ms is "50 fps", a
        // number that never happened, drawn on the minute for a minute (SparklineWindow scales the
        // whole window to its own min and max, so one outlier flattens everything else).
        //
        // Dropped without touching the baseline, so the next scheduled reading still measures a
        // whole second from the last real one and the axis loses no slot. The caller loses nothing
        // either: it repaints from `latest()`, which is the last honest number rather than a
        // fabricated one.
        if (elapsedMs < MIN_SAMPLE_INTERVAL_MS) return
        val seconds = elapsedMs / 1000f
        // Floored at zero: a reconnect inside one session rebuilds the client and the decoder, and
        // their counters start again from zero. That is "nothing arrived", not a negative rate.
        val frames = (stats.framesRendered - lastFrames).coerceAtLeast(0L)
        val bytes = (stats.videoBytes - lastBytes).coerceAtLeast(0L)
        fps.push(frames / seconds)
        mbps.push(bytes * 8f / seconds / 1_000_000f)
        remember(stats, atMs)
    }

    /** Forgets the minute and the baseline both. */
    fun reset() {
        fps.clear()
        mbps.clear()
        lastSessionId = null
        lastAtMs = 0L
        lastFrames = 0L
        lastBytes = 0L
    }

    private fun remember(stats: PcLinkSession.Stats, atMs: Long) {
        lastSessionId = stats.sessionId
        lastAtMs = atMs
        lastFrames = stats.framesRendered
        lastBytes = stats.videoBytes
    }

    private companion object {
        /**
         * Half the callers' one-second cadence: comfortably rejects a reading taken because
         * something was tapped, and never a tick that ran late (`postDelayed` cannot run early).
         */
        const val MIN_SAMPLE_INTERVAL_MS = 500L
    }
}
