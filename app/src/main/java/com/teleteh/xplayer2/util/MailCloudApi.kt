package com.teleteh.xplayer2.util

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for Mail.ru Cloud (Облако Mail.ru) PUBLIC share links (no login/token needed).
 *
 * A public link is `https://cloud.mail.ru/public/<a>/<b>` — the part after `/public/` is the
 * "weblink" (always ≥2 path segments). It can point at a single FILE or a FOLDER; we branch on the
 * `type` the listing API returns:
 *   - "file"   -> the link itself is one video; resolve its stream URL and play it
 *   - "folder" -> enumerate `list[]` (subfolders to drill into + video files to play)
 *
 * Every item carries its OWN full weblink (`<a>/<b>/sub/clip.mp4`), so navigation is just "browse a
 * weblink"; there is no separate path param. Stream URLs are built from a short-lived dispatcher
 * shard, so we resolve them lazily right before playback (and the public file is served with Range
 * support, so ExoPlayer can seek). No auth, no cookies, plain browser GETs.
 *
 * Endpoints (all public, verified live): `api/v4/public/list` (listing), `api/v2/folder` (fallback),
 * `api/v3/dispatcher` (the `weblink_get` shard host). v4 requires no auth; v4-for-download requires
 * auth, which is why we use the v3 dispatcher.
 */
object MailCloudApi {

    private const val TAG = "MailCloudApi"
    private const val LIST_V4 = "https://cloud.mail.ru/api/v4/public/list"
    private const val LIST_V2 = "https://cloud.mail.ru/api/v2/folder"
    private const val DISPATCHER = "https://cloud.mail.ru/api/v3/dispatcher"
    private const val PAGE = 500
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** One row in a public folder: a subfolder to open, or a video file to play. */
    data class Entry(
        val name: String,
        val weblink: String,   // this item's OWN full public weblink (key/.../name)
        val isDir: Boolean,
        val isVideo: Boolean,
        val sizeBytes: Long,
    )

    /** Result of browsing a public weblink. */
    data class Listing(
        val isFile: Boolean,        // the public link itself is a single file
        val fileName: String?,      // name when isFile
        val fileWeblink: String?,   // weblink of the single file (to resolve its stream)
        val entries: List<Entry>,   // folder contents when !isFile (dirs first, then videos)
        val folderName: String? = null, // the folder's own display name (when !isFile)
    )

    /** True for `https://cloud.mail.ru/public/<a>/<b>…` public share links. */
    fun isMailCloudUrl(uri: Uri?): Boolean {
        val host = (uri?.host?.lowercase() ?: return false).removePrefix("www.")
        if (host != "cloud.mail.ru") return false
        val segs = uri.pathSegments
        return segs.size >= 3 && segs[0].equals("public", ignoreCase = true)
    }

    /** The weblink (everything after `/public/`) for a share URL, or null if it isn't one. */
    fun weblinkFromUrl(rawUrl: String): String? {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
        if (!isMailCloudUrl(uri)) return null
        return uri.pathSegments.drop(1).joinToString("/")   // drop the leading "public"
    }

    /** The durable public URL for a weblink — the recents key (round-trips back through us). */
    fun publicUrl(weblink: String): String = "https://cloud.mail.ru/public/$weblink"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Per-segment encode for the shard path: keep `/`, and spaces as %20 (never `+`). */
    private fun encSeg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun httpGetJson(urlStr: String): JSONObject? = try {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", BROWSER_UA)
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

    private fun looksVideo(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

    /** v4 returns the node directly; v2 wraps it in `body`. Accept either. */
    private fun unwrap(root: JSONObject): JSONObject = root.optJSONObject("body") ?: root

    private fun fetchV4(weblink: String, offset: Int): JSONObject? = httpGetJson(
        "$LIST_V4?weblink=${enc(weblink)}&sort=name&order=asc&offset=$offset&limit=$PAGE&version=4"
    )

    private fun addEntry(out: MutableList<Entry>, it: JSONObject) {
        // v4 uses "type"; the v2 fallback uses "kind" — accept either.
        val type = it.optString("type").ifBlank { it.optString("kind") }
        val isDir = type == "folder"
        val name = it.optString("name")
        if (name.isBlank()) return
        val isVideo = !isDir && looksVideo(name)
        if (!isDir && !isVideo) return   // hide non-video files
        out.add(Entry(name, it.optString("weblink"), isDir, isVideo, it.optLong("size", 0L)))
    }

    /**
     * Browse a public [weblink] (the part after `/public/`). Paginates folders fully. Returns null on
     * network error. Tries v4 first, falls back to the legacy v2 folder endpoint.
     */
    suspend fun browse(weblink: String): Listing? = withContext(Dispatchers.IO) {
        val first = fetchV4(weblink, 0) ?: return@withContext browseV2(weblink)
        val node = unwrap(first)
        if (node.optString("type") == "file") {
            return@withContext Listing(
                isFile = true,
                fileName = node.optString("name").takeIf { it.isNotBlank() },
                fileWeblink = node.optString("weblink").takeIf { it.isNotBlank() } ?: weblink,
                entries = emptyList(),
            )
        }
        val out = ArrayList<Entry>()
        var page: JSONObject? = node
        var offset = 0
        while (page != null) {
            val arr = page.optJSONArray("list") ?: break
            val n = arr.length()
            for (i in 0 until n) arr.optJSONObject(i)?.let { o -> addEntry(out, o) }
            if (n < PAGE) break
            offset += n
            page = fetchV4(weblink, offset)?.let { unwrap(it) }
        }
        Listing(
            isFile = false, fileName = null, fileWeblink = null, entries = out,
            folderName = node.optString("name").takeIf { it.isNotBlank() },
        )
    }

    /** Legacy v2 folder listing (wrapped in `{status, body}`), used only if v4 fails. */
    private fun browseV2(weblink: String): Listing? {
        val root = httpGetJson("$LIST_V2?weblink=${enc(weblink)}&offset=0&limit=$PAGE&api=2") ?: return null
        if (root.optInt("status", 200) !in 200..299) return null
        val body = root.optJSONObject("body") ?: return null
        val type = body.optString("type").ifBlank { body.optString("kind") }
        if (type == "file") {
            return Listing(true, body.optString("name").takeIf { it.isNotBlank() }, weblink, emptyList())
        }
        val arr = body.optJSONArray("list") ?: JSONArray()
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEntry(out, it) }
        return Listing(false, null, null, out, body.optString("name").takeIf { it.isNotBlank() })
    }

    /**
     * Resolve a directly streamable URL for a file [weblink] (supports Range/seek). Built from a
     * fresh dispatcher shard each call — the shard token is short-lived, so resolve at play time.
     * The returned URL 301-redirects to the datacloud host; ExoPlayer follows it. Null on error.
     */
    suspend fun resolveHref(weblink: String): String? = withContext(Dispatchers.IO) {
        val shard = fetchShard() ?: return@withContext null
        val encoded = weblink.split("/").joinToString("/") { encSeg(it) }
        "$shard/$encoded"
    }

    /** The `weblink_get` shard host (e.g. `https://cloclo52.cloud.mail.ru/public/<tok>/g/no`). */
    private fun fetchShard(): String? {
        val d = httpGetJson(DISPATCHER) ?: return null
        val body = d.optJSONObject("body") ?: d
        return body.optJSONArray("weblink_get")?.optJSONObject(0)
            ?.optString("url")?.takeIf { it.isNotBlank() }
    }
}
