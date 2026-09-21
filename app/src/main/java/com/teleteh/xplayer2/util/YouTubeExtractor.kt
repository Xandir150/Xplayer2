package com.teleteh.xplayer2.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Share-link playback only. Client constants checked against SmartTube 32.55 / MediaServiceCore:
 * https://github.com/yuliskov/MediaServiceCore/blob/8b4a884bf2501e4b1d6c75f2beb779fb8858332e/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/innertube/utils/Constants.kt
 * ANDROID_VR no longer reliably serves playable URLs. VISIONOS still exposes direct formats/HLS.
 * This deliberately does not implement authenticated playback, PO tokens, deciphering or SABR.
 */
internal object YouTubeExtractor {
    internal const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    private const val CLIENT_VERSION = "1.02"
    private const val VISITOR_TTL_MS = 30 * 60 * 1000L
    private data class Visitor(val value: String, val fetchedAt: Long)
    @Volatile private var visitor: Visitor? = null

    internal data class Format(val url: String, val mime: String, val height: Int, val bitrate: Int, val preferredAudio: Boolean)
    internal data class Candidates(val title: String?, val videos: List<Format>, val audios: List<Format>, val muxed: List<Format>, val hls: String?)

    fun extract(videoId: String): VideoStreamExtractor.ExtractedStream? {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return null
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/watch?v=$videoId",
            )
            var response = player(videoId, visitorData(), headers) ?: return null
            if (response.optJSONObject("playabilityStatus")?.optString("status") == "LOGIN_REQUIRED") {
                visitor = null
                response = player(videoId, visitorData(), headers) ?: return null
            }
            val candidates = parseCandidates(response) ?: return null
            // An OK player response can still contain CDN URLs returning 403. Validate the exact
            // selected video AND audio before handing them to Media3. Read at most 256 bytes.
            val video = candidates.videos.take(3).firstOrNull { probeMedia(it.url, headers) }
            if (video != null) {
                val audio = candidates.audios.take(3).firstOrNull { probeMedia(it.url, headers) }
                if (audio != null) return VideoStreamExtractor.ExtractedStream(
                    url = video.url, title = candidates.title, quality = "${video.height}p",
                    audioUrl = audio.url, headers = headers,
                )
            }
            candidates.muxed.take(2).firstOrNull { probeMedia(it.url, headers) }?.let {
                return VideoStreamExtractor.ExtractedStream(it.url, candidates.title, "${it.height}p", headers = headers)
            }
            candidates.hls?.takeIf { probeHls(it, headers) }?.let {
                return VideoStreamExtractor.ExtractedStream(it, candidates.title, "Auto", headers = headers)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseCandidates(response: JSONObject): Candidates? {
        if (response.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
        val data = response.optJSONObject("streamingData") ?: return null
        val adaptive = formats(data.optJSONArray("adaptiveFormats"))
        val videos = adaptive.filter { it.mime.startsWith("video/") && it.height in 1..1080 }
            .sortedWith(compareByDescending<Format> { it.mime.contains("avc1") }.thenByDescending { it.height }.thenByDescending { it.bitrate })
        val audios = adaptive.filter { it.mime.startsWith("audio/") }
            .sortedWith(compareByDescending<Format> { it.preferredAudio }.thenByDescending { it.mime.contains("mp4a") }.thenByDescending { it.bitrate })
        val muxed = formats(data.optJSONArray("formats")).filter { it.height in 1..1080 }
            .sortedWith(compareByDescending<Format> { it.mime.contains("avc1") }.thenByDescending { it.height }.thenByDescending { it.bitrate })
        return Candidates(
            response.optJSONObject("videoDetails")?.optString("title")?.takeIf { it.isNotBlank() },
            videos, audios, muxed, data.optString("hlsManifestUrl").takeIf(::isHttpsUrl),
        )
    }

    private fun formats(array: JSONArray?): List<Format> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
        val item = array?.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optString("url").takeIf(::isHttpsUrl) ?: return@mapNotNull null
        val audio = item.optJSONObject("audioTrack")
        Format(url, item.optString("mimeType"), item.optInt("height"), item.optInt("bitrate"),
            audio == null || audio.optBoolean("audioIsDefault") || audio.optString("displayName").contains("original", ignoreCase = true))
    }

    internal fun isHttpsUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) { false }

