package com.teleteh.xplayer2.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.appbar.MaterialToolbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.util.VideoStreamExtractor
import kotlinx.coroutines.launch

/**
 * Lists a VK owner's (user/group) videos with covers and opens the chosen one in the player.
 * Driven entirely by intent extras so it's reusable for any club — the "Hughey" button in the
 * Network tab passes that group's owner id and a "3D" title filter.
 */
class VkClubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vk_club)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "Hughey"
        toolbar.setNavigationOnClickListener { finish() }

        // Optional first row: an external link (the author's Boosty) opened in the browser.
        val btnBoosty = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBoosty)
        val boostyUrl = intent.getStringExtra(EXTRA_BOOSTY_URL)?.takeIf { it.startsWith("http") }
        if (boostyUrl != null) {
            btnBoosty.visibility = View.VISIBLE
            btnBoosty.setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(boostyUrl)))
                }
            }
        }

        val rv = findViewById<RecyclerView>(R.id.rvVideos)
        val progress = findViewById<ProgressBar>(R.id.progress)
        val empty = findViewById<TextView>(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = VideoAdapter { openVideo(it) }
        rv.adapter = adapter

        val ownerId = intent.getStringExtra(EXTRA_OWNER_ID)
        if (ownerId.isNullOrBlank()) { finish(); return }
        val titleFilter = intent.getStringExtra(EXTRA_TITLE_FILTER)

        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = runCatching { VideoStreamExtractor.listOwnerVideos(ownerId, titleFilter) }
                .getOrDefault(emptyList())
            progress.visibility = View.GONE
            adapter.submit(items)
            if (items.isEmpty()) {
                empty.text = getString(R.string.vk_club_empty)
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun openVideo(item: VideoStreamExtractor.VkVideoItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(item.url)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        })
    }

    private class VideoAdapter(
        val onClick: (VideoStreamExtractor.VkVideoItem) -> Unit,
    ) : RecyclerView.Adapter<VideoAdapter.VH>() {
        private val items = mutableListOf<VideoStreamExtractor.VkVideoItem>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<VideoStreamExtractor.VkVideoItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.ivThumb)
            val titleTv: TextView = v.findViewById(R.id.tvTitle)
            val duration: TextView = v.findViewById(R.id.tvDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vk_video, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.titleTv.text = item.title
            holder.duration.text = item.duration.orEmpty()
            holder.duration.visibility = if (item.duration.isNullOrBlank()) View.GONE else View.VISIBLE
            if (item.thumbnailUrl != null) holder.thumb.load(item.thumbnailUrl) else holder.thumb.setImageDrawable(null)
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        const val EXTRA_OWNER_ID = "vk_owner_id"
        const val EXTRA_TITLE_FILTER = "vk_title_filter"
        const val EXTRA_TITLE = "vk_screen_title"
        const val EXTRA_BOOSTY_URL = "vk_boosty_url"
    }
}
