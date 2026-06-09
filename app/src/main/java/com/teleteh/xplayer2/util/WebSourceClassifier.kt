package com.teleteh.xplayer2.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.teleteh.xplayer2.data.network.WebSourceStore
import com.teleteh.xplayer2.data.network.WebSourceType
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.MailCloudActivity
import com.teleteh.xplayer2.ui.VkClubActivity
import com.teleteh.xplayer2.ui.YaDiskActivity

/**
 * Single place that decides what a pasted/shared link is and where it should go — used by both the
 * URL field in the Sources tab and the ACTION_SEND share path in [PlayerActivity], so the two stay
 * in lock-step.
 *
 * Containers (a folder or a video list — never a single video) are REMEMBERED in [WebSourceStore]
 * so they reappear as a row in the Sources tab; single videos and direct files are not (Recent
 * already covers replaying those). YaDisk is special: a public link can be a folder OR a single
 * file, and only the folder should be remembered — so we hand every YaDisk link to [YaDiskActivity]
 * and let it persist the source itself once it has confirmed the link lists a folder.
 */
object WebSourceClassifier {

    sealed class Kind {
        /** A VK playlist `/playlist/<owner>_<id>` — remembered as [WebSourceType.VK_PLAYLIST]. */
        data class VkPlaylist(val ownerId: String, val playlistId: String) : Kind()

        /** A VK community videos page with a numeric owner — remembered as [WebSourceType.VK_GROUP]. */
        data class VkGroup(val ownerId: String) : Kind()

        /** A single VK video `/video<owner>_<id>` — NOT remembered (plays in the player). */
        object VkVideo : Kind()

        /** A Yandex Disk public link (folder OR file) — routed to [YaDiskActivity]. */
        data class YaDisk(val publicKey: String) : Kind()

        /** A Mail.ru Cloud public link (folder OR file) — routed to [MailCloudActivity]. */
        data class MailCloud(val weblink: String, val rawUrl: String) : Kind()

        /** Anything else (direct file/HLS, unknown host) — handled the old way by the caller. */
        object Other : Kind()
    }

    /** True for the three container kinds we persist as [WebSourceType] rows. */
    fun isRememberedContainer(kind: Kind): Boolean =
        kind is Kind.VkPlaylist || kind is Kind.VkGroup

    fun classify(rawUrl: String): Kind {
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return Kind.Other
        if (YaDiskApi.isYaDiskUrl(uri)) return Kind.YaDisk(rawUrl.trim())
        if (MailCloudApi.isMailCloudUrl(uri)) {
            MailCloudApi.weblinkFromUrl(rawUrl)?.let { return Kind.MailCloud(it, rawUrl.trim()) }
        }

        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return Kind.Other
        val isVk = host == "vk.com" || host == "vk.ru" || host == "vkvideo.ru" || host == "m.vk.com"
        if (!isVk) return Kind.Other

        val path = (uri.path ?: "").trim('/')

        // /playlist/<owner>_<id> — owner and id may both be negative; split on the FIRST '_'.
        Regex("^playlist/([^/]+)").find(path)?.groupValues?.get(1)?.let { seg ->
            val us = seg.indexOf('_')
            if (us > 0 && us < seg.length - 1) {
                val owner = seg.substring(0, us)
                val pl = seg.substring(us + 1)
                if (isNumericId(owner) && isNumericId(pl)) return Kind.VkPlaylist(owner, pl)
            }
        }

        // /video<owner>_<id> — a single video; not remembered.
        if (Regex("^video-?\\d+_\\d+").containsMatchIn(path)) return Kind.VkVideo

        // Community videos page WITHOUT a video id → VK_GROUP. We can route only when a NUMERIC
        // owner is derivable: /club<id>, /public<id>, /@club<id> (owner = -<id>). A screen-name-only
        // page (/@name, /name, /video/@name) can't be resolved to a numeric owner cheaply → Other.
        Regex("^@?(?:club|public)(\\d+)").find(path)?.groupValues?.get(1)?.let { id ->
            return Kind.VkGroup("-$id")
        }

        return Kind.Other
    }

    private fun isNumericId(s: String): Boolean =
        s.isNotEmpty() && (s.toLongOrNull() != null)

    /**
     * Build the Intent that opens [kind], and (for the remembered container kinds) persist it to
     * [WebSourceStore] first so it shows up in the Sources tab next time. Returns null for
     * [Kind.Other] and [Kind.VkVideo] — the caller plays those as it does today. YaDisk is routed
     * here but persisted later, by [YaDiskActivity], once it confirms the link is a folder.
     */
    fun openIntent(context: Context, rawUrl: String, kind: Kind): Intent? {
        val url = rawUrl.trim()
        return when (kind) {
            is Kind.VkPlaylist -> {
                WebSourceStore(context).addOrUpdate(WebSourceType.VK_PLAYLIST, "VK", url)
                Intent(context, VkClubActivity::class.java).apply {
                    putExtra(VkClubActivity.EXTRA_OWNER_ID, kind.ownerId)
                    putExtra(VkClubActivity.EXTRA_PLAYLIST_ID, kind.playlistId)
                    // Let VkClubActivity refresh this saved row's title to the real community name.
                    putExtra(VkClubActivity.EXTRA_REMEMBER_URL, url)
                }
            }
            is Kind.VkGroup -> {
                WebSourceStore(context).addOrUpdate(WebSourceType.VK_GROUP, "VK", url)
                Intent(context, VkClubActivity::class.java).apply {
                    putExtra(VkClubActivity.EXTRA_OWNER_ID, kind.ownerId)
                    putExtra(VkClubActivity.EXTRA_REMEMBER_URL, url)
                }
            }
            is Kind.YaDisk -> Intent(context, YaDiskActivity::class.java).apply {
                putExtra(YaDiskActivity.EXTRA_PUBLIC_KEY, kind.publicKey)
                // Tell the browser the original link so it can remember the folder once confirmed.
                putExtra(YaDiskActivity.EXTRA_REMEMBER_URL, kind.publicKey)
            }
            is Kind.MailCloud -> Intent(context, MailCloudActivity::class.java).apply {
                putExtra(MailCloudActivity.EXTRA_PUBLIC_KEY, kind.weblink)
                // The original share URL is what we remember (once the listing confirms it's a folder).
                putExtra(MailCloudActivity.EXTRA_REMEMBER_URL, kind.rawUrl)
            }
            Kind.VkVideo, Kind.Other -> null
        }
    }
}
