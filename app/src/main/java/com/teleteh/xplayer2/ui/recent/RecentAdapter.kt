package com.teleteh.xplayer2.ui.recent

import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.RecentEntry
import java.util.concurrent.TimeUnit

class RecentAdapter(
    private val onClick: (RecentEntry) -> Unit,
    private val onDelete: (RecentEntry) -> Unit = {}
) : ListAdapter<RecentEntry, RecentAdapter.VH>(Diff) {

    object Diff : DiffUtil.ItemCallback<RecentEntry>() {
        override fun areItemsTheSame(oldItem: RecentEntry, newItem: RecentEntry): Boolean =
            oldItem.uri == newItem.uri
        override fun areContentsTheSame(oldItem: RecentEntry, newItem: RecentEntry): Boolean =
            oldItem == newItem
    }

    class VH(view: View, val onClick: (Int) -> Unit, val onDeleteIdx: (Int) -> Unit) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val badge: TextView = view.findViewById(R.id.tvBadge)
        val sbsState: TextView? = view.findViewById(R.id.tvSbsState)
        init {
            view.setOnClickListener { onClick(bindingAdapterPosition) }
            view.findViewById<View?>(R.id.btnDelete)?.setOnClickListener {
                onDeleteIdx(bindingAdapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return VH(v,
            onClick = { pos -> if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos)) },
            onDeleteIdx = { pos -> if (pos != RecyclerView.NO_POSITION) onDelete(getItem(pos)) }
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = resolveNiceTitle(holder.itemView.context, item)
        holder.subtitle.text = buildString {
            append("Позиция: ")
            append(formatMs(item.lastPositionMs))
            if (item.durationMs > 0) {
                append(" / ")
                append(formatMs(item.durationMs))
            }
        }
        // Frame-packing badge: show "OU=>SBS" for known 3D formats (3=SBS, 4=OU), hide otherwise
        if (item.framePacking == 3 || item.framePacking == 4) {
            holder.badge.visibility = View.VISIBLE
            holder.badge.text = "OU=>SBS"
        } else {
            holder.badge.visibility = View.GONE
        }

        // SBS state badge: show when sbsEnabled is true
        holder.sbsState?.visibility = if (item.sbsEnabled == true) View.VISIBLE else View.GONE
        holder.sbsState?.text = "OU=>SBS"
    }

    private fun resolveNiceTitle(context: android.content.Context, entry: RecentEntry): String {
        // 1) Try stored title
        if (entry.title.isNotBlank() && !entry.title.startsWith("msf:") && !entry.title.startsWith("content:")) {
            return entry.title
        }
        val uri = Uri.parse(entry.uri)
        // 2) Try DISPLAY_NAME from provider
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Throwable) { /* ignore */ }
        // 3) Fallback to decoded lastPathSegment
        val last = uri.lastPathSegment ?: entry.uri
        return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }
}
