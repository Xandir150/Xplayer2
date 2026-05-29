package com.teleteh.xplayer2.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.teleteh.xplayer2.ui.network.NetworkFragment
import com.teleteh.xplayer2.ui.recent.RecentFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    // Two tabs: Recent and Sources (Sources = the former Files + Network merged — local-file
    // picker, URL, Hughey and SMB/DLNA all live in NetworkFragment now).
    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> RecentFragment()
        else -> NetworkFragment()
    }
}
