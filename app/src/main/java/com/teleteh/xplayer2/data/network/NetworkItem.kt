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
}
