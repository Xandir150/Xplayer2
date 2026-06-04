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
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** One row in a public folder: a subfolder to open, or a video file to play. */
    data class Entry(
        val name: String,
        val path: String,          // relative path inside the public folder ("/clip.mp4")
        val isDir: Boolean,
        val isVideo: Boolean,
        val directUrl: String?,    // `file` field when present (lets us skip the /download call)
        val sizeBytes: Long,
        val preview: String?,
        val publicUrl: String?,    // durable per-file public link (yadi.sk/i/…) — the recents key
        val itemPublicKey: String?, // the item's signed public_key — used for the quality-streams hash
    )

    /** Result of browsing a public link at a given path. */
    data class Listing(
        val isFile: Boolean,            // the public link itself is a single file
        val fileName: String?,          // name when isFile
        val fileDirectUrl: String?,     // `file` of the single-file resource, if any
        val filePublicKey: String?,     // single-file resource's public_key (for quality streams)
        val filePath: String?,          // single-file resource's path ("/" typically)
        val entries: List<Entry>,       // folder contents when !isFile (dirs first, then videos)
    )

    fun isYaDiskUrl(uri: Uri?): Boolean {
        // Match ONLY public SHARE hosts (disk.yandex.* / yadi.sk), by EXACT host. This must NOT match
        // the resolved download host `downloader.disk.yandex.ru` — otherwise the player bounces a
        // resolved href straight back into this browser ("can't open"). `endsWith` did exactly that.
        val host = (uri?.host?.lowercase() ?: return false).removePrefix("www.")
        return host == "yadi.sk" ||
                host == "disk.yandex.ru" || host == "disk.yandex.com" ||
                host == "disk.yandex.kz" || host == "disk.yandex.by" ||
                host == "disk.360.yandex.ru" || host == "disk.360.yandex.com"
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
                filePublicKey = root.optString("public_key").takeIf { it.isNotBlank() },
                filePath = root.optString("path").takeIf { it.isNotBlank() } ?: "/",
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
                        publicUrl = it.optString("public_url").takeIf { s -> s.isNotBlank() },
                        itemPublicKey = it.optString("public_key").takeIf { s -> s.isNotBlank() },
                    )
                )
            }
            offset += items.length()
            if (offset >= total) break
            page = httpGetJson("$base&offset=$offset")
        }
        Listing(isFile = false, fileName = null, fileDirectUrl = null, filePublicKey = null, filePath = null, entries = out)
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

    /** One selectable quality of a video: a directly-playable HLS media-playlist URL + a label. */
    data class VideoQuality(val label: String, val url: String, val height: Int)

    /**
     * Fetch the per-quality HLS renditions for a public video — the way Yandex Disk's own web player
     * does it (the documented API exposes only the original). Two steps: scrape the `sk` CSRF token
     * from the public page (keeping its cookies), then POST the internal `get-video-streams` endpoint
     * with `hash = "<item public_key>:<path>"`. Returns qualities HIGHEST-FIRST (the "adaptive" master
     * is dropped — we expose explicit qualities like VK/OK), or empty on ANY failure so the caller
     * falls back to the original-file href. Internal/undocumented endpoint → wrapped defensively.
     */
    suspend fun getVideoStreams(shareUrl: String, itemPublicKey: String, path: String): List<VideoQuality> =
        withContext(Dispatchers.IO) {
            try {
                val page = httpGetText(shareUrl) ?: return@withContext emptyList()
                // environment.sk in the <script id="store-prefetch"> JSON. Scope to "environment" so we
                // don't grab some other "sk". The token is bound to the page's cookies (sent below).
                val sk = Regex("\"environment\"\\s*:\\s*\\{.*?\"sk\"\\s*:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
                    .find(page.body)?.groupValues?.get(1)
                    ?: return@withContext emptyList()
                val hash = "$itemPublicKey:$path"
                fun post(token: String) = httpPostJson(
                    "https://disk.yandex.ru/public/api/get-video-streams",
                    JSONObject().put("hash", hash).put("sk", token).toString(),
                    shareUrl, page.cookies
                )
                var json = post(sk)
                // An aged sk is rejected with a fresh one in the body — retry once with it.
                if (json != null && json.optBoolean("error", false) && json.has("newSk")) {
                    json = post(json.optString("newSk"))
                }
                if (json == null || json.optBoolean("error", false)) return@withContext emptyList()
                val videos = json.optJSONObject("data")?.optJSONArray("videos") ?: return@withContext emptyList()
                val out = ArrayList<VideoQuality>()
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val dim = v.optString("dimension")
                    val u = v.optString("url")
                    if (u.isBlank() || dim.isBlank() || dim == "adaptive") continue
                    out.add(VideoQuality(dim, u, v.optJSONObject("size")?.optInt("height", 0) ?: 0))
                }
                out.sortByDescending { it.height }   // highest first -> index 0 is the default (max)
                out
            } catch (e: Exception) {
                Log.w(TAG, "getVideoStreams failed", e); emptyList()
            }
        }

    private data class PageResult(val body: String, val cookies: String)

    private fun httpGetText(urlStr: String): PageResult? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000; readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Accept-Language", "ru,en;q=0.9")
        }
        val code = conn.responseCode
        val body = if (code in 200..299)
            BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() } else null
        // Reduce each Set-Cookie to its "name=value" and join — the sk token is bound to these.
        val cookies = conn.headerFields["Set-Cookie"]?.joinToString("; ") { it.substringBefore(';') } ?: ""
        conn.disconnect()
        if (body != null) PageResult(body, cookies) else null
    } catch (e: Exception) {
        Log.w(TAG, "GET page $urlStr failed", e); null
    }

    private fun httpPostJson(urlStr: String, body: String, referer: String, cookies: String): JSONObject? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000; readTimeout = 20000
            doOutput = true
            instanceFollowRedirects = true
            setRequestProperty("Content-Type", "text/plain")
            setRequestProperty("Referer", referer)
            setRequestProperty("Origin", "https://disk.yandex.ru")
            setRequestProperty("User-Agent", BROWSER_UA)
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies)
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val txt = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } }
        conn.disconnect()
        if (txt != null) JSONObject(txt) else null
    } catch (e: Exception) {
        Log.w(TAG, "POST $urlStr failed", e); null
    }
}
