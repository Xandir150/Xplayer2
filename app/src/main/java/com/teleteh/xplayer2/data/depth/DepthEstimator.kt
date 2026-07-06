package com.teleteh.xplayer2.data.depth

import android.content.Context
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps a TFLite monocular-depth model (MiDaS v2.1 small) for the "Lazy 3D" feature.
 *
 * Model is auto-downloaded at runtime by [DepthModelManager]; a bundled assets copy is also
 * honoured if present. See the assets README for details.
 *
 * Verified I/O shape (MiDaS v2.1 small, 256x256 FP32):
 *   input  : float32 [1, 256, 256, 3], RGB, ImageNet-normalised ((x/255 - mean)/std)
 *   output : float32 [1, 256, 256, 1], raw inverse-depth (higher = nearer)
 *
 * Output is min/max-normalised to 0..1 (1 = nearest) so the GL stereo shader can use
 * it as a unit disparity multiplier without knowing model-specific scaling.
 *
 * Inference uses an NPU-first delegate ladder for energy efficiency: NNAPI (the OS routes to the
 * device NPU on ANY vendor — Hexagon / APU / Exynos / Tensor — configured hardware-accelerator-only)
 * → GPU delegate (portable) → multi-thread CPU. See [init].
 */
class DepthEstimator(
    val inputSize: Int = 256,
    // false → never try the GPU delegate (e.g. DA-V2's ViT returns constant garbage on Adreno GPU,
    // TF #93476) — NPU(NNAPI)/CPU only. The active model's gpuSafe drives this.
    private val allowGpu: Boolean = true,
    // Per-model depth "mapper" (iw3 terms) applied to normalised inverse depth (1 = nearest)
    // before the stereo warp — e.g. DepthModel.mapDepth. Baked into a LUT once; null = identity.
    depthMapper: ((Float) -> Float)? = null,
    // Percentile of the final depth map that the dynamic convergence tracks (see
    // [dynamicConvergence]); 0 disables dynamic convergence (stays at the 0.5 screen-middle).
    private val convergencePercentile: Float = 0f,
) {
    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null

    private val inputBuf: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val outputBuf: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 4)
        .order(ByteOrder.nativeOrder())

    // Mapper as a 257-entry LUT (linear-interp not needed at 8-bit depth-texture precision) —
    // ~200k transcendental calls per inference at 30 Hz would be measurable on the v7a TV boxes.
    private val mapperLut: FloatArray? = depthMapper?.let { f ->
        FloatArray(MAPPER_LUT_SIZE + 1) { i -> f(i.toFloat() / MAPPER_LUT_SIZE).coerceIn(0f, 1f) }
    }

    // Histogram scratch for the percentile range scan — reused across frames (worker thread only).
    private val histBins = IntArray(HIST_BINS)

    // --- Temporal smoothing (Lazy 3D: kills depth flicker + scale "breathing") ---
    // MiDaS normalises each frame by its OWN min/max, so the same physical depth maps to different
    // values every inference → the whole picture's depth "breathes" → visible shimmer in the warp.
    // We EMA both the normalisation min/max AND the per-pixel normalised depth across inferences.
    // alpha = weight kept from history; ~0.85 ≈ a few-frame time constant at the ~30 Hz infer rate.
    @Volatile var temporalAlpha: Float = 0.85f
    // All temporal state below is guarded by [temporalLock]: estimate() runs on the worker thread
    // while resetTemporal()/close() can run on the teardown thread — they must not race the buffers.
    private val temporalLock = Any()
    private var emaMin = 0f
    private var emaMax = 0f
    private var emaDepth: FloatArray? = null
    private var temporalSeeded = false

    /**
     * Smoothed screen-plane depth for the warp shader's convergence uniform (0.2..0.8; 0.5 until
     * the first inference lands or when dynamic convergence is disabled). Polled from the depth
     * tick on the main thread — volatile, updated under [temporalLock] on the worker thread.
     */
    @Volatile var dynamicConvergence: Float = 0.5f
        private set

    /** Clear temporal-smoothing state so a new clip/session starts clean (no stale depth bleed). */
    fun resetTemporal() {
        synchronized(temporalLock) {
            temporalSeeded = false
            emaMin = 0f
            emaMax = 0f
            emaDepth = null
            dynamicConvergence = 0.5f
        }
    }

    /** Exponential-moving-average inference latency in ms. Updated after every [estimate]. */
    @Volatile var avgInferenceMs: Float = 0f
        private set

    /** Which TFLite backend the interpreter loaded on ("GPU" / "NNAPI" / "CPU x4"), or null. */
    @Volatile var backend: String? = null
        private set

    /** True once a TFLite [Interpreter] is ready to accept frames. */
    fun isReady(): Boolean = interpreter != null

    /**
     * Load the model. Tries [assetPath] first (bundled in-APK build, dev workflow), then
     * the cached file managed by [DepthModelManager.cachedFile] (downloaded at runtime).
     * Returns false if neither location has the file or the runtime can't construct an
     * interpreter (e.g. NNAPI rejected the graph).
     */
    fun init(context: Context): Boolean {
        if (interpreter != null) return true
        val mgr = DepthModelManager(context)   // resolves the active (user-selected) model
        val buffer = loadModelAsset(context, mgr.model.filename)
            ?: loadModelFile(mgr.cachedFile)
            ?: return false

        // Delegate ladder, NPU-FIRST for energy efficiency: the device NPU is the most power-
        // efficient path for per-frame depth, so we try it first, then GPU, then CPU.
        //
        // 1) NNAPI — the only VENDOR-AGNOSTIC OS route to the NPU across API 29..36: the OS routes
        //    to whatever accelerator the device's NNAPI HAL exposes (Qualcomm Hexagon, MediaTek APU,
        //    Samsung Exynos NPU, Google Tensor TPU). We bundle NO vendor .so for this. Configured to
        //    use ONLY a hardware accelerator (setUseNnapiCpu(false)) so a device with no usable NPU
        //    fails fast and falls through to the GPU instead of silently emulating on the CPU.
        //    NNAPI is deprecated (Android 15) but still functional through Android 16; it stays the
        //    portable NPU path until LiteRT's per-vendor NPU accelerators cover our API floor.
        // A Java-level NNAPI failure is already handled (tryInit catches it → we fall through to GPU).
        // The unguarded case is a NATIVE crash (SIGSEGV) inside the vendor NNAPI HAL during init,
        // which try/catch CANNOT catch (same hazard as the GPU delegate, which we gate with
        // CompatibilityList — NNAPI offers no such probe). Guard it with a persisted CANARY that
        // survives a process crash: arm a flag on disk immediately before the native init and disarm
        // it immediately after. If a later launch finds the flag still armed, the previous attempt
        // took the process down → permanently ban NNAPI on this device and go straight to the GPU.
        // One crash maximum per device, then self-healed.
        val prefs = context.getSharedPreferences(DELEGATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NNAPI_BANNED, false)) {
            Log.i(TAG, "Lazy 3D: NNAPI banned on this device (prior native crash) — using GPU/CPU")
        } else if (prefs.getBoolean(KEY_NNAPI_PROBING, false)) {
            // Flag left armed by a previous run = NNAPI native-crashed during init last time.
            prefs.edit().putBoolean(KEY_NNAPI_BANNED, true).putBoolean(KEY_NNAPI_PROBING, false).commit()
            Log.w(TAG, "Lazy 3D: NNAPI crashed on a previous launch — banning it on this device")
        } else {
            prefs.edit().putBoolean(KEY_NNAPI_PROBING, true).commit()   // must hit disk BEFORE the native call
            val ok = tryInit(buffer) { o ->
                val opts = NnApiDelegate.Options().apply {
                    // LOW_POWER, deliberately: depth runs continuously for the length of a movie,
                    // and SUSTAINED_SPEED asks the driver to hold the NPU at high clocks — which
                    // overheated phones within minutes (thermal warning → OS killed the app).
                    // LOW_POWER trades some per-inference latency for temperature; the latest-only
                    // worker absorbs that by just updating depth a little less often. Before/after
                    // latency is visible as "depth Xms" in the remote's debug overlay.
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER)
                    setAllowFp16(true)
                    setUseNnapiCpu(false)   // hardware accelerator only — no CPU reference fallback
                }
                val d = NnApiDelegate(opts); nnApiDelegate = d; o.addDelegate(d); "NNAPI"
            }
            prefs.edit().putBoolean(KEY_NNAPI_PROBING, false).commit()
            if (ok) return true
        }
        // 2) GPU (Adreno / Mali / Xclipse / PowerVR) — portable fallback, but only for gpu-safe
        //    models: DA-V2's ViT returns constant garbage on the Adreno GPU delegate (TF #93476),
        //    so that model skips straight to CPU. Gated by CompatibilityList: on some GPUs/drivers
        //    constructing the GPU delegate crashes NATIVELY (SIGSEGV), uncatchable by tryInit's
        //    try/catch, so only attempt it when TFLite says it's safe.
        if (allowGpu) {
            val gpuSupported = try {
                CompatibilityList().isDelegateSupportedOnThisDevice
            } catch (e: Throwable) {
                Log.w(TAG, "Lazy 3D: GPU compatibility probe failed (${e.message}); skipping GPU")
                false
            }
            if (gpuSupported && tryInit(buffer) { o ->
                    val d = GpuDelegate(); gpuDelegate = d; o.addDelegate(d); "GPU"
                }) return true
        }
        // 3) Multi-thread CPU — universal last resort.
        return tryInit(buffer) { o -> o.setNumThreads(4); "CPU x4" }
    }

    private inline fun tryInit(buffer: MappedByteBuffer, configure: (Interpreter.Options) -> String): Boolean {
        return try {
            val options = Interpreter.Options()
            val label = configure(options)
            interpreter = Interpreter(buffer, options)
            backend = label
            Log.i(TAG, "Lazy 3D: depth model loaded on $label (inputSize=${inputSize}x$inputSize)")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Lazy 3D: delegate init failed (${e.message}); trying next")
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            try { nnApiDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null; nnApiDelegate = null
            try { interpreter?.close() } catch (_: Throwable) {}
            interpreter = null
            false
        }
    }

    /**
     * Run one depth inference. [rgbaPixels] must be width*height ints in ARGB_8888 layout
     * (the format [android.graphics.Bitmap.getPixels] returns). The input is bilinearly
     * down-sampled to [inputSize] x [inputSize] before inference.
     *
     * Returns the normalised depth as a [FloatArray] of size inputSize*inputSize, or null
     * if no interpreter is loaded.
     */
    fun estimate(rgbaPixels: IntArray, srcWidth: Int, srcHeight: Int): FloatArray? {
        val interp = interpreter ?: return null
        if (srcWidth <= 0 || srcHeight <= 0) return null

        // Nearest-neighbour down-sample into the input buffer (avoids allocating a Bitmap),
        // applying ImageNet normalization — MiDaS v2.1 small was trained on
        // (pixel/255 - mean) / std with the standard ImageNet constants. Feeding plain 0..1
        // produces washed-out, low-contrast depth.
        inputBuf.rewind()
        val scaleX = srcWidth.toFloat() / inputSize
        val scaleY = srcHeight.toFloat() / inputSize
        for (y in 0 until inputSize) {
            val sy = (y * scaleY).toInt().coerceIn(0, srcHeight - 1)
            val rowStart = sy * srcWidth
            for (x in 0 until inputSize) {
                val sx = (x * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val px = rgbaPixels[rowStart + sx]
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8) and 0xFF) / 255f
                val b = (px and 0xFF) / 255f
                inputBuf.putFloat((r - 0.485f) / 0.229f)
                inputBuf.putFloat((g - 0.456f) / 0.224f)
                inputBuf.putFloat((b - 0.406f) / 0.225f)
            }
        }
        inputBuf.rewind()
        outputBuf.rewind()

        val t0 = System.nanoTime()
        try {
            interp.run(inputBuf, outputBuf)
        } catch (e: Throwable) {
            Log.e(TAG, "Lazy 3D: inference failed", e)
            return null
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000f
        avgInferenceMs = if (avgInferenceMs <= 0f) elapsedMs else avgInferenceMs * 0.9f + elapsedMs * 0.1f

        outputBuf.rewind()
        val out = FloatArray(inputSize * inputSize)
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (i in out.indices) {
            val v = outputBuf.float
            out[i] = v
            if (v < min) min = v
            if (v > max) max = v
        }
        // Robust range: normalise to the p02..p98 percentiles instead of the absolute min/max.
        // A handful of outlier pixels (specular highlight the net reads as "very near", a dark
        // corner read as "very far") used to own the whole 0..1 range, compressing the real scene
        // into a narrow band — the main reason V-Model's depth looked flat and MiDaS's occasional
        // near-spikes exaggerated pop-out. Clipped pixels saturate to 0/1, which is what we want.
        if (max > min) {
            val p = percentileRange(out, min, max)
            min = p.first
            max = p.second
        }
        // Temporally-stable normalisation + per-pixel EMA. Guarded so a concurrent resetTemporal()
        // can't null/realloc the buffer mid-flight (see [temporalLock]).
        synchronized(temporalLock) {
            val a = temporalAlpha.coerceIn(0f, 0.97f)
            // The normalisation min/max use a slower EMA than the per-pixel depth: a steadier range
            // avoids re-mapping the whole picture every frame (which would itself shimmer), while the
            // per-pixel EMA still adapts at [a].
            val aRange = maxOf(a, 0.92f)
            var ema = emaDepth
            // Scene-cut detection: if the new raw range barely overlaps the smoothed one (hard cut,
            // seek, or a new clip), snap to it instead of slowly morphing the scale across the change.
            val overlap = minOf(max, emaMax) - maxOf(min, emaMin)
            val span = maxOf(max - min, emaMax - emaMin).coerceAtLeast(1e-6f)
            val cut = temporalSeeded && overlap < 0.3f * span
            val seed = cut || !temporalSeeded || ema == null || ema.size != out.size
            if (seed) {
                emaMin = min; emaMax = max
            } else {
                emaMin = aRange * emaMin + (1f - aRange) * min
                emaMax = aRange * emaMax + (1f - aRange) * max
            }
            val range = (emaMax - emaMin).coerceAtLeast(1e-6f)
            if (ema == null || ema.size != out.size) { ema = FloatArray(out.size); emaDepth = ema }
            val lut = mapperLut
            for (i in out.indices) {
                var norm = ((out[i] - emaMin) / range).coerceIn(0f, 1f)
                // Per-model mapper (see DepthModel.mapDepth) — LUT lookup, applied before the EMA
                // so the temporal smoothing operates on the same curve the shader will consume.
                if (lut != null) norm = lut[(norm * MAPPER_LUT_SIZE).toInt()]
                ema[i] = if (seed) norm else a * ema[i] + (1f - a) * norm
                out[i] = ema[i]
            }
            temporalSeeded = true
            updateDynamicConvergence(out, seed)
        }
        return out
    }

    /**
     * Dynamic convergence (iw3 --convergence-mode / Apple spatial-photo style): track the
     * [convergencePercentile] of the FINAL depth map with a slow EMA, so the stereo screen plane
     * locks onto the main subject — the subject sits at the display "window" and the rest of the
     * scene recedes behind it, which reads as distinct layers and keeps pop-out comfortable.
     * Runs under [temporalLock]; snaps on seed/scene-cut like the rest of the temporal state.
     */
    private fun updateDynamicConvergence(depth: FloatArray, seed: Boolean) {
        if (convergencePercentile <= 0f) return
        val bins = histBins
        java.util.Arrays.fill(bins, 0)
        for (v in depth) bins[(v * (HIST_BINS - 1)).toInt().coerceIn(0, HIST_BINS - 1)]++
        val target = (depth.size * convergencePercentile).toInt()
        var acc = 0
        var bin = HIST_BINS - 1
        for (b in 0 until HIST_BINS) {
            acc += bins[b]
            if (acc >= target) { bin = b; break }
        }
        val value = (bin.toFloat() / (HIST_BINS - 1)).coerceIn(0.2f, 0.8f)
        dynamicConvergence = if (seed) value
        else CONVERGENCE_ALPHA * dynamicConvergence + (1f - CONVERGENCE_ALPHA) * value
    }

    /**
     * Low/high percentile values of [out] via a [HIST_BINS]-bin histogram over [rawMin, rawMax] —
     * one O(n) pass + a 256-entry scan, negligible next to the ~30 ms inference. Returns the
     * (p02, p98) sample values, degenerating to the raw extremes for near-constant maps.
     */
    private fun percentileRange(out: FloatArray, rawMin: Float, rawMax: Float): Pair<Float, Float> {
        val bins = histBins
        java.util.Arrays.fill(bins, 0)
        val scale = (HIST_BINS - 1) / (rawMax - rawMin)
        for (v in out) bins[((v - rawMin) * scale).toInt().coerceIn(0, HIST_BINS - 1)]++
        val loTarget = (out.size * PCT_CLIP).toInt()
        val hiTarget = (out.size * (1f - PCT_CLIP)).toInt()
        var acc = 0
        var loBin = 0
        var hiBin = HIST_BINS - 1
        for (b in 0 until HIST_BINS) {
            acc += bins[b]
            if (acc <= loTarget) loBin = b
            if (acc < hiTarget) hiBin = b else break
        }
        if (hiBin <= loBin) return rawMin to rawMax   // near-constant map — don't fabricate range
        val lo = rawMin + loBin / scale
        val hi = rawMin + (hiBin + 1) / scale
        return lo to hi
    }

    fun close() {
        try { interpreter?.close() } catch (_: Throwable) { }
        try { nnApiDelegate?.close() } catch (_: Throwable) { }
        try { gpuDelegate?.close() } catch (_: Throwable) { }
        interpreter = null
        nnApiDelegate = null
        gpuDelegate = null
        resetTemporal()
    }

    private fun loadModelAsset(context: Context, assetPath: String): MappedByteBuffer? {
        return try {
            val afd = context.assets.openFd(assetPath)
            FileInputStream(afd.fileDescriptor).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        } catch (_: Throwable) {
            // Asset missing — caller will try the cached file path.
            null
        }
    }

    private fun loadModelFile(file: java.io.File): MappedByteBuffer? {
        return try {
            if (!file.exists() || file.length() < 1_000_000) {
                Log.w(TAG, "Lazy 3D: depth model not found in assets or cache (${file.path})")
                return null
            }
            FileInputStream(file).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Lazy 3D: failed to map cached model ${file.path}: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DepthEstimator"
        // Canary prefs guarding the (uncatchable) native NNAPI-init crash — see init().
        private const val DELEGATE_PREFS = "lazy3d_delegate"
        private const val KEY_NNAPI_PROBING = "nnapi_probing"
        private const val KEY_NNAPI_BANNED = "nnapi_banned"
        // Robust-normalisation parameters (see estimate/percentileRange).
        private const val HIST_BINS = 256
        private const val PCT_CLIP = 0.02f   // clip 2% at each end of the depth histogram
        private const val MAPPER_LUT_SIZE = 256
        // Slow EMA for the dynamic convergence — the screen plane must not visibly "swim".
        private const val CONVERGENCE_ALPHA = 0.95f

        /** Device SoC label ("Samsung Exynos 2400" / "Qualcomm SM8550") for the Lazy-3D debug
         *  overlay — API 31+ only; falls back to the board codename ([Build.HARDWARE]) below that
         *  or if the OEM leaves the SOC_* fields blank. Static/cached — doesn't change at runtime. */
        val socLabel: String by lazy {
            val mfr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null
            val model = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
            listOfNotNull(mfr, model).joinToString(" ").ifBlank { Build.HARDWARE }
        }

        // Overall-CPU-load sampling state (device-wide, not per-estimator — hence static). There's
        // no public API for per-backend (NPU/GPU) utilization on Android, so this is the closest
        // proxy the debug overlay can show; it's total system CPU, not just this process.
        @Volatile private var lastCpuTotal = 0L
        @Volatile private var lastCpuIdle = 0L

        /**
         * Best-effort overall CPU load % (delta since the last call) from /proc/stat's aggregate
         * "cpu" line, which — unlike /proc/[pid]/stat for other processes — third-party apps can
         * still read on stock Android. Returns null on the first call (no delta yet), if the file
         * is unreadable on this OEM/SELinux policy, or on any parse error.
         */
        fun sampleCpuLoadPercent(): Int? = try {
            val line = java.io.RandomAccessFile("/proc/stat", "r").use { it.readLine() }
            val fields = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (fields.size < 4) return null
            val idle = fields[3] + fields.getOrElse(4) { 0L }   // idle + iowait
            val total = fields.sum()
            val (prevTotal, prevIdle) = lastCpuTotal to lastCpuIdle
            lastCpuTotal = total; lastCpuIdle = idle
            val dTotal = total - prevTotal
            val dIdle = idle - prevIdle
            if (prevTotal == 0L || dTotal <= 0) null
            else (100 * (dTotal - dIdle) / dTotal).toInt().coerceIn(0, 100)
        } catch (_: Throwable) {
            null
        }
    }
}
