package com.teleteh.xplayer2.ui.network

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.net.Uri
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.network.NetworkItem
import coil.load

class NetworkAdapter(
    private val onClick: (NetworkItem) -> Unit
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
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: NetworkItem, newItem: NetworkItem): Boolean = oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_network, parent, false)
        return VH(v, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(itemView: View, private val onClick: (NetworkItem) -> Unit) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.tvTitle)
        private val sub: TextView = itemView.findViewById(R.id.tvSubtitle)
        private val iconBg: ImageView = itemView.findViewById(R.id.ivIconBg)
        private val icon: ImageView = itemView.findViewById(R.id.ivIcon)
        private var current: NetworkItem? = null
        init {
            itemView.setOnClickListener { current?.let(onClick) }
        }
        fun bind(item: NetworkItem) {
            current = item
            when (item) {
                is NetworkItem.SmbShare -> {
                    title.text = item.name
                    sub.text = formatSmbSubtitle(item.uri)
                    iconBg.setImageResource(R.drawable.bg_circle_smb)
                    icon.setImageResource(R.drawable.ic_smb_24)
                }
                is NetworkItem.DlnaDevice -> {
                    title.text = item.friendlyName.ifBlank { "DLNA Device" }
                    sub.text = formatHttpSubtitle(item.location)
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
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
                    sub.text = "Вверх"
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    icon.setImageResource(R.drawable.ic_folder_24)
                }
                is NetworkItem.DlnaContainer -> {
                    title.text = item.title
                    sub.text = "Папка"
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    icon.setImageResource(R.drawable.ic_folder_24)
                }
                is NetworkItem.DlnaMedia -> {
                    title.text = item.title
                    sub.text = item.mime ?: item.url
                    iconBg.setImageResource(R.drawable.bg_circle_dlna)
                    val mime = item.mime ?: ""
                    icon.setImageResource(
                        if (mime.startsWith("audio")) R.drawable.ic_audio_24 else R.drawable.ic_video_24
                    )
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
