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
    YANDEX,     // Yandex Disk public links (disk.yandex.* / yadi.sk)
    UNKNOWN
}

data class RecentEntry(
    val uri: String,
    val title: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val framePacking: Int? = null,
    val sbsShiftEnabled: Boolean? = null,
    val sourceType: SourceType? = null,
    // 0=Auto, 1=16:9, 2=4:3, 3=21:9, 4=32:9, 5=1:1, 6=2.39:1
    val resizeMode: Int = 0,
    // Effective stereo mode last used for this clip (single source of truth for restore and the
    // history badge): -1 = never played / unknown, 0 = 2D, 1 = OU→SBS, 2 = SBS. A saved 0..2 is
    // honoured on reopen, overriding auto-detect (important for Full-SBS/OU clips that look 2D).
    val stereoMode: Int = -1,
    // Per-clip audio gain boost in millibels (0 = off, max 2400 = +24 dB). Per-clip because a
    // gain that rescues a quiet upload clips/distorts a normally-mastered one.
    val volumeBoostMb: Int = 0,
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
                // Yandex Disk public links (the durable yadi.sk/i/… we store for recents)
                host == "yadi.sk" || host.endsWith("disk.yandex.ru") || host.endsWith("disk.yandex.com") ||
                    host.endsWith("disk.yandex.kz") || host.endsWith("disk.yandex.by") ||
                    host.endsWith("disk.360.yandex.ru") || host.endsWith("disk.360.yandex.com") -> SourceType.YANDEX
                // Local files
                scheme == "content" || scheme == "file" -> SourceType.LOCAL
                // Network sources
                scheme == "smb" || scheme == "http" || scheme == "https" -> SourceType.NETWORK
                else -> SourceType.UNKNOWN
            }
        }
    }
}
