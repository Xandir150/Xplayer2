package com.teleteh.xplayer2.data.depth

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Single-thread worker that owns a [DepthEstimator] and accepts video frames over a
 * latest-only slot. The slot is overwritten if a newer frame arrives while inference
 * is in flight — we never queue stale frames, the worker simply skips ahead to the most
 * recent picture. This gives async, never-blocking depth: the renderer keeps pushing
 * frames at full rate, the worker keeps up as best it can, and the latest depth is
 * always one frame behind real-time (typical ~25–35 ms on modern Android NPUs).
 *
 * Usage from the GL/main thread:
 *   - call [submit] with each new decoded frame (downsampled to the model's input size
 *     by the worker itself; the caller hands over RGBA pixels and the source resolution)
 *   - call [pollLatestDepth] right before drawing to fetch the freshest depth map
 *
 * Lifecycle:
 *   - [start] spawns the worker thread once the estimator has been [DepthEstimator.init]'d
 *   - [stop] signals the thread to exit, releases the estimator
 */
class DepthFrameWorker(
    private val estimator: DepthEstimator,
) {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    // Pending input slot — overwritten by newest frame, consumed by the worker.
    private var pendingPixels: IntArray? = null
    private var pendingWidth: Int = 0
    private var pendingHeight: Int = 0
    private var pendingTimestampNanos: Long = 0L

    // Latest result — read by the renderer when binding the depth texture.
    @Volatile var latestDepth: FloatArray? = null
        private set
    @Volatile var latestDepthTimestampNanos: Long = 0L
        private set

    fun start() {
        if (!estimator.isReady()) {
            Log.w(TAG, "Worker not started: estimator not initialized")
            return
        }
        if (running.compareAndSet(false, true)) {
            thread = Thread(::loop, "DepthFrameWorker").also { it.start() }
        }
    }

    /**
     * Stop the worker. Returns true when the worker thread actually exited; false when it is
     * still wedged inside an inference after the join timeout — in that case the caller MUST NOT
     * close the estimator (closing a TFLite interpreter mid-`run()` is native UB that can take the
     * GPU delegate down for the whole process); leak it instead.
     */
    fun stop(): Boolean {
        if (!running.compareAndSet(true, false)) return thread == null
        lock.withLock { condition.signalAll() }
        // Generous join: a slow CPU-fallback inference can exceed the old 500 ms on weak devices.
        try { thread?.join(3000) } catch (_: InterruptedException) { }
        val exited = thread?.isAlive != true
        if (exited) thread = null
        latestDepth = null
        latestDepthTimestampNanos = 0L
        return exited
    }

    /**
     * Hand a frame to the worker. Overrides whatever was pending. Frames are NOT copied
     * here — the caller is expected to either pass a fresh buffer each call or accept
     * that the buffer it passed last time may be read concurrently with the next
     * inference. To stay safe with ring buffers, use a fresh [IntArray] each call.
     */
    fun submit(pixels: IntArray, width: Int, height: Int, timestampNanos: Long) {
        if (!running.get()) return
        lock.withLock {
            pendingPixels = pixels
            pendingWidth = width
            pendingHeight = height
            pendingTimestampNanos = timestampNanos
            condition.signalAll()
        }
    }

    /** Returns the freshest depth map produced so far, or null if none yet. */
    fun pollLatestDepth(): FloatArray? = latestDepth

    private fun loop() {
        Log.i(TAG, "DepthFrameWorker started")
        while (running.get()) {
            val pixels: IntArray
            val w: Int
            val h: Int
            val ts: Long
            lock.withLock {
                while (running.get() && pendingPixels == null) {
                    try { condition.await() } catch (_: InterruptedException) { return }
                }
                if (!running.get()) return
                pixels = pendingPixels!!
                w = pendingWidth
                h = pendingHeight
                ts = pendingTimestampNanos
                pendingPixels = null
            }
            val depth = try {
                estimator.estimate(pixels, w, h)
            } catch (e: Throwable) {
                Log.e(TAG, "Inference threw", e)
                null
            }
            if (depth != null) {
                latestDepth = depth
                latestDepthTimestampNanos = ts
            }
        }
        Log.i(TAG, "DepthFrameWorker stopped (avg inference ${estimator.avgInferenceMs} ms)")
    }

    companion object {
        private const val TAG = "DepthFrameWorker"
    }
}
