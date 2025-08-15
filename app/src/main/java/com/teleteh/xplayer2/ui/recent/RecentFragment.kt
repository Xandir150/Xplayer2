package com.teleteh.xplayer2.ui.recent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.RecentStore
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils

class RecentFragment : Fragment(R.layout.fragment_recent) {
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: RecentAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recycler = view.findViewById(R.id.rvRecent)
        empty = view.findViewById(R.id.tvEmpty)
        adapter = RecentAdapter(onClick = { entry ->
            val ctx = requireContext()
            val intent = Intent(ctx, PlayerActivity::class.java)
            intent.data = entry.uriObj()
            intent.putExtra(PlayerActivity.EXTRA_START_POSITION_MS, entry.lastPositionMs)
            // Pass the stored title so PlayerActivity starts with the correct display title
            intent.putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DisplayUtils.startOnBestDisplay(requireActivity(), intent)
        }, onDelete = { entry ->
            RecentStore(requireContext()).delete(entry.uri)
            loadData()
        })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        val items = RecentStore(requireContext()).getAll()
        adapter.submitList(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }
}
