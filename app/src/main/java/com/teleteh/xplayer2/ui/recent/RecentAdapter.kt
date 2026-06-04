package com.teleteh.xplayer2.ui.recent

import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.RecentEntry
import com.teleteh.xplayer2.data.SourceType
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

    class VH(view: View, val onClick: (Int) -> Unit, val onDeleteIdx: (Int) -> Unit) :
        RecyclerView.ViewHolder(view) {
        val sourceIcon: ImageView = view.findViewById(R.id.ivSourceIcon)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        val badge: TextView = view.findViewById(R.id.tvBadge)
        val sbsState: TextView? = view.findViewById(R.id.tvSbsState)

        init {
            val deleteButton = view.findViewById<View?>(R.id.btnDelete)

            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.isLongClickable = false
            view.setOnClickListener { onClick(bindingAdapterPosition) }

            // Single-tap activation (touch ACTION_UP triggers click) so touch and D-pad behave consistently.
            view.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    v.requestFocus(); v.performClick(); true
                } else false
            }

            // D-pad RIGHT on a row jumps focus to its delete button (when present).
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (deleteButton?.visibility == View.VISIBLE) {
                        deleteButton.requestFocus(); return@setOnKeyListener true
                    }
                }
                false
            }

            deleteButton?.isFocusable = true
            deleteButton?.isFocusableInTouchMode = true
            deleteButton?.setOnClickListener { onDeleteIdx(bindingAdapterPosition) }
            deleteButton?.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { view.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        deleteButton.performClick(); true
                    }
                    else -> false
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false)
        return VH(
            v,
            onClick = { pos -> if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos)) },
            onDeleteIdx = { pos -> if (pos != RecyclerView.NO_POSITION) onDelete(getItem(pos)) }
        )
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = resolveNiceTitle(holder.itemView.context, item)
        holder.subtitle.text = buildString {
            append(holder.subtitle.context.getString(R.string.recent_position_prefix))
            append(formatMs(item.lastPositionMs))
            if (item.durationMs > 0) {
                append(" / ")
                append(formatMs(item.durationMs))
            }
        }
        
        // Source icon based on type
        val sourceType = item.sourceType ?: RecentEntry.detectSourceType(Uri.parse(item.uri))
        val iconRes = when (sourceType) {
            SourceType.VK -> R.drawable.ic_source_vk
            SourceType.OK -> R.drawable.ic_source_ok
            SourceType.LOCAL -> R.drawable.ic_source_local
            SourceType.NETWORK -> R.drawable.ic_source_network
            SourceType.YANDEX -> R.drawable.ic_source_yadisk
            SourceType.UNKNOWN -> R.drawable.ic_source_unknown
        }
        holder.sourceIcon.setImageResource(iconRes)
        
        // Frame-packing format badge from the URI hint (3 = SBS, 4 = OU), hide otherwise.
        when (item.framePacking) {
            3 -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = holder.badge.context.getString(R.string.menu_stereo_short)
            }
            4 -> {
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = holder.badge.context.getString(R.string.remote_ou_to_sbs)
            }
            else -> holder.badge.visibility = View.GONE
        }

        // Per-clip stereo-state badge straight from the saved effective mode (single source of
        // truth): 2 = SBS, 1 = OU→SBS, anything else (2D / unknown) shows no badge.
        val sbsLabelRes: Int? = when (item.stereoMode) {
            2 -> R.string.menu_stereo_short   // SBS
            1 -> R.string.remote_ou_to_sbs    // OU→SBS
            else -> null
        }
        if (sbsLabelRes != null) {
            holder.sbsState?.visibility = View.VISIBLE
            holder.sbsState?.text = holder.sbsState?.context?.getString(sbsLabelRes)
        } else {
            holder.sbsState?.visibility = View.GONE
        }
    }

    private fun resolveNiceTitle(context: android.content.Context, entry: RecentEntry): String {
        // 1) Try stored title
        if (entry.title.isNotBlank() && !entry.title.startsWith("msf:") && !entry.title.startsWith("content:")) {
            return entry.title
        }
        val uri = Uri.parse(entry.uri)
        // 2) Try DISPLAY_NAME from provider
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Throwable) { /* ignore */
        }
        // 3) Fallback to decoded lastPathSegment
        val last = uri.lastPathSegment ?: entry.uri
        return try {
            java.net.URLDecoder.decode(last, "UTF-8")
        } catch (_: Throwable) {
            last
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format(
            "%02d:%02d",
            m,
            s
        )
    }
}
