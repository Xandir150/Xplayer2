package com.teleteh.xplayer2.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.teleteh.xplayer2.data.network.PcVideoFrame
import java.util.ArrayDeque

/**
 * Annex-B helpers for the PC Link stream. Pure (no Android types) so the NAL walking is unit-
 * testable on the JVM.
 */
object PcAnnexB {

    /** Codec-specific data extracted from the leading parameter sets of an access unit. */
    class Csd(val csd0: ByteArray, val csd1: ByteArray?)

    /**
     * Pulls the codec-config bytes out of an access unit that carries them (the server sets
     * `CODEC_CONFIG` on the frame; §3 says the payload *starts* with VPS/SPS/PPS and then
     * continues into the IDR slices, so we stop at the first VCL NAL).
     *
     * MediaCodec's convention differs per codec:
     * * H.264 — `csd-0` = SPS, `csd-1` = PPS, each with its start code;
     * * H.265 — `csd-0` = VPS + SPS + PPS concatenated.
     *
     * Returns null when the unit contains no parameter sets at all (nothing to configure with).
     */
    fun extractCsd(au: ByteArray, mime: String): Csd? {
        val hevc = mime.equals("video/hevc", ignoreCase = true) ||
            mime.equals("video/dolby-vision", ignoreCase = true)
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        val vps = ArrayList<ByteArray>()
        for (nal in splitNals(au)) {
            if (nal.isEmpty()) continue
            val header = nal[startCodeLen(nal)].toInt() and 0xFF
            if (hevc) {
                when ((header shr 1) and 0x3F) {
                    32 -> vps += nal
                    33 -> sps += nal
                    34 -> pps += nal
                    // 35 = AUD, 39/40 = SEI: allowed to precede the parameter sets, skip them.
                    35, 39, 40 -> Unit
                    // Anything else at this point is a VCL NAL — the picture data starts here.
                    else -> return build(hevc, vps, sps, pps)
                }
            } else {
                when (header and 0x1F) {
                    7 -> sps += nal
                    8 -> pps += nal
                    6, 9 -> Unit // SEI / access-unit delimiter
                    else -> return build(hevc, vps, sps, pps)
                }
            }
        }
        return build(hevc, vps, sps, pps)
    }

    private fun build(
        hevc: Boolean,
        vps: List<ByteArray>,
        sps: List<ByteArray>,
        pps: List<ByteArray>
    ): Csd? {
        if (sps.isEmpty() && pps.isEmpty() && vps.isEmpty()) return null
        return if (hevc) {
            Csd(concat(vps + sps + pps), null)
        } else {
            Csd(concat(sps), if (pps.isEmpty()) null else concat(pps))
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    /** Splits an Annex-B buffer into NAL units, each still carrying its own start code. */
    fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) {
                    starts += i
                    i += 3
                    continue
                }
                if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    starts += i
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>(starts.size)
        for (n in starts.indices) {
            val from = starts[n]
            val to = if (n + 1 < starts.size) starts[n + 1] else data.size
            if (to > from) out += data.copyOfRange(from, to)
        }
        return out
    }

    /** Length of the start code (3 or 4) at the head of a NAL produced by [splitNals]. */
    fun startCodeLen(nal: ByteArray): Int =
        if (nal.size >= 4 && nal[2] == 0.toByte() && nal[3] == 1.toByte()) 4 else 3
}

/**
 * Input-side "keep up with the wire, drop is fine" policy for the decoder, extracted as a pure
 * state machine so it can be unit-tested without a MediaCodec.
 *
 * The server paces the stream, so a decoder that falls behind must not build a queue — that queue
 * IS latency. Once more than [maxPending] access units are waiting, the backlog is dropped and the
 * policy asks for an IDR; from there until that IDR arrives every non-sync unit is dropped too,
 * because feeding half a GOP produces a smeared, macro-blocked picture rather than a late one.
 * If the backlog itself already contains a sync frame, we jump straight to it instead — no round
 * trip to the server needed.
 */
class PcAuDropPolicy(private val maxPending: Int = DEFAULT_MAX_PENDING) {

    private val pending = ArrayDeque<PcVideoFrame>()

    /** True while every non-IDR unit is being dropped, waiting for the stream to resync. */
    var isWaitingForIdr: Boolean = false
        private set

    var droppedFrames: Long = 0L
        private set

