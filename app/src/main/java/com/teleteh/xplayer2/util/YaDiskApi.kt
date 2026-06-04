package com.teleteh.xplayer2.util

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for Yandex Disk's PUBLIC resources REST API (no OAuth needed).
 *
 * A public link is either a folder (`https://disk.yandex.ru/d/<key>`) or a single file
 * (`.../i/<key>`). We pass the WHOLE share URL as `public_key` (the API accepts that) and branch on
 * the resource `type` returned by the meta endpoint:
 *   - "dir"  -> enumerate `_embedded.items` (subfolders to drill into + video files to play)
 *   - "file" -> the link itself is one video; resolve its download href and play it
 *
 * The public API exposes only the ORIGINAL uploaded file per video — there is no public HLS/multi-
 * quality manifest — so "maximum quality" is simply the original. Download hrefs are short-lived and
 * signed, so we resolve them lazily, right before playback (and refresh on a 403).
 *
 * Docs: https://yandex.com/dev/disk/api/reference/public.html
 */
object YaDiskApi {

    private const val TAG = "YaDiskApi"
    private const val API = "https://cloud-api.yandex.net/v1/disk/public/resources"
    private const val PAGE = 200

    /** One row in a public folder: a subfolder to open, or a video file to play. */
    data class Entry(
        val name: String,
        val path: String,          // relative path inside the public folder ("/clip.mp4")
        val isDir: Boolean,
        val isVideo: Boolean,
        val directUrl: String?,    // `file` field when present (lets us skip the /download call)
        val sizeBytes: Long,
        val preview: String?,
    )

    /** Result of browsing a public link at a given path. */
    data class Listing(
        val isFile: Boolean,            // the public link itself is a single file
        val fileName: String?,          // name when isFile
        val fileDirectUrl: String?,     // `file` of the single-file resource, if any
        val entries: List<Entry>,       // folder contents when !isFile (dirs first, then videos)
    )

    fun isYaDiskUrl(uri: Uri?): Boolean {
        val host = uri?.host?.lowercase() ?: return false
        return host == "yadi.sk" ||
                host.endsWith("disk.yandex.ru") || host.endsWith("disk.yandex.com") ||
                host.endsWith("disk.yandex.net") || host.endsWith("disk.yandex.kz") ||
                host.endsWith("disk.360.yandex.ru") || host.endsWith("disk.360.yandex.com")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGetJson(urlStr: String): JSONObject? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XPlayer2/1.0 (Android)")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }
        conn.disconnect()
        if (code in 200..299 && body != null) JSONObject(body)
        else { Log.w(TAG, "GET $urlStr -> HTTP $code: ${body?.take(200)}"); null }
    } catch (e: Exception) {
        Log.e(TAG, "GET $urlStr failed", e); null
    }

    private val VIDEO_EXTS = setOf(
        "mp4", "mkv", "mov", "avi", "webm", "m4v", "flv", "wmv",
        "mpg", "mpeg", "ts", "m2ts", "3gp", "ogv", "mts", "vob"
    )

    private fun looksVideo(name: String, mediaType: String?, mimeType: String?): Boolean {
        if (mediaType == "video") return true
        if (mimeType?.startsWith("video/") == true) return true
        return name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS
    }

    /**
     * Browse a public link. [publicKey] is the full share URL; [path] is the relative folder path
     * inside it (null/"/" = root). Folders paginate over every page. Returns null on network error.
     */
    suspend fun browse(publicKey: String, path: String?): Listing? = withContext(Dispatchers.IO) {
        val base = "$API?public_key=${enc(publicKey)}&limit=$PAGE&sort=name" +
                (if (!path.isNullOrEmpty() && path != "/") "&path=${enc(path)}" else "")
        val root = httpGetJson(base) ?: return@withContext null
        if (root.optString("type") == "file") {
            return@withContext Listing(
                isFile = true,
                fileName = root.optString("name").takeIf { it.isNotBlank() },
                fileDirectUrl = root.optString("file").takeIf { it.isNotBlank() },
                entries = emptyList(),
            )
        }
        val out = ArrayList<Entry>()
        var offset = 0
        var total = Int.MAX_VALUE
        var page: JSONObject? = root
        while (offset < total) {
            val emb = page?.optJSONObject("_embedded") ?: break
            total = emb.optInt("total", out.size)
            val items = emb.optJSONArray("items") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val name = it.optString("name")
                val isDir = it.optString("type") == "dir"
                val isVideo = !isDir && looksVideo(
                    name,
                    it.optString("media_type").takeIf { s -> s.isNotBlank() },
                    it.optString("mime_type").takeIf { s -> s.isNotBlank() },
                )
                if (!isDir && !isVideo) continue   // hide non-video files
                out.add(
                    Entry(
                        name = name,
                        path = it.optString("path"),
                        isDir = isDir,
                        isVideo = isVideo,
                        directUrl = it.optString("file").takeIf { s -> s.isNotBlank() },
                        sizeBytes = it.optLong("size", 0L),
                        preview = it.optString("preview").takeIf { s -> s.isNotBlank() },
                    )
                )
            }
            offset += items.length()
            if (offset >= total) break
            page = httpGetJson("$base&offset=$offset")
        }
        Listing(isFile = false, fileName = null, fileDirectUrl = null, entries = out)
    }

    /**
     * Resolve a short-lived, directly streamable href for a file (supports Range/seek). [path] is
     * the file's path inside a folder; pass null for a single-file (`/i/`) public link.
     */
    suspend fun resolveHref(publicKey: String, path: String?): String? = withContext(Dispatchers.IO) {
        val url = "$API/download?public_key=${enc(publicKey)}" +
                (if (!path.isNullOrEmpty()) "&path=${enc(path)}" else "")
        httpGetJson(url)?.optString("href")?.takeIf { it.isNotBlank() }
    }
}
