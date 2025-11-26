package com.teleteh.xplayer2.data

import android.net.Uri

/**
 * Source type for displaying appropriate icon in history
 */
enum class SourceType {
    LOCAL,      // Local file
    NETWORK,    // SMB, HTTP stream, DLNA
    VK,         // vk.com, vkvideo.ru
    OK,         // ok.ru
    UNKNOWN
}

data class RecentEntry(
    val uri: String,
    val title: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val framePacking: Int? = null,
    val sbsEnabled: Boolean? = null,
    val sbsShiftEnabled: Boolean? = null,
    val sourceType: SourceType? = null
) {
    fun uriObj(): Uri = Uri.parse(uri)
    
    companion object {
        /**
         * Determine source type from URI
         */
        fun detectSourceType(uri: Uri): SourceType {
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase() ?: ""
            
            return when {
                // VK video
                host.contains("vk.com") || host.contains("vkvideo.ru") -> SourceType.VK
                // OK.ru
                host.contains("ok.ru") -> SourceType.OK
                // Local files
                scheme == "content" || scheme == "file" -> SourceType.LOCAL
                // Network sources
                scheme == "smb" || scheme == "http" || scheme == "https" -> SourceType.NETWORK
                else -> SourceType.UNKNOWN
            }
        }
    }
}