    val size: Int get() = pending.size

    /**
     * Offers one access unit. Returns true if the caller should ask the server for an IDR (only on
     * the transition into the waiting state — a caller that keeps offering frames doesn't spam the
     * control channel).
     */
    fun offer(frame: PcVideoFrame): Boolean {
        if (isWaitingForIdr) {
            if (!frame.isIdr && !frame.hasCodecConfig) {
                droppedFrames++
                return false
            }
            isWaitingForIdr = false
        }
        pending.addLast(frame)
        if (pending.size <= maxPending) return false

        // Behind: skip ahead to the newest sync point we already hold. Everything before it is
        // superseded, and everything after it decodes cleanly from it, so no hole is created.
        val lastSync = pending.indexOfLast { it.isIdr || it.hasCodecConfig }
        if (lastSync > 0) {
            repeat(lastSync) {
                pending.removeFirst()
                droppedFrames++
            }
        }
        if (pending.size <= maxPending) return false

        // Still behind, with no usable sync point to jump to: drop the partial GOP outright and
        // wait for a fresh IDR rather than feeding the decoder a picture full of holes.
        droppedFrames += pending.size.toLong()
        pending.clear()
        isWaitingForIdr = true
        return true
    }

    /** The next unit to feed the codec, or null when the queue is empty. */
    fun poll(): PcVideoFrame? = pending.pollFirst()

    /**
     * Drops everything queued. [requireIdr] = true also blocks non-sync units until the next IDR —
     * what a codec reset or a surface swap needs.
     */
    fun reset(requireIdr: Boolean) {
        droppedFrames += pending.size.toLong()
        pending.clear()
        isWaitingForIdr = requireIdr
    }

    companion object {
        /** More than this many units waiting means we're behind: drop instead of buffering. */
        const val DEFAULT_MAX_PENDING = 3
    }
}

/**
 * MediaCodec wrapper for the PC Link video stream: takes Annex-B access units off the wire and
 * renders them straight to a [Surface] (the one owned by whichever [OuToSbsGlView] is currently
 * live — the phone's or the glasses Presentation's).
 *
 * Deliberately not a player: there is no clock, no pacing and no output buffering. The server
 * paces the stream, so every decoded frame is released with `render = true` the instant it comes
 * out. Latency control happens on the input side instead — see [PcAuDropPolicy].
 *
 * Thread-safety: [submit] is called from the network reader, the rest from the main thread, and
 * MediaCodec's own callbacks arrive on a private [HandlerThread]. All shared state is guarded by
 * [lock]; MediaCodec itself is documented as thread-safe.
 */
class PcStreamDecoder(private val listener: Listener) {

    interface Listener {
        /** Ask the server for a sync frame (dropped a GOP, reset the codec, took a new surface). */
        fun onRequestIdr()

        /** Fatal-ish decoder trouble that survived a recreate attempt; for the status overlay. */
        fun onDecoderError(message: String)

        /** A frame was released to the surface. Called on the codec callback thread. */
        fun onFrameRendered(ptsUs: Long)

        /** The decoder's output size became known/changed (main thread not guaranteed). */
        fun onVideoSize(width: Int, height: Int)
    }

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var codec: MediaCodec? = null
    private var codecStarted = false
    private var released = false

    private var mime: String? = null
    private var width = 0
    private var height = 0
    private var csd: PcAnnexB.Csd? = null
    private var surface: Surface? = null

    private val queue = PcAuDropPolicy()
    private val freeInputBuffers = ArrayDeque<Int>()
    private var lastIdrNudgeNs = 0L

    /** Frames released to the surface since construction. */
    @Volatile var framesRendered: Long = 0L
        private set

    /** Access units fed to the codec. */
    @Volatile var framesDecoded: Long = 0L
        private set

    /** pts of the most recently rendered frame, microseconds on the SERVER's clock. */
    @Volatile var lastRenderedPtsUs: Long = 0L
        private set

    val droppedFrames: Long get() = synchronized(lock) { queue.droppedFrames }

    /**
     * Points the decoder at a stream format (the server's `config`). A format change tears the
     * codec down; the server always follows `config` with a codec-config frame, which rebuilds it.
     */
    fun configure(mime: String, width: Int, height: Int) {
        synchronized(lock) {
            if (released) return
            if (this.mime == mime && this.width == width && this.height == height) return
            this.mime = mime
            this.width = width
            this.height = height
            this.csd = null
            releaseCodecLocked()
            queue.reset(requireIdr = true)
        }
        listener.onRequestIdr()
    }

