package com.teleteh.xplayer2.data.depth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the on-device depth model used by [DepthEstimator]. Supports MORE THAN ONE model so the
 * beta can A/B them (see [DepthModel]); the active one is a user choice persisted in prefs and
 * resolved per-instance. Each model resolves to its own cache file + download URL + input geometry.
 *
 * Resolution order per model: bundled asset (if shipped) → cached download (`filesDir/<filename>`)
 * from its GitHub-release URL, fetched on first use. Downloads are validated (Content-Length match
 * + TFLite "TFL3" magic) and single-flighted; a model that fails to load is invalidated and re-fetched.
 */
class DepthModelManager(
    private val appContext: Context,
    val model: DepthModel = activeModel(appContext),
) {

    /**
     * A selectable depth model. TEMPORARY beta A/B: we ship two and let testers pick the one that
     * looks best; once we know, this collapses back to a single model.
     *  - [inputSize]  the model's square input (and output) resolution.
     *  - [gpuSafe]    false → never run on the TFLite GPU delegate (e.g. DA-V2's ViT outputs constant
     *                 garbage on Adreno GPU, TF #93476) — NPU(NNAPI)/CPU only.
     */
    enum class DepthModel(
        val filename: String,
        val url: String,
        val inputSize: Int,
        val gpuSafe: Boolean,
        val uiLabel: String,
    ) {
        MIDAS(
            "midas_v21_small.tflite", "$REL/midas_v21_small.tflite",
            256, true, "MiDaS small — fast (current default)"
        ),
        DAV2(
            "depth_anything_v2_small.tflite", "$REL/depth_anything_v2_small.tflite",
            518, false, "Depth Anything V2 — sharper, NPU only, ~99 MB"
        );
    }

    enum class State { Missing, BundledInAssets, Cached, Downloading, Failed }

    fun interface ProgressListener {
        /** [bytesRead]/[total] downloaded so far. [total] may be -1 if server didn't say. */
        fun onProgress(bytesRead: Long, total: Long)
    }

    val cachedFile: File get() = File(appContext.filesDir, model.filename)

    /** Host the active model downloads from — for surfacing a network error to the user. */
    fun downloadHost(): String = runCatching { URL(model.url).host }.getOrNull() ?: "the server"

    fun isBundled(): Boolean = try {
        appContext.assets.openFd(model.filename).close(); true
    } catch (_: Throwable) { false }

    fun isCached(): Boolean =
        cachedFile.exists() && cachedFile.length() > 1_000_000 && hasTfliteMagic(cachedFile)

    fun isAvailable(): Boolean = isBundled() || isCached()

    /**
     * Delete the cached model. Called when [DepthEstimator] fails to load it on EVERY backend —
     * a plain CPU Interpreter only rejects a corrupt flatbuffer, and a corrupt cached file would
     * otherwise brick Lazy 3D forever (it passes the size check on every retry, and if its size
     * matches the remote the update check never replaces it either). Next enable re-downloads.
     */
    fun invalidateCache() {
        if (cachedFile.exists()) {
            Log.w(TAG, "Invalidating cached depth model ${model.filename} (${cachedFile.length()} bytes) — failed to load")
            cachedFile.delete()
        }
    }

    fun currentState(): State = when {
        isBundled() -> State.BundledInAssets
        isCached() -> State.Cached
        else -> State.Missing
    }

    /**
     * Ensure the model is present locally — does nothing if it is, otherwise downloads from
     * [DepthModel.url] into the cache file. Network blocking work happens on Dispatchers.IO.
     * Returns true on success / already-present, false if download failed.
     */
    suspend fun ensureAvailable(progress: ProgressListener? = null): Boolean {
        if (isAvailable()) return true
        // Single-flight per file: the on-demand enable, the MainActivity prefetch and the update
        // check used to download CONCURRENTLY into the same .part file, each opening it with O_TRUNC.
        return downloadMutex.withLock {
            if (isAvailable()) true else downloadInternal(progress)
        }
    }

    /**
     * Check whether the remote file is a different size than our cached copy. Returns
     * true if an update is available (or if the user has no copy at all). HEAD request.
     */
    suspend fun isUpdateAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (isBundled()) return@withContext false // we trust the bundled copy
        if (!isCached()) return@withContext true
        try {
            val conn = URL(model.url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            try {
                val remote = conn.contentLengthLong
                remote > 0 && remote != cachedFile.length()
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Update check failed: ${e.message}")
            false
        }
    }

    /** Re-download even if we already have a copy. Returns true on success. */
    suspend fun forceUpdate(progress: ProgressListener? = null): Boolean =
        downloadMutex.withLock { downloadInternal(progress) }

    private suspend fun downloadInternal(progress: ProgressListener?): Boolean = withContext(Dispatchers.IO) {
        val target = cachedFile
        val tmp = File(target.parentFile, target.name + ".part")
        var success = false
        try {
            val conn = URL(model.url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            var expected = -1L
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Download HTTP $code for ${model.url}")
                    return@withContext false
                }
                val total = conn.contentLengthLong
                expected = total
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var sent = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sent += n
                            if (sent - lastReport > 64 * 1024) {
                                lastReport = sent
                                progress?.onProgress(sent, total)
                            }
                        }
                        progress?.onProgress(sent, total)
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() < 1_000_000) {
                Log.w(TAG, "Downloaded file too small (${tmp.length()} bytes), discarding")
                tmp.delete()
                return@withContext false
            }
            // A truncated body or an HTML interstitial from a proxy can exceed 1 MB — never let
            // either replace a working model.
            if (expected > 0 && tmp.length() != expected) {
                Log.w(TAG, "Downloaded ${tmp.length()} of $expected bytes (truncated), discarding")
                tmp.delete()
                return@withContext false
            }
            if (!hasTfliteMagic(tmp)) {
                Log.w(TAG, "Downloaded file is not a TFLite flatbuffer, discarding")
                tmp.delete()
                return@withContext false
            }
            if (target.exists()) target.delete()
            success = tmp.renameTo(target)
            if (!success) {
                Log.w(TAG, "Failed to rename ${tmp.path} -> ${target.path}")
                tmp.delete()
            } else {
                Log.i(TAG, "Depth model downloaded: ${model.filename} ${target.length()} bytes")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed", e)
            tmp.delete()
        }
        success
    }

    companion object {
        private const val TAG = "DepthModelManager"
        private const val REMOTE_VERSION = "models-v1"
        private const val REL =
            "https://github.com/Xandir150/Xplayer2/releases/download/$REMOTE_VERSION"

        /** Bundled-asset filename for the default model (kept for the assets-resolution path). */
        const val MODEL_FILENAME = "midas_v21_small.tflite"

        private const val MODEL_PREFS = "lazy3d_model"
        private const val KEY_ACTIVE = "active"

        /** The model the user picked (TEMPORARY beta switch). Defaults to the fast MiDaS. */
        fun activeModel(context: Context): DepthModel {
            val name = context.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE, null)
            return DepthModel.values().firstOrNull { it.name == name } ?: DepthModel.MIDAS
        }

        fun setActiveModel(context: Context, m: DepthModel) {
            context.getSharedPreferences(MODEL_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_ACTIVE, m.name).apply()
        }

        /** Serializes ALL model downloads process-wide (instances are created ad hoc everywhere). */
        private val downloadMutex = Mutex()

        private val updateCheckClaimed = AtomicBoolean(false)

        /** Claim the once-per-process update check — true for the first caller only. */
        fun claimUpdateCheck(): Boolean = updateCheckClaimed.compareAndSet(false, true)

        /** TFLite flatbuffers carry the "TFL3" file identifier at bytes 4..7. */
        private fun hasTfliteMagic(f: File): Boolean = try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(4)
                val b = ByteArray(4)
                raf.readFully(b)
                b[0] == 'T'.code.toByte() && b[1] == 'F'.code.toByte() &&
                        b[2] == 'L'.code.toByte() && b[3] == '3'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }
}
