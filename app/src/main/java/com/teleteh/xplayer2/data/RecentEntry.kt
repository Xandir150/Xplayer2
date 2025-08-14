package com.teleteh.xplayer2.data

import android.net.Uri

data class RecentEntry(
    val uri: String,
    val title: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastPlayedAt: Long,
    val framePacking: Int? = null,
    val sbsEnabled: Boolean? = null
) {
    fun uriObj(): Uri = Uri.parse(uri)
}
