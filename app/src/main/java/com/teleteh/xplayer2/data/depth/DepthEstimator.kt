package com.teleteh.xplayer2.data.depth

import android.content.Context
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

    /** Clear temporal-smoothing state so a new clip/session starts clean (no stale depth bleed). */
    fun resetTemporal() {
        synchronized(temporalLock) {
            temporalSeeded = false
            emaMin = 0f
            emaMax = 0f
            emaDepth = null
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
    fun init(context: Context, assetPath: String = DepthModelManager.MODEL_FILENAME): Boolean {
        if (interpreter != null) return true
        val buffer = loadModelAsset(context, assetPath)
            ?: loadModelFile(DepthModelManager(context).cachedFile)
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
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                    setAllowFp16(true)
                    setUseNnapiCpu(false)   // hardware accelerator only — no CPU reference fallback
                }
                val d = NnApiDelegate(opts); nnApiDelegate = d; o.addDelegate(d); "NNAPI"
            }
            prefs.edit().putBoolean(KEY_NNAPI_PROBING, false).commit()
            if (ok) return true
        }
        // 2) GPU (Adreno / Mali / Xclipse / PowerVR) — portable fallback. Gated by CompatibilityList:
        //    on some GPUs/drivers constructing the GPU delegate crashes NATIVELY (SIGSEGV), which the
        //    try/catch in tryInit CANNOT recover from, so only attempt it when TFLite says it's safe.
        val gpuSupported = try {
            CompatibilityList().isDelegateSupportedOnThisDevice
        } catch (e: Throwable) {
            Log.w(TAG, "Lazy 3D: GPU compatibility probe failed (${e.message}); skipping GPU")
            false
        }
        if (gpuSupported && tryInit(buffer) { o ->
                val d = GpuDelegate(); gpuDelegate = d; o.addDelegate(d); "GPU"
            }) return true
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
            for (i in out.indices) {
                val norm = ((out[i] - emaMin) / range).coerceIn(0f, 1f)
                ema[i] = if (seed) norm else a * ema[i] + (1f - a) * norm
                out[i] = ema[i]
            }
            temporalSeeded = true
        }
        return out
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
    }
}
