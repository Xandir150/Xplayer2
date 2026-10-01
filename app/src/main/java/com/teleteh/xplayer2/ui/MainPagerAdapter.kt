package com.teleteh.xplayer2.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.teleteh.xplayer2.ui.files.FilesFragment
import com.teleteh.xplayer2.ui.glasses.GlassesControlFragment
import com.teleteh.xplayer2.ui.network.NetworkFragment
import com.teleteh.xplayer2.ui.pclink.PcMirrorFragment
import com.teleteh.xplayer2.ui.recent.RecentFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    /**
     * Whether the Glasses page exists. It is the last page and appears only while XREAL One-series
     * glasses answer on their control channel; see MainActivity.
     */
    var hasGlassesPage: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    // Recent, Files (local picker), Network (URL, Hughey, SMB/DLNA) and PC-Mirror (PC Link's own
    // screen: finding a PC, and the remote for a running session), plus Glasses when they can be
    // controlled. Anything counting pages should ask the adapter — see MainActivity's tab titles
    // and head-turn paging.
    override fun getItemCount(): Int = if (hasGlassesPage) MainPages.GLASSES + 1 else MainPages.PC_MIRROR + 1

    // A page that is gone must not keep its fragment (the default says every id is still present).
    override fun containsItem(itemId: Long): Boolean = itemId in 0 until itemCount

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> RecentFragment()
        1 -> FilesFragment()
        2 -> NetworkFragment()
        4 -> GlassesControlFragment()
        else -> PcMirrorFragment()
    }
}
