package com.teleteh.xplayer2.ui.network

import android.net.Uri
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
import coil3.dispose
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.placeholder
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.network.NetworkItem
import com.teleteh.xplayer2.data.network.WebSourceType

class NetworkAdapter(
    private val onClick: (NetworkItem) -> Unit,
    // Fired for the deletable rows: SMB shares and remembered web sources.
    private val onDelete: (NetworkItem) -> Unit = {}
) : ListAdapter<NetworkItem, NetworkAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<NetworkItem>() {
            override fun areItemsTheSame(oldItem: NetworkItem, newItem: NetworkItem): Boolean {
                return when {
                    oldItem is NetworkItem.SmbShare && newItem is NetworkItem.SmbShare -> oldItem.name == newItem.name
                    oldItem is NetworkItem.DlnaDevice && newItem is NetworkItem.DlnaDevice -> oldItem.usn == newItem.usn && oldItem.location == newItem.location
                    oldItem is NetworkItem.DlnaContainer && newItem is NetworkItem.DlnaContainer ->
                        oldItem.id == newItem.id && oldItem.controlUrl == newItem.controlUrl

                    oldItem is NetworkItem.DlnaMedia && newItem is NetworkItem.DlnaMedia ->
                        oldItem.url == newItem.url

                    oldItem is NetworkItem.DlnaUp && newItem is NetworkItem.DlnaUp -> true
                    oldItem is NetworkItem.WebSource && newItem is NetworkItem.WebSource -> oldItem.url == newItem.url
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: NetworkItem, newItem: NetworkItem): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_network, parent, false)
        return VH(v, onClick, onDelete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(
        itemView: View,
        private val onClick: (NetworkItem) -> Unit,
        private val onDelete: (NetworkItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvTitle)
        private val sub: TextView = itemView.findViewById(R.id.tvSubtitle)
        private val iconBg: ImageView = itemView.findViewById(R.id.ivIconBg)
        private val icon: ImageView = itemView.findViewById(R.id.ivIcon)
        private val deleteButton: View? = itemView.findViewById(R.id.btnDelete)
        private var current: NetworkItem? = null

        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true
            itemView.isLongClickable = false
            itemView.setOnClickListener { current?.let(onClick) }

            // Single-tap activation for consistency between touch and D-pad navigation.
            itemView.setOnTouchListener { v, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    v.requestFocus(); v.performClick(); true
                } else false
            }

            // D-pad RIGHT focuses the delete affordance (when visible — SMB shares only).
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (deleteButton?.visibility == View.VISIBLE) {
                        deleteButton.requestFocus(); return@setOnKeyListener true
                    }
                }
                false
            }

            deleteButton?.isFocusable = true
            deleteButton?.isFocusableInTouchMode = true
            deleteButton?.setOnClickListener {
                // Only the deletable rows have the button visible (SMB shares, remembered web sources).
                when (val c = current) {
                    is NetworkItem.SmbShare -> onDelete(c)
                    is NetworkItem.WebSource -> onDelete(c)
                    else -> {}
                }
            }
            deleteButton?.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { itemView.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        deleteButton.performClick(); true
                    }
                    else -> false
                }
            }
        }

        fun bind(item: NetworkItem) {
            current = item
            when (item) {
                is NetworkItem.SmbShare -> {
                    title.text = item.name
                    sub.text = formatSmbSubtitle(item.uri)
                    iconBg.setImageResource(R.drawable.bg_circle_smb)
                    icon.setImageResource(R.drawable.ic_smb_24)
                    deleteButton?.visibility = View.VISIBLE
                }

                is NetworkItem.DlnaDevice -> {
                    title.text = item.friendlyName.ifBlank { "DLNA Device" }
                    sub.text = formatHttpSubtitle(item.location)
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    deleteButton?.visibility = View.GONE
                    val iurl = item.iconUrl
                    if (!iurl.isNullOrBlank()) {
                        icon.load(iurl) {
                            crossfade(true)
                            placeholder(R.drawable.ic_dlna_24)
                            error(R.drawable.ic_dlna_24)
                        }
                    } else {
                        icon.setImageResource(R.drawable.ic_dlna_24)
                    }
                }

                is NetworkItem.DlnaUp -> {
                    title.text = "…"
                    sub.text = sub.context.getString(R.string.dlna_up_subtitle)
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    icon.setImageResource(R.drawable.ic_folder_24)
                    deleteButton?.visibility = View.GONE
                }

                is NetworkItem.DlnaContainer -> {
                    title.text = item.title
                    sub.text = sub.context.getString(R.string.dlna_folder_subtitle)
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    icon.setImageResource(R.drawable.ic_folder_24)
                    deleteButton?.visibility = View.GONE
                }

                is NetworkItem.DlnaMedia -> {
                    title.text = item.title
                    sub.text = item.mime ?: item.url
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    deleteButton?.visibility = View.GONE
                    val mime = item.mime ?: ""
                    icon.setImageResource(
                        if (mime.startsWith("audio")) R.drawable.ic_audio_24 else R.drawable.ic_video_24
                    )
                }

                is NetworkItem.WebSource -> {
                    title.text = item.title
                    sub.text = item.url
                    // Brand logos are 240×240 PNGs — the icon ImageView is a fixed 40dp box with
                    // padding + centerInside, so they scale down to the row size (never intrinsic).
                    // No circular background here (the logos are self-contained), so clear it.
                    iconBg.setImageDrawable(null)
                    // Cancel any in-flight Coil load bound to this recycled view (a DLNA device icon)
                    // before setting the static brand logo, so it can't get overwritten asynchronously.
                    icon.dispose()
                    icon.setImageResource(
                        when (item.type) {
                            WebSourceType.YADISK_FOLDER -> R.drawable.ic_source_yadisk
                            WebSourceType.MAILRU_FOLDER -> R.drawable.ic_source_mailru
                            WebSourceType.VK_PLAYLIST, WebSourceType.VK_GROUP -> R.drawable.ic_source_vk
                        }
                    )
                    deleteButton?.visibility = View.VISIBLE
                }
            }
        }

        private fun formatSmbSubtitle(uriStr: String): String {
            return try {
                val u = Uri.parse(uriStr)
                val host = u.host ?: uriStr
                val port = if (u.port != -1) ":${u.port}" else ""
                val share = u.pathSegments.firstOrNull()?.let { "/$it" } ?: ""
                "$host$port$share"
            } catch (_: Exception) {
                uriStr
            }
        }

        private fun formatHttpSubtitle(uriStr: String): String {
            return try {
                val u = Uri.parse(uriStr)
                val host = u.host ?: uriStr
                val port = if (u.port != -1) ":${u.port}" else ""
                "$host$port"
            } catch (_: Exception) {
                uriStr
            }
        }
    }
}
