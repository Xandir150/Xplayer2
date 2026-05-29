package com.teleteh.xplayer2.util

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Extracts direct video stream URLs from various video hosting services.
 * Supported: ok.ru, vkvideo.ru, vk.com/video
 */
object VideoStreamExtractor {

    private const val TAG = "VideoStreamExtractor"

    data class ExtractedStream(
        val url: String,
        val title: String? = null,
        val quality: String? = null
    )

    /**
     * Checks if the given URI is from a supported video hosting service.
     */
    fun isSupported(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host.contains("ok.ru") ||
                host.contains("vkvideo.ru") ||
                (host.contains("vk.com") && uri.path?.contains("video") == true)
    }

    /**
     * Extracts the direct video stream URL from a supported hosting service.
     * Returns null if extraction fails or the service is not supported.
     */
    suspend fun extract(uri: Uri): ExtractedStream? = withContext(Dispatchers.IO) {
        val host = uri.host?.lowercase() ?: return@withContext null
        Log.d(TAG, "Attempting to extract stream from: $uri (host=$host)")
        try {
            val result = when {
                host.contains("ok.ru") -> extractOkRu(uri)
                host.contains("vkvideo.ru") -> extractVkVideo(uri)
                host.contains("vk.com") && uri.path?.contains("video") == true -> extractVkVideo(uri)
                else -> null
            }
            if (result != null) {
                Log.i(TAG, "Successfully extracted: ${result.url} (title=${result.title}, quality=${result.quality})")
            } else {
                Log.w(TAG, "Failed to extract stream from $uri")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract stream from $uri", e)
            null
        }
    }

    /**
     * Extracts video stream from ok.ru
     * Example URL: https://m.ok.ru/video/88843291236
     */
    private suspend fun extractOkRu(uri: Uri): ExtractedStream? = withContext(Dispatchers.IO) {
        val videoId = extractOkRuVideoId(uri) ?: run {
            Log.w(TAG, "Could not extract video ID from ok.ru URL: $uri")
            return@withContext null
        }
        Log.d(TAG, "Extracting ok.ru video: $videoId")

        // Try mobile page first (often has simpler structure)
        var html = fetchPage("https://m.ok.ru/video/$videoId")
        if (html == null) {
            // Fallback to desktop page
            html = fetchPage("https://ok.ru/video/$videoId")
        }
        if (html == null) {
            Log.w(TAG, "Failed to fetch ok.ru page for video $videoId")
            return@withContext null
        }
        
        Log.d(TAG, "Fetched ok.ru page, length=${html.length}")

        // Method 1: Try to find data-options JSON in the page
        val optionsPattern = Pattern.compile("data-options=[\"']([^\"']+)[\"']", Pattern.DOTALL)
        val optionsMatcher = optionsPattern.matcher(html)
        
        if (optionsMatcher.find()) {
            val optionsEncoded = optionsMatcher.group(1) ?: ""
            Log.d(TAG, "Found data-options, length=${optionsEncoded.length}")
            val optionsJson = decodeHtmlEntities(optionsEncoded)
            
            try {
                val json = JSONObject(optionsJson)
                val flashvars = json.optJSONObject("flashvars")
                val metadataStr = flashvars?.optString("metadata")
                if (!metadataStr.isNullOrBlank()) {
                    val metaJson = JSONObject(metadataStr)
                    val title = metaJson.optString("title")
                    val videos = metaJson.optJSONArray("videos")
                    
                    val stream = extractBestVideoFromArray(videos, title)
                    if (stream != null) return@withContext stream
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse ok.ru data-options JSON", e)
            }
        }

        // Method 2: Try to find st.vv.data JSON
        val vvDataPattern = Pattern.compile("st\\.vv\\.data\\s*=\\s*(\\{[^;]+\\});", Pattern.DOTALL)
        val vvDataMatcher = vvDataPattern.matcher(html)
        if (vvDataMatcher.find()) {
            val vvDataJson = vvDataMatcher.group(1) ?: ""
            Log.d(TAG, "Found st.vv.data, length=${vvDataJson.length}")
            try {
                val json = JSONObject(vvDataJson)
                val videos = json.optJSONArray("videos")
                val title = json.optString("title")
                val stream = extractBestVideoFromArray(videos, title)
                if (stream != null) return@withContext stream
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse st.vv.data JSON", e)
            }
        }

        // Method 3: Try to find metadata JSON directly
        val metadataPattern = Pattern.compile("\"metadata\"\\s*:\\s*\"(\\{[^\"]+\\})\"")
        val metadataMatcher = metadataPattern.matcher(html)
        if (metadataMatcher.find()) {
            val metadataEncoded = metadataMatcher.group(1) ?: ""
            val metadataJson = metadataEncoded
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\\u0026", "&")
            Log.d(TAG, "Found metadata JSON, length=${metadataJson.length}")
            try {
                val json = JSONObject(metadataJson)
                val videos = json.optJSONArray("videos")
                val title = json.optString("title")
                val stream = extractBestVideoFromArray(videos, title)
                if (stream != null) return@withContext stream
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse metadata JSON", e)
            }
        }

        // Method 4: Fallback - search for video URLs directly in page
        val stream = extractVideoUrlsFromHtml(html)
        if (stream != null) return@withContext stream

        Log.w(TAG, "No video URLs found in ok.ru page")
        null
    }
    
    private fun extractBestVideoFromArray(videos: JSONArray?, title: String?): ExtractedStream? {
        if (videos == null || videos.length() == 0) return null
        
        // Quality priority (highest first)
        val qualityOrder = listOf("full", "ultra", "quad", "hd", "sd", "low", "lowest", "mobile")
        var bestUrl: String? = null
        var bestQuality: String? = null
        var bestPriority = Int.MAX_VALUE
        
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val url = video.optString("url").takeIf { it.isNotBlank() } ?: continue
            val name = video.optString("name").lowercase()
            
            val priority = qualityOrder.indexOf(name).takeIf { it >= 0 } ?: (qualityOrder.size + i)
            if (priority < bestPriority) {
                bestPriority = priority
                bestUrl = url
                bestQuality = name
            }
        }
        
        return bestUrl?.let { 
            ExtractedStream(it, title?.takeIf { t -> t.isNotBlank() }, bestQuality) 
        }
    }
    
    private fun extractVideoUrlsFromHtml(html: String): ExtractedStream? {
        // Try HLS first (usually better quality)
        val hlsPatterns = listOf(
            Pattern.compile("\"(https?://[^\"]*?\\.m3u8[^\"]*?)\""),
            Pattern.compile("'(https?://[^']*?\\.m3u8[^']*?)'"),
            Pattern.compile("(https?://[^\\s\"'<>]*?\\.m3u8[^\\s\"'<>]*)")
        )
        for (pattern in hlsPatterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val url = decodeUrl(matcher.group(1) ?: continue)
                if (isValidVideoUrl(url)) {
                    Log.d(TAG, "Found HLS URL: $url")
                    return ExtractedStream(url, null, "hls")
                }
            }
        }
        
        // Try MP4
        val mp4Patterns = listOf(
            Pattern.compile("\"(https?://[^\"]*?\\.mp4[^\"]*?)\""),
            Pattern.compile("'(https?://[^']*?\\.mp4[^']*?)'")
        )
        for (pattern in mp4Patterns) {
            val matcher = pattern.matcher(html)
            while (matcher.find()) {
                val url = decodeUrl(matcher.group(1) ?: continue)
                if (isValidVideoUrl(url) && !url.contains("preview") && !url.contains("thumb")) {
                    Log.d(TAG, "Found MP4 URL: $url")
                    return ExtractedStream(url, null, "mp4")
                }
            }
        }
        
        return null
    }
    
