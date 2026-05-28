package com.teleteh.xplayer2.data.depth

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Wraps a TFLite monocular-depth model (default: Depth-Anything-V2 Small) for the
 * "Lazy 3D" feature.
 *
 * Model placement: drop `depth_v2_small.tflite` into `app/src/main/assets/`. See
 * [README.txt][com.teleteh.xplayer2.data.depth.README] in the same package — and the
 * download notes in the project README — for where to grab a mobile-friendly copy.
 *
 * Expected I/O shape (matches the public Depth-Anything-V2 small INT8 export):
 *   input  : float32 [1, H, W, 3], RGB in 0..1, default H=W=256
 *   output : float32 [1, H, W] OR [1, H, W, 1], raw inverse-depth (higher = nearer)
 *
 * Output is min/max-normalised to 0..1 (1 = nearest) so the GL stereo shader can use
 * it as a unit disparity multiplier without knowing model-specific scaling.
 *
 * Inference is dispatched on the NNAPI delegate when available (NPU on Tensor G3 /
 * Snapdragon 8 Gen 2+) — falls back to multi-thread CPU otherwise. GPU delegate is
 * available as an opt-in for devices whose NPU implementation rejects this graph.
 */
class DepthEstimator(
    val inputSize: Int = 256,
) {
    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private val inputBuf: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 3 * 4)
        .order(ByteOrder.nativeOrder())
    private val outputBuf: ByteBuffer = ByteBuffer
        .allocateDirect(inputSize * inputSize * 4)
        .order(ByteOrder.nativeOrder())

    /** Exponential-moving-average inference latency in ms. Updated after every [estimate]. */
    @Volatile var avgInferenceMs: Float = 0f
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
        return try {
            val buffer = loadModelAsset(context, assetPath)
                ?: loadModelFile(DepthModelManager(context).cachedFile)
                ?: return false
            val options = Interpreter.Options().apply {
                // Reserve threads conservatively — depth runs alongside the video decoder.
                setNumThreads(2)
                try {
                    val d = NnApiDelegate()
                    addDelegate(d)
                    nnApiDelegate = d
                    Log.i(TAG, "Lazy 3D: NNAPI delegate attached")
                } catch (e: Throwable) {
                    Log.w(TAG, "Lazy 3D: NNAPI not available (${e.message}); using CPU")
                }
            }
            interpreter = Interpreter(buffer, options)
            Log.i(TAG, "Lazy 3D: depth model loaded (inputSize=${inputSize}x$inputSize)")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Lazy 3D: failed to init depth model", e)
            close()
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

        // Bilinear down-sample directly into the input buffer (avoids allocating Bitmap).
        inputBuf.rewind()
        val scaleX = srcWidth.toFloat() / inputSize
        val scaleY = srcHeight.toFloat() / inputSize
        for (y in 0 until inputSize) {
            val sy = (y * scaleY).toInt().coerceIn(0, srcHeight - 1)
            val rowStart = sy * srcWidth
            for (x in 0 until inputSize) {
                val sx = (x * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val px = rgbaPixels[rowStart + sx]
                inputBuf.putFloat(((px shr 16) and 0xFF) / 255f) // R
                inputBuf.putFloat(((px shr 8) and 0xFF) / 255f)  // G
                inputBuf.putFloat((px and 0xFF) / 255f)          // B
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
        val range = (max - min).coerceAtLeast(1e-6f)
        for (i in out.indices) out[i] = (out[i] - min) / range
        return out
    }

    fun close() {
        try { interpreter?.close() } catch (_: Throwable) { }
        try { nnApiDelegate?.close() } catch (_: Throwable) { }
        interpreter = null
        nnApiDelegate = null
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
    }
}