    /**
     * Attaches the output surface, or detaches with null (Presentation going away, activity
     * stopping). The codec is recreated rather than re-targeted: `setOutputSurface` is optional
     * for decoders and has a long history of vendor-specific failures, and we can always recover
     * cheaply by asking for a new IDR.
     */
    fun setSurface(surface: Surface?) {
        var needIdr = false
        synchronized(lock) {
            if (released) return
            if (this.surface === surface) return
            this.surface = surface
            releaseCodecLocked()
            queue.reset(requireIdr = true)
            if (surface != null && csd != null) {
                needIdr = createCodecLocked()
            }
        }
        if (needIdr || surface != null) listener.onRequestIdr()
    }

    /**
     * Feeds one access unit. Safe to call from any thread; returns immediately (the codec is in
     * async mode, so this only enqueues and opportunistically fills a free input buffer).
     */
    fun submit(frame: PcVideoFrame) {
        var requestIdr: Boolean
        synchronized(lock) {
            if (released) return
            if (frame.hasCodecConfig && csd == null) {
                val m = mime
                if (m != null) {
                    csd = PcAnnexB.extractCsd(frame.payload, m)
                    if (csd != null && surface != null && codec == null) createCodecLocked()
                }
            }
            requestIdr = queue.offer(frame)
            if (csd == null) {
                // Nothing can decode before the parameter sets arrive: hold the queue empty and
                // wait for a codec-config frame instead of buffering units nothing will consume.
                queue.reset(requireIdr = true)
            } else if (codec != null) {
                pumpLocked()
            }
            // csd but no codec = no surface right now; the drop policy caps the wait for one.
            //
            // Frames arriving while we can't use any of them means our earlier request never
            // produced a sync frame (lost, rate-limited, or we joined mid-stream and the server is
            // waiting to be asked). Nudge it again, at most once a second.
            if (!requestIdr && (csd == null || queue.isWaitingForIdr)) {
                requestIdr = throttledIdrRequestLocked()
            }
        }
        if (requestIdr) listener.onRequestIdr()
    }

    /** Stops everything. The instance is not reusable. */
    fun release() {
        val t: HandlerThread?
        synchronized(lock) {
            if (released) return
            released = true
            releaseCodecLocked()
            queue.reset(requireIdr = true)
            surface = null
            t = thread
            thread = null
            handler = null
        }
        t?.quitSafely()
    }

    // --- internals (all called under `lock`) ---------------------------------------------------

