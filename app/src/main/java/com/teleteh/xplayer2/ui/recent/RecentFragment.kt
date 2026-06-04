package com.teleteh.xplayer2.ui.recent

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.RecentEntry
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
            val uri = entry.uriObj()
            
            // Check if we still have permission to access this URI
            val hasPermission = try {
                if (uri.scheme == "content") {
                    ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                } else {
                    true // file:// or other schemes don't need permission check
                }
            } catch (_: Exception) { false }
            
            if (!hasPermission && uri.scheme == "content") {
                // Try to re-take permission (may fail if original grant expired)
                try {
                    ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    android.widget.Toast.makeText(ctx, R.string.file_unavailable, android.widget.Toast.LENGTH_LONG).show()
                    return@RecentAdapter
                }
            }
            
            val intent = Intent(ctx, PlayerActivity::class.java)
            intent.data = uri
            intent.putExtra(PlayerActivity.EXTRA_START_POSITION_MS, entry.lastPositionMs)
            // Pass the stored title so PlayerActivity starts with the correct display title
            intent.putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            DisplayUtils.startOnBestDisplay(requireActivity(), intent)
        }, onLongClick = { entry -> confirmDelete(entry) })
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        attachSwipeToDelete()
        loadData()
    }

    /** Swipe a Recent row either direction to delete it — replaces the old trash button. */
    private fun attachSwipeToDelete() {
        val bg = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.rc_danger))
        val trash = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_delete)
            ?.mutate()?.apply { setTint(Color.WHITE) }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val entry = adapter.currentList.getOrNull(pos) ?: return
                removeRecent(entry, withUndo = true)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isActive: Boolean
            ) {
                val v = vh.itemView
                when {
                    dX > 0 -> bg.setBounds(v.left, v.top, v.left + dX.toInt(), v.bottom)
                    dX < 0 -> bg.setBounds(v.right + dX.toInt(), v.top, v.right, v.bottom)
                    else -> bg.setBounds(0, 0, 0, 0)
                }
                bg.draw(c)
                trash?.let { ic ->
                    val ih = ic.intrinsicHeight
                    val iw = ic.intrinsicWidth
                    val top = v.top + (v.height - ih) / 2
                    if (dX > 0) ic.setBounds(v.left + pad, top, v.left + pad + iw, top + ih)
                    else ic.setBounds(v.right - pad - iw, top, v.right - pad, top + ih)
                    if (dX != 0f) ic.draw(c)
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isActive)
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(recycler)
    }

    private fun removeRecent(entry: RecentEntry, withUndo: Boolean) {
        RecentStore(requireContext()).delete(entry.uri)
        loadData()
        if (withUndo) {
            Snackbar.make(recycler, R.string.recent_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    RecentStore(requireContext()).upsert(entry)
                    loadData()
                }.show()
        }
    }

    /** Long-press delete (touch or D-pad) — a confirm dialog, since there's no swipe-undo for it. */
    private fun confirmDelete(entry: RecentEntry) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recent_delete_confirm)
            .setMessage(entry.title)
            .setPositiveButton(R.string.delete) { _, _ -> removeRecent(entry, withUndo = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
