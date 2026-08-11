package com.teleteh.xplayer2.data.network

sealed class NetworkItem {
    data class SmbShare(
        val name: String,
        val uri: String
    ) : NetworkItem()

    data class DlnaDevice(
        val friendlyName: String,
        val location: String,
        val usn: String?,
        val iconUrl: String?
    ) : NetworkItem()

    // Special row to navigate up within DLNA browsing UI
    object DlnaUp : NetworkItem()

    /**
     * Static entry point into PC Link (PC -> glasses desktop streaming): always shown at the top
     * of the Sources list, like a permanent "device". Tapping it opens
     * `com.teleteh.xplayer2.ui.network.PcConnectActivity`, which does its own LAN discovery /
     * manual-IP entry — nothing here is discovered or persisted per-instance.
     */
    object PcLink : NetworkItem()

    data class DlnaContainer(
        val title: String,
        val id: String,
        val parentId: String?,
        val deviceLocation: String,
        val controlUrl: String
    ) : NetworkItem()

    data class DlnaMedia(
        val title: String,
        val url: String,
        val mime: String?,
        val deviceLocation: String,
        val controlUrl: String
    ) : NetworkItem()

    /**
     * A remembered network container (a folder or a video list — never a single video). Persisted
     * by [WebSourceStore] and shown as a tappable row in the Sources tab so it's there next time.
     * [url] is the original link the user opened/shared; routing is by [type].
     */
    data class WebSource(
        val type: WebSourceType,
        val title: String,
        val url: String
    ) : NetworkItem()
}

/** The kinds of container we remember. Single videos are NOT remembered (Recent covers those). */
enum class WebSourceType { YADISK_FOLDER, MAILRU_FOLDER, VK_PLAYLIST, VK_GROUP }
