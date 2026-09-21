package com.teleteh.xplayer2.data.depth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
 *   - [start] spawns the worker thread and initializes the estimator on that thread
 *   - [stop] signals the thread to exit, releases the estimator
 */
class DepthFrameWorker internal constructor(
    private val initialize: () -> Boolean,
    private val infer: (IntArray, Int, Int) -> FloatArray?,
    private val release: () -> Unit,
    private val log: (String, Throwable?) -> Unit,
) {
    constructor(estimator: DepthEstimator, context: Context) : this(
        { estimator.init(context) },
        estimator::estimate,
        estimator::close,
        { message, error ->
            if (error == null) Log.i(TAG, message) else Log.e(TAG, message, error)
        },
    )
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

    /** Creates, runs and closes every delegate on this worker's thread. */
    suspend fun start(): Boolean = suspendCancellableCoroutine { continuation ->
        check(running.compareAndSet(false, true)) { "Worker already started" }
        continuation.invokeOnCancellation { requestStop() }
        thread = Thread({
            try {
                val ready = running.get() && initialize()
                continuation.resume(ready)
                if (ready && running.get()) loop()
            } catch (e: Throwable) {
                if (continuation.isActive) continuation.resume(false)
                log("Depth worker failed", e)
            } finally {
                running.set(false)
                release()
                lock.withLock { pendingPixels = null }
                latestDepth = null
                latestDepthTimestampNanos = 0L
            }
        }, "DepthFrameWorker").also { it.start() }
    }

    /** Non-blocking cancellation, including model initialization in progress. */
    fun requestStop() {
        running.set(false)
        lock.withLock {
            pendingPixels = null
            condition.signalAll()
        }
    }

    /** A stalled native call owns its delegate until it returns; never close it elsewhere. */
    fun stop(): Boolean {
        requestStop()
        try { thread?.join(3000) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return thread?.isAlive != true
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
            if (!running.get()) return
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
        log("DepthFrameWorker started", null)
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
                infer(pixels, w, h)
            } catch (e: Throwable) {
                log("Inference threw", e)
                null
            }
            if (depth != null) {
                latestDepth = depth
                latestDepthTimestampNanos = ts
            }
        }
        log("DepthFrameWorker stopped", null)
    }

    companion object {
        private const val TAG = "DepthFrameWorker"
    }
}