    /** Builds and starts a codec for the current mime/size/csd/surface. Returns true on success. */
    private fun createCodecLocked(): Boolean {
        val m = mime ?: return false
        val s = surface ?: return false
        val c = csd ?: return false
        if (width <= 0 || height <= 0) return false
        val h = ensureHandlerLocked()
        val format = MediaFormat.createVideoFormat(m, width, height)
        format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(c.csd0))
        c.csd1?.let { format.setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(it)) }
        // Adaptive playback: let the decoder handle a resolution change (the desktop resizing)
        // without a full reconfigure.
        format.setInteger(MediaFormat.KEY_MAX_WIDTH, width)
        format.setInteger(MediaFormat.KEY_MAX_HEIGHT, height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        // Vendor low-latency switches: same intent as KEY_LOW_LATENCY on SoCs whose decoders
        // predate it (or ignore it). Each is speculative, so each is guarded — an unknown key is
        // usually ignored, but a few OMX shims have thrown on it.
        for (key in VENDOR_LOW_LATENCY_KEYS) {
            try { format.setInteger(key, 1) } catch (_: Throwable) { }
        }
        try { format.setInteger("vendor.qti-ext-dec-picture-order.enable", 0) } catch (_: Throwable) { }
        // Realtime priority: this is a live link, not a file being transcoded.
        try { format.setInteger(MediaFormat.KEY_PRIORITY, 0) } catch (_: Throwable) { }

        return try {
            val created = createCodecForMime(m)
            created.setCallback(callback, h)
            created.configure(format, s, null, 0)
            created.start()
            codec = created
            codecStarted = true
            freeInputBuffers.clear()
            android.util.Log.i(TAG, "MediaCodec started: ${created.name} $m ${width}x$height")
            true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Failed to start decoder for $m ${width}x$height", t)
            releaseCodecLocked()
            listener.onDecoderError(t.message ?: "decoder init failed")
            false
        }
    }

    private fun createCodecForMime(mime: String): MediaCodec {
        val name = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info ->
                    !info.isEncoder &&
                        info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        isHardware(info)
                }?.name
        } catch (_: Throwable) {
            null
        }
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated && !info.isSoftwareOnly
        } else {
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun releaseCodecLocked() {
        val c = codec ?: return
        val wasStarted = codecStarted
        codec = null
        codecStarted = false
        freeInputBuffers.clear()
        try { if (wasStarted) c.stop() } catch (_: Throwable) { }
        try { c.release() } catch (_: Throwable) { }
    }

    private fun ensureHandlerLocked(): Handler {
        handler?.let { return it }
        val t = HandlerThread("PcStreamDecoder").also { it.start() }
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    /** Moves as many queued access units as there are free input buffers into the codec. */
    private fun pumpLocked() {
        val c = codec ?: return
        while (freeInputBuffers.isNotEmpty() && queue.size > 0) {
            val frame = queue.poll() ?: return
            val index = freeInputBuffers.removeFirst()
            try {
                val buffer = c.getInputBuffer(index) ?: continue
                buffer.clear()
                buffer.put(frame.payload)
                // No BUFFER_FLAG_CODEC_CONFIG even on a codec-config unit: the parameter sets are
                // the head of a normal access unit that continues into the IDR slices, and the
                // sets themselves already went in as csd-0/csd-1.
                c.queueInputBuffer(index, 0, frame.payload.size, frame.ptsUs, 0)
                framesDecoded++
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "queueInputBuffer failed", t)
                recoverLocked("input: ${t.message}")
                return
            }
        }
    }

    /** True at most once per [IDR_NUDGE_INTERVAL_NS], so a stalled stream can't spam the server. */
    private fun throttledIdrRequestLocked(): Boolean {
        val now = System.nanoTime()
        if (now - lastIdrNudgeNs < IDR_NUDGE_INTERVAL_NS) return false
        lastIdrNudgeNs = now
        return true
    }

    /** Rebuilds the codec after an error and asks for a fresh sync point. */
    private fun recoverLocked(reason: String) {
        releaseCodecLocked()
        queue.reset(requireIdr = true)
        val recreated = if (surface != null && csd != null) createCodecLocked() else false
        if (!recreated) listener.onDecoderError(reason)
        // Requested outside the lock by the callers that can; safe here too (the listener only
        // sets a flag on the client).
        listener.onRequestIdr()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            synchronized(lock) {
                if (released || codec !== c) return
                freeInputBuffers.addLast(index)
                pumpLocked()
            }
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (released || codec !== c) {
                    try { c.releaseOutputBuffer(index, false) } catch (_: Throwable) { }
                    return
                }
            }
            try {
                // Render NOW: the server does the pacing, so anything we hold back is pure latency.
                c.releaseOutputBuffer(index, true)
                framesRendered++
                lastRenderedPtsUs = info.presentationTimeUs
                listener.onFrameRendered(info.presentationTimeUs)
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "releaseOutputBuffer failed", t)
            }
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            val w = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Throwable) { 0 }
            val h = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Throwable) { 0 }
            if (w > 0 && h > 0) listener.onVideoSize(w, h)
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            android.util.Log.e(TAG, "MediaCodec error (recoverable=${e.isRecoverable})", e)
            synchronized(lock) {
                if (released || codec !== c) return
                recoverLocked(e.diagnosticInfo.ifEmpty { e.message ?: "decoder error" })
            }
        }
    }

    private companion object {
        const val TAG = "PcStreamDecoder"

        /** Minimum gap between "still stuck, please send a sync frame" nudges. */
        const val IDR_NUDGE_INTERVAL_NS = 1_000_000_000L

        val VENDOR_LOW_LATENCY_KEYS = arrayOf(
            "vendor.qti-ext-dec-low-latency.enable",   // Qualcomm
            "vendor.low-latency.enable",               // MediaTek / others
            "vendor.rtc-ext-dec-low-latency.enable"    // Exynos RTC path
        )
    }
}
