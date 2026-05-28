package com.teleteh.xplayer2.data.depth

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the on-device location of the depth-estimation model used by [DepthEstimator].
 *
 * Resolution order:
 *   1. Bundled in `app/src/main/assets/midas_v21_small.tflite` — preferred for offline builds
 *      or developers who manually drop the file in.
 *   2. Downloaded into `context.filesDir/midas_v21_small.tflite` from [MODEL_URL] — happens on
 *      first Lazy 3D enable, with progress reported through the [ensureAvailable] callback.
 *
 * Updates are handled via a cheap Content-Length comparison on a HEAD request — if the
 * remote file is a different size than the local one we re-download.
 *
 * Hosting note: [MODEL_URL] points at a GitHub Release asset on this repo. Upload a fresh
 * TFLite export there (release tag `models-v1` or newer; see [REMOTE_VERSION]) and bump
 * [REMOTE_VERSION] when the binary changes so existing installs pick the new model up.
 */
class DepthModelManager(private val appContext: Context) {

    enum class State { Missing, BundledInAssets, Cached, Downloading, Failed }

    fun interface ProgressListener {
        /** [bytesRead]/[total] downloaded so far. [total] may be -1 if server didn't say. */
        fun onProgress(bytesRead: Long, total: Long)
    }

    val cachedFile: File get() = File(appContext.filesDir, MODEL_FILENAME)

    fun isBundled(): Boolean = try {
        appContext.assets.openFd(MODEL_FILENAME).close(); true
    } catch (_: Throwable) { false }

    fun isCached(): Boolean = cachedFile.exists() && cachedFile.length() > 1_000_000

    fun isAvailable(): Boolean = isBundled() || isCached()

    fun currentState(): State = when {
        isBundled() -> State.BundledInAssets
        isCached() -> State.Cached
        else -> State.Missing
    }

    /**
     * Ensure the model is present locally — does nothing if it is, otherwise downloads from
     * [MODEL_URL] into the cache file. Network blocking work happens on Dispatchers.IO.
     * Returns true on success / already-present, false if download failed.
     */
    suspend fun ensureAvailable(progress: ProgressListener? = null): Boolean {
        if (isAvailable()) return true
        return downloadInternal(progress)
    }

    /**
     * Check whether the remote file is a different size than our cached copy. Returns
     * true if an update is available (or if the user has no copy at all). HEAD request,
     * no body transfer.
     */
    suspend fun isUpdateAvailable(): Boolean = withContext(Dispatchers.IO) {
        if (isBundled()) return@withContext false // we trust the bundled copy
        if (!isCached()) return@withContext true
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
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
    suspend fun forceUpdate(progress: ProgressListener? = null): Boolean = downloadInternal(progress)

    private suspend fun downloadInternal(progress: ProgressListener?): Boolean = withContext(Dispatchers.IO) {
        val target = cachedFile
        val tmp = File(target.parentFile, target.name + ".part")
        var success = false
        try {
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Download HTTP $code for $MODEL_URL")
                    return@withContext false
                }
                val total = conn.contentLengthLong
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
                            // Throttle progress callbacks to every 64 KB
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
            if (target.exists()) target.delete()
            success = tmp.renameTo(target)
            if (!success) {
                Log.w(TAG, "Failed to rename ${tmp.path} -> ${target.path}")
                tmp.delete()
            } else {
                Log.i(TAG, "Depth model downloaded: ${target.length()} bytes")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Download failed", e)
            tmp.delete()
        }
        success
    }

    companion object {
        private const val TAG = "DepthModelManager"
        // MiDaS v2.1 small (256x256, FP32). Clean float NHWC I/O that matches DepthEstimator.
        // To upgrade the model later (e.g. to a Depth-Anything-V2 export), just publish the
        // new .tflite under a new release tag and bump REMOTE_VERSION — clients pick it up
        // automatically on the next update check (size mismatch triggers re-download).
        const val MODEL_FILENAME = "midas_v21_small.tflite"
        const val REMOTE_VERSION = "models-v1"
        const val MODEL_URL =
            "https://github.com/Xandir150/Xplayer2/releases/download/$REMOTE_VERSION/$MODEL_FILENAME"
    }
}
