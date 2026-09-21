package com.teleteh.xplayer2.util

import java.net.URI
import java.net.URLDecoder

/** A share may contain a title or a sentence before the actual link. */
internal object SharedMediaUrl {
    fun fromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text)
            .map { it.value.trimEnd('.', ',', ')', ']', '}') }
            .firstOrNull { runCatching { URI(it).host != null }.getOrDefault(false) }
    }
}

internal object YouTubeLink {
    private val idPattern = Regex("[A-Za-z0-9_-]{11}")

    fun isYouTube(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return false
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
        return host in setOf("youtube.com", "youtu.be", "youtube-nocookie.com", "music.youtube.com")
    }

    fun videoId(url: String): String? {
        if (!isYouTube(url)) return null
        val uri = URI(url)
        val parts = (uri.path ?: "").trim('/').split('/')
        val candidate = when {
            uri.host.lowercase().removePrefix("www.") == "youtu.be" -> parts.singleOrNull()
            parts.size == 2 && parts[0] in setOf("shorts", "embed", "v", "live") -> parts[1]
            parts.singleOrNull() == "watch" -> uri.rawQuery?.split('&')?.firstNotNullOfOrNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2 && pair[0] == "v") {
                    runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()
                } else null
            }
            else -> null
        }
        return candidate?.takeIf(idPattern::matches)
    }
}
