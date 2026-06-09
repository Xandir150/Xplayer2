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
import com.google.android.material.appbar.MaterialToolbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.network.WebSourceStore
import com.teleteh.xplayer2.data.network.WebSourceType
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.util.MailCloudApi
import kotlinx.coroutines.launch

/**
 * Browses a Mail.ru Cloud (Облако Mail.ru) PUBLIC link and plays its videos. Mirrors
 * [YaDiskActivity] (and reuses its layout/row), minus the per-quality streams — the public file is
 * served as the original, so playback is always maximum quality.
 *
 * Launched with [EXTRA_PUBLIC_KEY] (the "weblink": the part after `/public/`, possibly a subfolder).
 * On open it asks the public API what the link is:
 *   - a single file -> resolve its stream URL and hand it straight to the player, then close;
 *   - a folder      -> list its subfolders (drill in = a new instance with the child weblink, so the
 *                      system back stack is the folder history) and its video files.
 *
 * Stream URLs are short-lived, so a fresh one is resolved at play time.
 */
class MailCloudActivity : AppCompatActivity() {

    private lateinit var adapter: EntryAdapter
    private lateinit var progress: ProgressBar
    private lateinit var empty: TextView
    private lateinit var weblink: String
    private var rememberUrl: String? = null   // set only on the first open → remember a folder once

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vk_club)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title =
            intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: getString(R.string.mailru_title)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.btnBoosty).visibility = View.GONE

        val rv = findViewById<RecyclerView>(R.id.rvVideos)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.tvEmpty)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = EntryAdapter { onEntry(it) }
        rv.adapter = adapter

        findViewById<EditText>(R.id.etSearch).doAfterTextChanged { adapter.filter(it?.toString().orEmpty()) }

        // EXTRA_PUBLIC_KEY is the weblink; tolerate a full share URL too (saved sources store the URL).
        val raw = intent.getStringExtra(EXTRA_PUBLIC_KEY)?.takeIf { it.isNotBlank() }
            ?: run { finish(); return }
        weblink = MailCloudApi.weblinkFromUrl(raw) ?: raw
        rememberUrl = intent.getStringExtra(EXTRA_REMEMBER_URL)?.takeIf { it.isNotBlank() }
        load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        lifecycleScope.launch {
            val listing = runCatching { MailCloudApi.browse(weblink) }.getOrNull()
            if (listing == null) {
                progress.visibility = View.GONE
                showEmpty(getString(R.string.mailru_error))
                return@launch
            }
            if (listing.isFile) {
                // The shared link itself is a single video — resolve + play, then close the browser.
                playFile(listing.fileWeblink ?: weblink, listing.fileName, finishAfter = true)
                return@launch
            }
            // Confirmed a FOLDER: remember it as a network source so it's a row in the Sources tab
            // next time. Only the original link is remembered (EXTRA_REMEMBER_URL is set on the first
            // open, not on subfolder drill-ins), so single-file links never get saved.
            rememberUrl?.let {
                val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { t -> t.isNotBlank() }
                    ?: listing.folderName?.takeIf { t -> t.isNotBlank() }
                    ?: getString(R.string.mailru_title)
                WebSourceStore(this@MailCloudActivity)
                    .addOrUpdate(WebSourceType.MAILRU_FOLDER, title, it)
            }
            progress.visibility = View.GONE
            adapter.submit(listing.entries)
            showEmpty(if (listing.entries.isEmpty()) getString(R.string.mailru_empty) else null)
        }
    }

    private fun onEntry(e: MailCloudApi.Entry) {
        if (e.isDir) {
            startActivity(Intent(this, MailCloudActivity::class.java).apply {
                putExtra(EXTRA_PUBLIC_KEY, e.weblink)
                putExtra(EXTRA_TITLE, e.name)
            })
        } else {
            playFile(e.weblink, e.name, finishAfter = false)
        }
    }

    /** Resolve a fresh stream URL for [fileWeblink] and open it in the player. */
    private fun playFile(fileWeblink: String, name: String?, finishAfter: Boolean) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val href = runCatching { MailCloudApi.resolveHref(fileWeblink) }.getOrNull()
            progress.visibility = View.GONE
            if (href.isNullOrBlank()) {
                Toast.makeText(this@MailCloudActivity, R.string.mailru_error, Toast.LENGTH_SHORT).show()
                if (finishAfter) finish()
                return@launch
            }
            startActivity(Intent(this@MailCloudActivity, PlayerActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse(href)
                putExtra(PlayerActivity.EXTRA_TITLE, name)
                // Durable identity so it lands in Recents and replays (the stream URL expires).
                putExtra(PlayerActivity.EXTRA_RECENT_URI, MailCloudApi.publicUrl(fileWeblink))
            })
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
        val onClick: (MailCloudApi.Entry) -> Unit,
    ) : RecyclerView.Adapter<EntryAdapter.VH>() {
        private val all = mutableListOf<MailCloudApi.Entry>()
        private val shown = mutableListOf<MailCloudApi.Entry>()
        private var query = ""

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<MailCloudApi.Entry>) {
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
            holder.icon.dispose()
            if (e.isDir) {
                holder.icon.setImageResource(R.drawable.ic_folder_24)
                holder.sub.text = getString(R.string.dlna_folder_subtitle)
            } else {
                holder.icon.setImageResource(R.drawable.ic_video_24)
                holder.sub.text = humanSize(e.sizeBytes)
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
        return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
    }

    companion object {
        const val EXTRA_PUBLIC_KEY = "mailru_weblink"
        const val EXTRA_TITLE = "mailru_title"

        /** When set (only on the first open, not subfolder drill-ins), remember this public link as
         *  a [WebSourceType.MAILRU_FOLDER] source once the listing confirms it's a folder. */
        const val EXTRA_REMEMBER_URL = "mailru_remember_url"
    }
}
