package com.teleteh.xplayer2.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.teleteh.xplayer2.ui.files.FilesFragment
import com.teleteh.xplayer2.ui.network.NetworkFragment
import com.teleteh.xplayer2.ui.recent.RecentFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> RecentFragment()
        1 -> FilesFragment()
        else -> NetworkFragment()
    }
}