    private fun isValidVideoUrl(url: String): Boolean {
        return url.startsWith("http") && 
               (url.contains(".mp4") || url.contains(".m3u8")) &&
               !url.contains("preview") &&
               !url.contains("thumbnail") &&
               !url.contains("poster")
    }
    
    private fun decodeUrl(url: String): String {
        return url
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\/", "/")
            .replace("&amp;", "&")
    }
    
    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\/", "/")
    }

    /**
     * Extracts video stream from vkvideo.ru or vk.com/video
     * Example URL: https://vkvideo.ru/video-225720479_456239202
     * 
     * Primary method: Use embed player page (video_ext.php) which contains HLS URLs directly
     */
    private suspend fun extractVkVideo(uri: Uri): ExtractedStream? = withContext(Dispatchers.IO) {
        val videoId = extractVkVideoId(uri) ?: run {
            Log.w(TAG, "Could not extract video ID from VK URL: $uri")
            return@withContext null
        }
        Log.d(TAG, "Extracting VK video: $videoId")

        // Parse owner_id and video_id from format: -225720479_456239202
        val parts = videoId.split("_")
        if (parts.size != 2) {
            Log.w(TAG, "Invalid VK video ID format: $videoId")
            return@withContext null
        }
        val ownerId = parts[0]
        val id = parts[1]

        // Method 1 (Primary): al_video.php XHR endpoint.
        // VK no longer ships stream URLs inside video_ext.php — that page became an empty
        // JS shell, which is why the old HTML scraping started returning nothing. The web
        // player now pulls them from al_video.php, so we replicate that request. A desktop
        // UA is required (a mobile UA gets a stripped payload with no player params). The
        // signed CDN URLs are IP-locked but not UA-locked, so ExoPlayer on the same device
        // plays them fine.
        extractVkViaApi("${ownerId}_$id")?.let { return@withContext it }
        Log.d(TAG, "VK al_video.php yielded nothing, falling back to legacy HTML scraping")

        // Method 2 (Legacy fallback): embed player page HTML scraping.
        // The embed page used to contain HLS URLs directly in the HTML.
        val embedUrl = "https://vk.com/video_ext.php?oid=$ownerId&id=$id&hash=0"
        Log.d(TAG, "Trying VK embed page: $embedUrl")
        val embedHtml = fetchPage(embedUrl)
        
        if (embedHtml != null) {
            Log.d(TAG, "Fetched VK embed page, length=${embedHtml.length}")
            
            // Extract HLS URL - pattern: "hls":"https:\/\/..."
            val hlsPattern = Pattern.compile("\"hls\"\\s*:\\s*\"([^\"]+)\"")
            val hlsMatcher = hlsPattern.matcher(embedHtml)
            if (hlsMatcher.find()) {
                val hlsUrl = decodeUrl(hlsMatcher.group(1) ?: "")
                if (hlsUrl.isNotBlank()) {
                    // Try to extract title from various fields
                    val title = extractTitleFromHtml(embedHtml)
                    Log.i(TAG, "Extracted VK HLS from embed: $hlsUrl, title=$title")
                    return@withContext ExtractedStream(hlsUrl, title, "hls")
                }
            }
            
            // Try MP4 URLs from embed page
            val mp4Pattern = Pattern.compile("\"url(\\d+)\"\\s*:\\s*\"([^\"]+)\"")
            val mp4Matcher = mp4Pattern.matcher(embedHtml)
            val videos = mutableMapOf<Int, String>()
            while (mp4Matcher.find()) {
                val quality = mp4Matcher.group(1)?.toIntOrNull() ?: continue
                val url = mp4Matcher.group(2) ?: continue
                videos[quality] = decodeUrl(url)
            }
            if (videos.isNotEmpty()) {
                val bestQuality = videos.keys.maxOrNull()!!
                val bestUrl = videos[bestQuality]!!
                val title = extractTitleFromHtml(embedHtml)
                Log.i(TAG, "Extracted VK MP4 from embed: quality=${bestQuality}p, title=$title")
                return@withContext ExtractedStream(bestUrl, title, "${bestQuality}p")
            }
        }

        // Method 3: Try regular video page as fallback
        val urls = listOf(
            "https://vk.com/video${ownerId}_$id"
        )
        
        var html: String? = null
        for (pageUrl in urls) {
            html = fetchPage(pageUrl)
            if (html != null) {
                Log.d(TAG, "Fetched VK page from $pageUrl, length=${html.length}")
                break
            }
        }
        
        if (html != null) {
            // Try to find HLS URL in page
            val hlsPattern = Pattern.compile("\"hls\"\\s*:\\s*\"([^\"]+)\"")
            val hlsMatcher = hlsPattern.matcher(html)
            if (hlsMatcher.find()) {
                val hlsUrl = decodeUrl(hlsMatcher.group(1) ?: "")
                if (hlsUrl.isNotBlank() && hlsUrl.contains("m3u8")) {
                    Log.i(TAG, "Found VK HLS in page: $hlsUrl")
                    return@withContext ExtractedStream(hlsUrl, null, "hls")
                }
            }
            
            // Try direct MP4 URLs
            val mp4Pattern = Pattern.compile("\"url(\\d+)\"\\s*:\\s*\"([^\"]+)\"")
            val mp4Matcher = mp4Pattern.matcher(html)
            val videos = mutableMapOf<Int, String>()
            while (mp4Matcher.find()) {
                val quality = mp4Matcher.group(1)?.toIntOrNull() ?: continue
                val url = mp4Matcher.group(2) ?: continue
                if (!url.contains("preview") && !url.contains("thumb")) {
                    videos[quality] = decodeUrl(url)
                }
            }
            if (videos.isNotEmpty()) {
                val bestQuality = videos.keys.maxOrNull()!!
                val bestUrl = videos[bestQuality]!!
                Log.i(TAG, "Found VK MP4 in page: quality=${bestQuality}p")
                return@withContext ExtractedStream(bestUrl, null, "${bestQuality}p")
            }
            
            // Fallback - search for any video URLs
            val stream = extractVideoUrlsFromHtml(html)
            if (stream != null) return@withContext stream
        }

        Log.w(TAG, "No video URLs found in VK page")
        null
    }
    
    /**
     * Extract title from HTML page trying various patterns
     */
    private fun extractTitleFromHtml(html: String): String? {
        // First, specifically look for md_title which has the video name
        val mdTitlePattern = Pattern.compile("\"md_title\"\\s*:\\s*\"([^\"]*?)\"")
        val mdMatcher = mdTitlePattern.matcher(html)
        if (mdMatcher.find()) {
            val rawMdTitle = mdMatcher.group(1) ?: ""
            Log.d(TAG, "md_title raw value: '$rawMdTitle' (length=${rawMdTitle.length})")
            if (rawMdTitle.isNotBlank()) {
                // Try to fix encoding: windows-1251 read as ISO-8859-1
                val fixedTitle = tryFixEncoding(rawMdTitle)
                val decoded = decodeUnicodeEscapes(fixedTitle)
                Log.d(TAG, "md_title fixed: '$fixedTitle', decoded: '$decoded'")
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }
        } else {
            Log.d(TAG, "md_title not found in HTML")
        }
        
        // Fallback patterns
        val patterns = listOf(
            "\"video_title\"\\s*:\\s*\"([^\"]+)\"",
            "\"title\"\\s*:\\s*\"([^\"]+)\"",
            "og:title\"\\s+content=\"([^\"]+)\""
        )
        
        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(html)
            if (matcher.find()) {
                val rawTitle = matcher.group(1) ?: continue
                // Skip empty or placeholder titles
                if (rawTitle.isBlank() || rawTitle == "null" || rawTitle.endsWith(".vtt") || 
                    rawTitle == "Video embed" || rawTitle.length < 3) continue
                
                val decoded = decodeUnicodeEscapes(rawTitle)
                Log.d(TAG, "Found title with pattern '$patternStr': '$decoded'")
                if (decoded.isNotBlank() && decoded.length > 2) {
                    return decoded
                }
            }
        }
        return null
    }
    
    /**
     * Try to fix encoding issues - convert from windows-1251 (read as ISO-8859-1) to UTF-8
     */
    private fun tryFixEncoding(text: String): String {
        return try {
            // If text contains high-byte characters that look like windows-1251 read as latin1
            val bytes = text.toByteArray(Charsets.ISO_8859_1)
            val fixed = String(bytes, charset("windows-1251"))
            Log.d(TAG, "Encoding fix: '$text' -> '$fixed'")
            fixed
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fix encoding", e)
            text
        }
    }
    
    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        
        // First, handle \\uXXXX (double-escaped)
        val doubleEscaped = Pattern.compile("\\\\\\\\u([0-9a-fA-F]{4})")
        var matcher = doubleEscaped.matcher(result)
        var sb = StringBuffer()
        while (matcher.find()) {
            val code = matcher.group(1)?.toIntOrNull(16) ?: continue
            val replacement = Regex.escapeReplacement(code.toChar().toString())
            matcher.appendReplacement(sb, replacement)
        }
        matcher.appendTail(sb)
        result = sb.toString()
        
        // Then handle \uXXXX (single-escaped)
        val singleEscaped = Pattern.compile("\\\\u([0-9a-fA-F]{4})")
        matcher = singleEscaped.matcher(result)
        sb = StringBuffer()
        while (matcher.find()) {
            val code = matcher.group(1)?.toIntOrNull(16) ?: continue
            val replacement = Regex.escapeReplacement(code.toChar().toString())
            matcher.appendReplacement(sb, replacement)
        }
        matcher.appendTail(sb)
        result = sb.toString()
        
        // Also decode HTML entities
        result = result
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        
        return result.trim()
    }
    
    /**
     * Primary VK extraction: POST to the al_video.php XHR endpoint the web player uses.
     * Response envelope (windows-1251): {"payload":[code, [title, html, ..., opts]]},
     * with stream URLs in opts.player.params[0] (hls_ondemand / urlNNNN).
     */
    private fun extractVkViaApi(videoFull: String): ExtractedStream? {
        val json = postAlVideo("act=show&video=$videoFull&al=1") ?: return null
        val payload = json.optJSONArray("payload")
        if (payload == null) {
            Log.w(TAG, "VK al_video: response has no payload array")
            return null
        }
        // payload = [code, inner]; code is 0 (Int) on success, a string (e.g. "8") on error.
        val codeOk = when (val c = payload.opt(0)) {
            is Number -> c.toInt() == 0
            is String -> c == "0"
            else -> false
        }
        val inner = payload.optJSONArray(1)
        if (!codeOk || inner == null) {
            Log.w(TAG, "VK al_video: error response (code=${payload.opt(0)})")
            return null
        }
        val opts = inner.optJSONObject(inner.length() - 1)
        if (opts == null) {
            Log.w(TAG, "VK al_video: payload has no opts object")
            return null
        }
        if (opts.optInt("player_unavailable", 0) == 1) {
            Log.w(TAG, "VK al_video: player_unavailable for $videoFull")
            return null
        }
        val p = opts.optJSONObject("player")?.optJSONArray("params")?.optJSONObject(0)
        if (p == null) {
            Log.w(TAG, "VK al_video: no player.params[0] in response")
            return null
        }
        val title = p.optString("md_title").takeIf { it.isNotBlank() }
            ?: p.optString("md_author").takeIf { it.isNotBlank() }
        return extractVkPlayerParams(p, title)
    }

    /** Pick the best playable stream from a VK player params[0] object. */
    private fun extractVkPlayerParams(p: JSONObject, title: String?): ExtractedStream? {
        // Adaptive HLS first: one URL carries every quality + audio track and plays via
        // the media3 HLS module. (dash_ondemand is skipped on purpose — no DASH module.)
        for (key in listOf("hls_ondemand", "hls")) {
            val u = p.optString(key).takeIf { it.startsWith("http") }
            if (u != null) {
                Log.i(TAG, "VK HLS extracted (title=$title)")
                return ExtractedStream(decodeUrl(u), title, "hls")
            }
        }
        // Progressive MP4 fallback, highest resolution first.
        for (q in listOf("url2160", "url1440", "url1080", "url720", "url480", "url360", "url240")) {
            val u = p.optString(q).takeIf { it.startsWith("http") }
            if (u != null) {
                val label = q.removePrefix("url") + "p"
                Log.i(TAG, "VK MP4 $label extracted (title=$title)")
                return ExtractedStream(decodeUrl(u), title, label)
            }
        }
        Log.w(TAG, "VK al_video: params[0] had no hls/url* fields")
        return null
    }

    private fun postAlVideo(body: String): JSONObject? {
        return try {
            Log.d(TAG, "POST al_video.php ($body)")
            val conn = (URL("https://vk.com/al_video.php").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                // Desktop UA: a mobile UA returns a stripped payload with no player params.
                setRequestProperty("User-Agent", API_USER_AGENT)
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("Referer", "https://vk.com/al_video.php")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                setRequestProperty("Accept-Encoding", "identity")
                instanceFollowRedirects = true
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
            val code = conn.responseCode
            Log.d(TAG, "al_video.php HTTP $code")
            if (code !in 200..299) {
                Log.w(TAG, "al_video.php returned HTTP $code")
                return null
            }
            // VK serves this JSON as windows-1251; decode directly so Cyrillic titles stay correct.
            val text = conn.inputStream.bufferedReader(charset("windows-1251")).use { it.readText() }
            val start = text.indexOf('{')
            JSONObject(if (start > 0) text.substring(start) else text)
        } catch (e: Exception) {
            Log.e(TAG, "al_video.php POST failed", e)
            null
        }
    }

    /** One video in a VK owner's library, as returned by the al_video.php video list. */
    data class VkVideoItem(
        val url: String,        // canonical vkvideo.ru URL — feeds straight back into [extract]
        val title: String,
        val thumbnailUrl: String?,
        val duration: String?,
    )

    /**
     * List an owner's (user/group) videos via al_video.php `load_videos_silent`, optionally
     * keeping only titles containing [titleContains] (case-insensitive). Paginates through the
     * whole library. [ownerId] is the numeric owner id (negative for groups, e.g. -225720479).
     *
     * Each list row is a flat array: [0]=ownerId, [1]=videoId, [2]=thumbnail, [3]=title,
     * [5]=duration. The envelope is payload[1][0]["all"] = {list, count, total} (windows-1251).
     */
    suspend fun listOwnerVideos(
        ownerId: String,
        titleContains: String? = null,
        maxItems: Int = 1000,
    ): List<VkVideoItem> = withContext(Dispatchers.IO) {
        val needle = titleContains?.lowercase()
        val out = mutableListOf<VkVideoItem>()
        var offset = 0
        var total = Int.MAX_VALUE
        var guard = 0
        while (offset < total && out.size < maxItems && guard++ < 40) {
            val json = postAlVideo("act=load_videos_silent&al=1&offset=$offset&oid=$ownerId&section=all")
                ?: break
            val inner = json.optJSONArray("payload")?.optJSONArray(1) ?: break
            val all = inner.optJSONObject(0)?.optJSONObject("all") ?: break
            total = all.optInt("total", out.size)
            val list = all.optJSONArray("list") ?: break
            val pageCount = all.optInt("count", list.length())
            if (list.length() == 0) break
            for (i in 0 until list.length()) {
                val v = list.optJSONArray(i) ?: continue
                val oid = v.optLong(0)
                val vid = v.optLong(1)
                if (oid == 0L || vid == 0L) continue
                val title = v.optString(3).trim()
                if (needle != null && !title.lowercase().contains(needle)) continue
                out.add(
                    VkVideoItem(
                        url = "https://vkvideo.ru/video${oid}_$vid",
                        title = title.ifBlank { "video${oid}_$vid" },
                        thumbnailUrl = v.optString(2).takeIf { it.startsWith("http") },
                        duration = v.optString(5).takeIf { it.isNotBlank() },
                    )
                )
            }
            offset += pageCount.coerceAtLeast(1)
            if (pageCount == 0) break
        }
        Log.i(TAG, "listOwnerVideos(oid=$ownerId, filter=$titleContains) -> ${out.size} items")
        out
    }

    private fun extractOkRuVideoId(uri: Uri): String? {
        // https://m.ok.ru/video/88843291236 or https://ok.ru/video/88843291236
        val path = uri.path ?: return null
        val pattern = Pattern.compile("/video/(\\d+)")
        val matcher = pattern.matcher(path)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractVkVideoId(uri: Uri): String? {
        // https://vkvideo.ru/video-225720479_456239202 or https://vk.com/video-225720479_456239202
        val path = uri.path ?: return null
        val pattern = Pattern.compile("/video(-?\\d+_\\d+)")
        val matcher = pattern.matcher(path)
        return if (matcher.find()) matcher.group(1) else null
    }

    // Use Android User-Agent so that video URLs are generated for Android device
    // This is important because VK/OK generate URLs with srcIp and srcAg parameters
    // that are validated on the CDN side
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    // Desktop Chrome UA for the al_video.php API call: VK returns the full player payload
    // (with params/hls) only for desktop clients; a mobile UA yields a stripped response.
    private const val API_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private fun fetchPage(urlStr: String): String? {
        return try {
            Log.d(TAG, "Fetching: $urlStr")
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                // Use Android User-Agent so video URLs work on Android device
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Accept-Encoding", "identity") // Disable compression to avoid issues
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            Log.d(TAG, "HTTP $code for $urlStr")
            if (code in 200..299) {
                // Determine charset from Content-Type header or default to UTF-8
                val contentType = conn.contentType ?: ""
                val charset = when {
                    contentType.contains("windows-1251", ignoreCase = true) -> Charsets.ISO_8859_1
                    contentType.contains("charset=", ignoreCase = true) -> {
                        val charsetName = contentType.substringAfter("charset=").substringBefore(";").trim()
                        try { charset(charsetName) } catch (_: Exception) { Charsets.UTF_8 }
                    }
                    else -> Charsets.UTF_8
                }
                Log.d(TAG, "Content-Type: $contentType, using charset: $charset")
                conn.inputStream.bufferedReader(charset).use { it.readText() }
            } else if (code in 300..399) {
                // Handle redirect manually if needed
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    Log.d(TAG, "Redirect to: $location")
                    fetchPage(location)
                } else {
                    null
                }
            } else {
                Log.w(TAG, "HTTP $code for $urlStr")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch $urlStr", e)
            null
        }
    }
}
