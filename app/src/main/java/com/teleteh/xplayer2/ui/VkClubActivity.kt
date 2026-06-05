package com.teleteh.xplayer2.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import com.google.android.material.appbar.MaterialToolbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.util.VideoStreamExtractor
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lists a VK owner's (user/group) videos with covers and opens the chosen one in the player.
 * Driven entirely by intent extras so it's reusable for any club — the "Hughey" button in the
 * Network tab passes that group's owner id and a "3D" title filter. Results are cached to disk
 * for a short 10-minute window so reopening is instant, but once that expires the list is
 * re-fetched on open so new uploads show up. Can be filtered by title on screen.
 */
class VkClubActivity : AppCompatActivity() {

    private lateinit var adapter: VideoAdapter
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vk_club)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: "VK"
        toolbar.setNavigationOnClickListener { finish() }

        // Optional first row: an external link (the author's Boosty) opened in the browser.
        val btnBoosty = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBoosty)
        val boostyUrl = intent.getStringExtra(EXTRA_BOOSTY_URL)?.takeIf { it.startsWith("http") }
        if (boostyUrl != null) {
            btnBoosty.visibility = View.VISIBLE
            btnBoosty.setOnClickListener {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(boostyUrl))) }
            }
        }

        val rv = findViewById<RecyclerView>(R.id.rvVideos)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = VideoAdapter { openVideo(it) }
        rv.adapter = adapter

        findViewById<EditText>(R.id.etSearch).doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        val ownerId = intent.getStringExtra(EXTRA_OWNER_ID)
        if (ownerId.isNullOrBlank()) { finish(); return }
        load(ownerId, intent.getStringExtra(EXTRA_PLAYLIST_ID), intent.getStringExtra(EXTRA_TITLE_FILTER))
    }

    private fun load(ownerId: String, playlistId: String?, titleFilter: String?) {
        // A playlist lists that album's videos; otherwise the owner's whole library. Distinct cache.
        val cacheTag = if (!playlistId.isNullOrBlank()) "pl${playlistId}" else (titleFilter ?: "all")
        val cacheFile = File(cacheDir, "vkclub_${ownerId}_${cacheTag}.json")
        readCache(cacheFile)?.let { showItems(it); return }   // fresh cache (<10 min) — show instantly
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val items = runCatching {
                if (!playlistId.isNullOrBlank())
                    VideoStreamExtractor.listPlaylistVideos(ownerId, playlistId, titleFilter)
                else
                    VideoStreamExtractor.listOwnerVideos(ownerId, titleFilter)
            }.getOrDefault(emptyList())
            progress.visibility = View.GONE
            if (items.isNotEmpty()) writeCache(cacheFile, items)
            showItems(items)
        }
    }

    private fun showItems(items: List<VideoStreamExtractor.VkVideoItem>) {
        adapter.submit(items)
        if (items.isEmpty()) {
            empty.text = getString(R.string.vk_club_empty)
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
    }

    private fun openVideo(item: VideoStreamExtractor.VkVideoItem) {
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(item.url)
            putExtra(PlayerActivity.EXTRA_TITLE, item.title)
        })
    }

    // --- disk cache (cacheDir/vkclub_<owner>_<filter>.json, 10-min TTL gated by file mtime) ---
    private fun readCache(f: File): List<VideoStreamExtractor.VkVideoItem>? {
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > CACHE_TTL_MS) return null
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                VideoStreamExtractor.VkVideoItem(
                    url = o.getString("u"),
                    title = o.getString("t"),
                    thumbnailUrl = o.optString("th").takeIf { it.isNotBlank() },
                    duration = o.optString("d").takeIf { it.isNotBlank() },
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun writeCache(f: File, items: List<VideoStreamExtractor.VkVideoItem>) {
        runCatching {
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject()
                    .put("u", it.url).put("t", it.title)
                    .put("th", it.thumbnailUrl ?: "").put("d", it.duration ?: ""))
            }
            f.writeText(arr.toString())
        }
    }

    private class VideoAdapter(
        val onClick: (VideoStreamExtractor.VkVideoItem) -> Unit,
    ) : RecyclerView.Adapter<VideoAdapter.VH>() {
        private val all = mutableListOf<VideoStreamExtractor.VkVideoItem>()
        private val shown = mutableListOf<VideoStreamExtractor.VkVideoItem>()
        private var query = ""

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<VideoStreamExtractor.VkVideoItem>) {
            all.clear(); all.addAll(list); applyFilter()
        }

        fun filter(q: String) { query = q.trim().lowercase(); applyFilter() }

        @SuppressLint("NotifyDataSetChanged")
        private fun applyFilter() {
            shown.clear()
            shown.addAll(if (query.isEmpty()) all else all.filter { it.title.lowercase().contains(query) })
            notifyDataSetChanged()
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.ivThumb)
            val titleTv: TextView = v.findViewById(R.id.tvTitle)
            val duration: TextView = v.findViewById(R.id.tvDuration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_vk_video, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = shown[position]
            holder.titleTv.text = item.title
            holder.duration.text = item.duration.orEmpty()
            holder.duration.visibility = if (item.duration.isNullOrBlank()) View.GONE else View.VISIBLE
            // Always go through Coil so it cancels any in-flight request bound to this recycled
            // view (prevents a stale/wrong poster); explicitly clear when there's no thumbnail.
            if (item.thumbnailUrl != null) {
                holder.thumb.load(item.thumbnailUrl) { crossfade(true) }
            } else {
                holder.thumb.dispose()
                holder.thumb.setImageDrawable(null)
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        const val EXTRA_OWNER_ID = "vk_owner_id"
        const val EXTRA_PLAYLIST_ID = "vk_playlist_id"
        const val EXTRA_TITLE_FILTER = "vk_title_filter"
        const val EXTRA_TITLE = "vk_screen_title"
        const val EXTRA_BOOSTY_URL = "vk_boosty_url"
        private const val CACHE_TTL_MS = 10 * 60 * 1000L  // 10 minutes
    }
}
