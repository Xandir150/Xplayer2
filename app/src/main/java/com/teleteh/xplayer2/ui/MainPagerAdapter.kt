package com.teleteh.xplayer2.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.teleteh.xplayer2.ui.network.NetworkFragment
import com.teleteh.xplayer2.ui.pclink.PcMirrorFragment
import com.teleteh.xplayer2.ui.recent.RecentFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    // Three tabs: Recent, Sources (the former Files + Network merged — local-file picker, URL,
    // Hughey and SMB/DLNA all live in NetworkFragment now) and PC-Mirror (PC Link's own screen:
    // finding a PC, and the remote for a running session). Anything counting pages should ask
    // the adapter — see MainActivity's tab titles and head-turn paging.
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> RecentFragment()
        1 -> NetworkFragment()
        else -> PcMirrorFragment()
    }
}
