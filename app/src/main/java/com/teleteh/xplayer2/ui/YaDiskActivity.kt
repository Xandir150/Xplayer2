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
import android.widget.Toast
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
import com.teleteh.xplayer2.data.network.WebSourceStore
import com.teleteh.xplayer2.data.network.WebSourceType
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.util.YaDiskApi
import kotlinx.coroutines.launch

/**
 * Browses a Yandex Disk PUBLIC link and plays its videos. Reuses the VK-club screen layout.
 *
 * Launched with [EXTRA_PUBLIC_KEY] (the full share URL) and an optional [EXTRA_PATH] (a subfolder
 * inside it). On open it asks the public API what the link is:
 *   - a single file  -> resolve its download href and hand it straight to the player, then close;
 *   - a folder        -> list its subfolders (drill in = a new instance with the subfolder path,
 *                         so the system back stack is the folder history) and its video files.
 *
 * Tapping a video resolves a fresh signed href at play time and opens [PlayerActivity]. The public
 * API exposes only the original file (no per-quality variants), so playback is always the original
 * = maximum quality.
 */
class YaDiskActivity : AppCompatActivity() {

    private lateinit var adapter: EntryAdapter
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var publicKey: String
    private var path: String? = null
    private var rememberUrl: String? = null   // set only on the first open → remember a folder once

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vk_club)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title =
            intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: getString(R.string.yadisk_title)
        toolbar.setNavigationOnClickListener { finish() }

        // The VK-club layout has an optional "Boosty" row at the top — not used here.
        findViewById<View>(R.id.btnBoosty).visibility = View.GONE

        val rv = findViewById<RecyclerView>(R.id.rvVideos)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = EntryAdapter { onEntry(it) }
        rv.adapter = adapter

        findViewById<EditText>(R.id.etSearch).doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        publicKey = intent.getStringExtra(EXTRA_PUBLIC_KEY)?.takeIf { it.isNotBlank() }
            ?: run { finish(); return }
        path = intent.getStringExtra(EXTRA_PATH)
        rememberUrl = intent.getStringExtra(EXTRA_REMEMBER_URL)?.takeIf { it.isNotBlank() }
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            val listing = runCatching { YaDiskApi.browse(publicKey, path) }.getOrNull()
            if (listing == null) {
                progress.visibility = View.GONE
                showEmpty(getString(R.string.yadisk_error))
                return@launch
            }
            if (listing.isFile) {
                // The shared link itself is a single video — resolve + play, then close the browser.
                // The opened link (yadi.sk/i/… or disk.yandex.ru/i/…) is itself the durable recents key.
                playFile(
                    downloadPath = null, name = listing.fileName, direct = listing.fileDirectUrl,
                    recentUri = publicKey, streamKey = listing.filePublicKey, streamPath = listing.filePath,
                    finishAfter = true,
                )
                return@launch
            }
            // Confirmed a FOLDER: remember it as a network source so it's a row in the Sources tab
            // next time. Only the original public link is remembered (EXTRA_REMEMBER_URL is set on
            // the first open, not on subfolder drill-ins), so single-file links never get saved.
            rememberUrl?.let {
                // Name the saved source after the actual Yandex Disk folder when the API gives us one;
                // fall back to an explicit intent title, then the generic "Yandex Disk".
                val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { t -> t.isNotBlank() }
                    ?: listing.folderName?.takeIf { t -> t.isNotBlank() }
                    ?: getString(R.string.yadisk_title)
                WebSourceStore(this@YaDiskActivity)
                    .addOrUpdate(WebSourceType.YADISK_FOLDER, title, it)
            }
            progress.visibility = View.GONE
            adapter.submit(listing.entries)
            showEmpty(if (listing.entries.isEmpty()) getString(R.string.yadisk_empty) else null)
        }
    }

    private fun onEntry(e: YaDiskApi.Entry) {
        if (e.isDir) {
            startActivity(Intent(this, YaDiskActivity::class.java).apply {
                putExtra(EXTRA_PUBLIC_KEY, publicKey)
                putExtra(EXTRA_PATH, e.path)
                putExtra(EXTRA_TITLE, e.name)
            })
        } else {
            // Key recents by the file's durable public link (yadi.sk/i/…), not the ephemeral href.
            playFile(
                downloadPath = e.path, name = e.name, direct = e.directUrl,
                recentUri = e.publicUrl, streamKey = e.itemPublicKey, streamPath = e.path, finishAfter = false,
            )
        }
    }

    /** Resolve a fresh signed href (preferred over the possibly-stale `file` field) and open it. */
    private fun playFile(
        downloadPath: String?, name: String?, direct: String?,
        recentUri: String?, streamKey: String?, streamPath: String?, finishAfter: Boolean,
    ) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            // Prefer Yandex's per-quality HLS renditions (a quality menu like VK/OK); fall back to the
            // single original-file href when the internal streams API is unavailable.
            val streams = if (streamKey != null && !streamPath.isNullOrEmpty())
                runCatching { YaDiskApi.getVideoStreams(publicKey, streamKey, streamPath) }.getOrDefault(emptyList())
            else emptyList()

            val intent = Intent(this@YaDiskActivity, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(PlayerActivity.EXTRA_TITLE, name)
                // Durable identity so this lands in Recents and replays/resumes (the play URL expires).
                if (!recentUri.isNullOrBlank()) putExtra(PlayerActivity.EXTRA_RECENT_URI, recentUri)
            }
            if (streams.isNotEmpty()) {
                intent.data = Uri.parse(streams[0].url)   // highest; PlayerActivity reads the arrays
                intent.putExtra(PlayerActivity.EXTRA_STREAM_LABELS, streams.map { it.label }.toTypedArray())
                intent.putExtra(PlayerActivity.EXTRA_STREAM_URLS, streams.map { it.url }.toTypedArray())
            } else {
                val href = runCatching { YaDiskApi.resolveHref(publicKey, downloadPath) }.getOrNull() ?: direct
                if (href.isNullOrBlank()) {
                    progress.visibility = View.GONE
                    Toast.makeText(this@YaDiskActivity, R.string.yadisk_error, Toast.LENGTH_SHORT).show()
                    if (finishAfter) finish()
                    return@launch
                }
                intent.data = Uri.parse(href)
            }
            progress.visibility = View.GONE
            startActivity(intent)
            if (finishAfter) finish()
        }
    }

    private fun showEmpty(msg: String?) {
        if (msg == null) {
            empty.visibility = View.GONE
        } else {
            empty.text = msg
            empty.visibility = View.VISIBLE
        }
    }

    private inner class EntryAdapter(
        val onClick: (YaDiskApi.Entry) -> Unit,
    ) : RecyclerView.Adapter<EntryAdapter.VH>() {
        private val all = mutableListOf<YaDiskApi.Entry>()
        private val shown = mutableListOf<YaDiskApi.Entry>()
        private var query = ""

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<YaDiskApi.Entry>) {
            all.clear(); all.addAll(list); applyFilter()
        }

        fun filter(q: String) { query = q.trim().lowercase(); applyFilter() }

        @SuppressLint("NotifyDataSetChanged")
        private fun applyFilter() {
            shown.clear()
            shown.addAll(if (query.isEmpty()) all else all.filter { it.name.lowercase().contains(query) })
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivIcon)
            val name: TextView = v.findViewById(R.id.tvName)
            val sub: TextView = v.findViewById(R.id.tvSub)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_yadisk, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val e = shown[position]
            holder.name.text = e.name
            if (e.isDir) {
                holder.icon.dispose()
                holder.icon.setImageResource(R.drawable.ic_folder_24)
                holder.sub.text = getString(R.string.yadisk_folder)
            } else {
                holder.sub.text = humanSize(e.sizeBytes)
                if (e.preview != null) {
                    holder.icon.load(e.preview) { crossfade(true) }
                } else {
                    holder.icon.dispose()
                    holder.icon.setImageResource(R.drawable.ic_video_24)
                }
            }
            holder.itemView.setOnClickListener { onClick(e) }
        }
    }

    private fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "${bytes} B" else String.format("%.1f %s", v, units[i])
    }

    companion object {
        const val EXTRA_PUBLIC_KEY = "yadisk_public_key"
        const val EXTRA_PATH = "yadisk_path"
        const val EXTRA_TITLE = "yadisk_title"

        /** When set (only on the first open, not subfolder drill-ins), remember this public link as
         *  a [WebSourceType.YADISK_FOLDER] source once the listing confirms it's a folder. */
        const val EXTRA_REMEMBER_URL = "yadisk_remember_url"
    }
}