    private fun player(videoId: String, visitorData: String?, headers: Map<String, String>): JSONObject? {
        val client = JSONObject().apply {
            put("clientName", "VISIONOS"); put("clientVersion", CLIENT_VERSION)
            put("deviceMake", "Apple"); put("deviceModel", "RealityDevice17,1")
            put("osName", "visionOS"); put("osVersion", "26.5.23O471")
            put("hl", "en"); put("gl", "US"); put("userAgent", USER_AGENT)
            if (visitorData != null) put("visitorData", visitorData)
        }
        val body = JSONObject().put("context", JSONObject().put("client", client))
            .put("videoId", videoId).put("contentCheckOk", true).put("racyCheckOk", true).toString()
        val requestHeaders = headers + mapOf("Content-Type" to "application/json", "X-YouTube-Client-Name" to "101", "X-YouTube-Client-Version" to CLIENT_VERSION) +
            (visitorData?.let { mapOf("X-Goog-Visitor-Id" to it) } ?: emptyMap())
        val text = requestText("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", requestHeaders, 2 * 1024 * 1024, body) ?: return null
        return JSONObject(text)
    }

    @Synchronized private fun visitorData(): String? {
        val now = System.currentTimeMillis()
        visitor?.takeIf { now - it.fetchedAt in 0 until VISITOR_TTL_MS }?.let { return it.value }
        return try {
            val html = requestText("https://www.youtube.com/", mapOf("User-Agent" to USER_AGENT, "Accept-Language" to "en-US,en;q=0.9"), 1024 * 1024, allowTruncation = true) ?: return null
            val encoded = Regex("\"visitorData\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: return null
            JSONObject("{\"value\":\"$encoded\"}").getString("value").also { visitor = Visitor(it, now) }
        } catch (_: Exception) { null }
    }

    private fun connection(url: String, headers: Map<String, String>): HttpURLConnection {
        require(isHttpsUrl(url))
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000; readTimeout = 12_000; instanceFollowRedirects = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
    }

    private fun requestText(url: String, headers: Map<String, String>, limit: Int, body: String? = null, allowTruncation: Boolean = false): String? {
        val conn = connection(url, headers)
        return try {
            if (body != null) {
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            if (conn.responseCode !in 200..299 || !isHttpsUrl(conn.url.toString())) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (out.size() < limit) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                    if (count < 0) break
                    out.write(buffer, 0, count)
                }
                if (!allowTruncation && out.size() == limit && input.read() != -1) return null
                out.toString("UTF-8")
            }
        } finally { conn.disconnect() }
    }

    private fun probeMedia(url: String, headers: Map<String, String>): Boolean = try {
        val conn = connection(url, headers + ("Range" to "bytes=0-255"))
        try {
            if (conn.responseCode !in listOf(200, 206) || !isHttpsUrl(conn.url.toString())) false
            else if (conn.contentType?.contains("text/html", ignoreCase = true) == true) false
            else conn.inputStream.use { it.read(ByteArray(256)) > 0 }
        } finally { conn.disconnect() }
    } catch (_: Exception) { false }

    private fun probeHls(url: String, headers: Map<String, String>, depth: Int = 0): Boolean {
        return try {
        if (depth > 2) false else {
            val manifest = requestText(url, headers, 256 * 1024) ?: return false
            if (!manifest.trimStart().startsWith("#EXTM3U")) return false
            val firstUri = manifest.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return false
            val child = URI(url).resolve(firstUri).toString()
            if (!isHttpsUrl(child)) false
            else if (manifest.contains("#EXT-X-STREAM-INF:")) probeHls(child, headers, depth + 1)
            else probeMedia(child, headers)
        }
        } catch (_: Exception) { false }
    }
}
