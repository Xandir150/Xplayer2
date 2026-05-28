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
    val sourceType: SourceType? = null,
    // 0=Auto, 1=16:9, 2=4:3, 3=21:9, 4=32:9, 5=1:1, 6=2.39:1
    val resizeMode: Int = 0,
    // Marks that the source video itself is already SBS-packed (so SBS mode samples left/right halves)
    val sourceIsSbs: Boolean = false,
    // Manually chosen stereo mode for this clip: -1 = auto-detect, 0 = 2D, 1 = OU→SBS, 2 = SBS.
    // Persisted only when the user explicitly picked it, so a saved value overrides auto-detect
    // on reopen (important for Full-SBS / Full-OU clips that look like 2D by resolution).
    val stereoMode: Int = -1,
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
