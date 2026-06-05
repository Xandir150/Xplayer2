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

    /**
     * One selectable quality of a stream. [label] is human-readable ("1080p", "Auto", "HD"),
     * [url] is the direct playable URL for that quality.
     */
    data class StreamVariant(
        val label: String,
        val url: String
    )

    data class ExtractedStream(
        val url: String,
        val title: String? = null,
        val quality: String? = null,
        // All selectable qualities, highest first. The primary [url] above always matches the
        // first/highest one. Empty when the source exposes only a single playable URL (the
        // quality picker is hidden unless this has ≥2 entries).
        val variants: List<StreamVariant> = emptyList()
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
        

        // Method 1: Try to find data-options JSON in the page
        val optionsPattern = Pattern.compile("data-options=[\"']([^\"']+)[\"']", Pattern.DOTALL)
        val optionsMatcher = optionsPattern.matcher(html)
        
        if (optionsMatcher.find()) {
            val optionsEncoded = optionsMatcher.group(1) ?: ""
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
    
    // ok.ru quality names ordered highest → lowest, with a friendly pixel-height label for the UI.
    private val OKRU_QUALITY_ORDER = listOf(
        "full" to "1080p",
        "ultra" to "1440p",
        "quad" to "2160p",
        "hd" to "720p",
        "sd" to "480p",
        "low" to "360p",
        "lowest" to "240p",
        "mobile" to "144p",
    )

    private fun extractBestVideoFromArray(videos: JSONArray?, title: String?): ExtractedStream? {
        if (videos == null || videos.length() == 0) return null

        // Quality priority (highest first). ok.ru's own "full/ultra/quad" naming is non-obvious,
        // so 2160p/1440p comes before "full" (1080p) in the ranking even though "full" sounds top.
        val rank = listOf("quad", "ultra", "full", "hd", "sd", "low", "lowest", "mobile")
        val labelOf = OKRU_QUALITY_ORDER.toMap()

        // Collect every distinct (rank, label, url) so we can both pick the best and offer a list.
        data class Cand(val priority: Int, val label: String, val url: String)
        val cands = mutableListOf<Cand>()
        for (i in 0 until videos.length()) {
            val video = videos.optJSONObject(i) ?: continue
            val url = video.optString("url").takeIf { it.isNotBlank() } ?: continue
            val name = video.optString("name").lowercase()
            val priority = rank.indexOf(name).takeIf { it >= 0 } ?: (rank.size + i)
            val label = labelOf[name] ?: name.ifBlank { "${i + 1}" }
            cands.add(Cand(priority, label, url))
        }
        if (cands.isEmpty()) return null

        cands.sortBy { it.priority }
        // De-duplicate by label, keeping the first (highest-priority) URL for each.
        val variants = mutableListOf<StreamVariant>()
        val seenLabels = HashSet<String>()
        for (c in cands) {
            if (seenLabels.add(c.label)) variants.add(StreamVariant(c.label, c.url))
        }
        val primary = variants.first()
        return ExtractedStream(
            primary.url,
            title?.takeIf { t -> t.isNotBlank() },
            primary.label,
            variants
        )
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

        // Method 2 (Legacy fallback): embed player page HTML scraping.
        // The embed page used to contain HLS URLs directly in the HTML.
        val embedUrl = "https://vk.com/video_ext.php?oid=$ownerId&id=$id&hash=0"
        val embedHtml = fetchPage(embedUrl)
        
        if (embedHtml != null) {
            
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
                val variants = videos.entries.sortedByDescending { it.key }
                    .map { StreamVariant("${it.key}p", it.value) }
                val best = variants.first()
                val title = extractTitleFromHtml(embedHtml)
                Log.i(TAG, "Extracted VK MP4 from embed: ${variants.size} qualities, best=${best.label}, title=$title")
                return@withContext ExtractedStream(best.url, title, best.label, variants)
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
                val variants = videos.entries.sortedByDescending { it.key }
                    .map { StreamVariant("${it.key}p", it.value) }
                val best = variants.first()
                Log.i(TAG, "Found VK MP4 in page: ${variants.size} qualities, best=${best.label}")
                return@withContext ExtractedStream(best.url, null, best.label, variants)
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
            if (rawMdTitle.isNotBlank()) {
                // Try to fix encoding: windows-1251 read as ISO-8859-1
                val fixedTitle = tryFixEncoding(rawMdTitle)
                val decoded = decodeUnicodeEscapes(fixedTitle)
                if (decoded.isNotBlank()) {
                    return decoded
                }
            }
        } else {
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

    /**
     * Build the full quality list from a VK player params[0] object, highest first.
     *
     * Every present progressive MP4 (url2160 … url240) becomes a selectable variant labelled by
     * its height ("2160p" … "240p"); the highest stays the primary [ExtractedStream.url] so the
     * default behaviour (always open the max VK offers) is unchanged. Adaptive HLS often caps at
     * 720p, so the progressive URLs are preferred and listed first. If there is no progressive URL
     * at all, the adaptive HLS stream is exposed as a single "Auto" variant.
     */
    private fun extractVkPlayerParams(p: JSONObject, title: String?): ExtractedStream? {
        val variants = mutableListOf<StreamVariant>()
        // Highest progressive MP4 first — guarantees the max VK offers.
        for (q in listOf("url2160", "url1440", "url1080", "url720", "url480", "url360", "url240")) {
            val u = p.optString(q).takeIf { it.startsWith("http") } ?: continue
            val label = q.removePrefix("url") + "p"
            variants.add(StreamVariant(label, decodeUrl(u)))
        }
        if (variants.isNotEmpty()) {
            val primary = variants.first()
            Log.i(TAG, "VK MP4 extracted: ${variants.size} qualities, best=${primary.label} (title=$title)")
            return ExtractedStream(primary.url, title, primary.label, variants)
        }
        // Adaptive HLS fallback (only if no progressive URL is present): one URL carries every
        // quality + audio track and plays via the media3 HLS module. Exposed as a single "Auto".
        for (key in listOf("hls_ondemand", "hls")) {
            val u = p.optString(key).takeIf { it.startsWith("http") }
            if (u != null) {
                Log.i(TAG, "VK HLS extracted (title=$title)")
                val url = decodeUrl(u)
                return ExtractedStream(url, title, "hls", listOf(StreamVariant("Auto", url)))
            }
        }
        Log.w(TAG, "VK al_video: params[0] had no hls/url* fields")
        return null
    }

    private fun postAlVideo(body: String): JSONObject? {
        return try {
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
        // Community/owner display name, parsed from the row's owner-link HTML ([8]). Same for every
        // row in a list, so the caller reads it off the first item to title the screen / saved source.
        val ownerName: String? = null,
    )

    // Owner-link HTML in row[8] is like `<a href='…' class='VideoCard__ownerLink' …>NAME</a>`.
    private val VK_OWNER_NAME = Regex(">([^<]+)</a>")

    /** Pull the community/owner display name out of a VK owner-link HTML cell, or null. */
    private fun parseVkOwnerName(ownerLinkHtml: String?): String? {
        val name = ownerLinkHtml?.let { VK_OWNER_NAME.find(it)?.groupValues?.getOrNull(1) }
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return name
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").trim()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * List an owner's (user/group) newest videos via al_video.php `load_videos_silent`,
     * optionally keeping only titles containing [titleContains] (case-insensitive). Returns the
     * single ~50-item window VK exposes unauthenticated. [ownerId] is the numeric owner id
     * (negative for groups, e.g. -225720479).
     *
     * Each list row is a flat array: [0]=ownerId, [1]=videoId, [2]=thumbnail, [3]=title,
     * [5]=duration. The envelope is payload[1][0]["all"] = {list, count, total} (windows-1251).
     *
     * Rows with no thumbnail are skipped: VK blanks the cover for removed/unavailable videos
     * (copyright takedowns etc.), and `act=show` on exactly those rows returns "removed" — so a
     * missing thumbnail is a reliable "this won't play" signal. This also drops generic
     * placeholder posts that carry no real cover.
     */
    suspend fun listOwnerVideos(
        ownerId: String,
        titleContains: String? = null,
    ): List<VkVideoItem> = withContext(Dispatchers.IO) {
        val needle = titleContains?.lowercase()
        // Unauthenticated al_video.php only exposes the newest ~50 videos for an owner: `offset`
        // never pages past the first window, `count` is ignored, and there's no cursor — so we
        // fetch the single available window and de-duplicate (the same ids can repeat in it).
        val json = postAlVideo("act=load_videos_silent&al=1&offset=0&oid=$ownerId&section=all")
            ?: return@withContext emptyList()
        val all = json.optJSONArray("payload")?.optJSONArray(1)
            ?.optJSONObject(0)?.optJSONObject("all")
            ?: return@withContext emptyList()
        val list = all.optJSONArray("list") ?: return@withContext emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<VkVideoItem>()
        for (i in 0 until list.length()) {
            val v = list.optJSONArray(i) ?: continue
            val oid = v.optLong(0)
            val vid = v.optLong(1)
            if (oid == 0L || vid == 0L) continue
            val key = "${oid}_$vid"
            if (!seen.add(key)) continue   // de-dupe: VK repeats ids within the window
            val title = v.optString(3).trim()
            if (needle != null && !title.lowercase().contains(needle)) continue
            // No thumbnail => removed/unavailable video (verified: act=show returns "removed"
            // for exactly the thumbnail-less rows). Skip so the list only shows what will play.
            val thumb = v.optString(2).takeIf { it.startsWith("http") } ?: continue
            out.add(
                VkVideoItem(
                    url = "https://vkvideo.ru/video$key",
                    title = title.ifBlank { "video$key" },
                    thumbnailUrl = thumb,
                    duration = v.optString(5).takeIf { it.isNotBlank() },
                    ownerName = parseVkOwnerName(v.optString(8)),
                )
            )
        }
        Log.i(TAG, "listOwnerVideos(oid=$ownerId, filter=$titleContains) -> ${out.size} of ${all.optInt("total")}")
        out
    }

    /**
     * List the videos of a VK playlist (album) via al_video.php `load_videos_silent`. Identical to
     * [listOwnerVideos] except it targets a `playlist_<id>` section, so the envelope key is
     * "playlist_$playlistId" instead of "all". [ownerId] and [playlistId] may both be negative,
     * e.g. owner -225720479, playlist -4 (from `/playlist/-225720479_-4`).
     */
    suspend fun listPlaylistVideos(
        ownerId: String,
        playlistId: String,
        titleContains: String? = null,
    ): List<VkVideoItem> = withContext(Dispatchers.IO) {
        val needle = titleContains?.lowercase()
        val section = "playlist_$playlistId"
        val json = postAlVideo("act=load_videos_silent&al=1&offset=0&oid=$ownerId&section=$section")
            ?: return@withContext emptyList()
        val all = json.optJSONArray("payload")?.optJSONArray(1)
            ?.optJSONObject(0)?.optJSONObject(section)
            ?: return@withContext emptyList()
        val list = all.optJSONArray("list") ?: return@withContext emptyList()
        val seen = HashSet<String>()
        val out = mutableListOf<VkVideoItem>()
        for (i in 0 until list.length()) {
            val v = list.optJSONArray(i) ?: continue
            val oid = v.optLong(0)
            val vid = v.optLong(1)
            if (oid == 0L || vid == 0L) continue
            val key = "${oid}_$vid"
            if (!seen.add(key)) continue   // de-dupe: VK can repeat ids within the window
            val title = v.optString(3).trim()
            if (needle != null && !title.lowercase().contains(needle)) continue
            // No thumbnail => removed/unavailable video; skip so the list only shows what will play.
            val thumb = v.optString(2).takeIf { it.startsWith("http") } ?: continue
            out.add(
                VkVideoItem(
                    url = "https://vkvideo.ru/video$key",
                    title = title.ifBlank { "video$key" },
                    thumbnailUrl = thumb,
                    duration = v.optString(5).takeIf { it.isNotBlank() },
                    ownerName = parseVkOwnerName(v.optString(8)),
                )
            )
        }
        Log.i(TAG, "listPlaylistVideos(oid=$ownerId, pl=$playlistId) -> ${out.size} of ${all.optInt("total")}")
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
                conn.inputStream.bufferedReader(charset).use { it.readText() }
            } else if (code in 300..399) {
                // Handle redirect manually if needed
                val location = conn.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
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
