package com.teleteh.xplayer2.ui.pclink

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.glasses.GlassesPresence
import com.teleteh.xplayer2.data.network.PcLinkPairing
import com.teleteh.xplayer2.data.network.PcLinkPairingStore
import com.teleteh.xplayer2.player.PcLinkSession
import com.teleteh.xplayer2.ui.network.PcConnectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **The PC-Mirror tab — where a cast starts.**
 *
 * The desktop is not a film: it has its own way in (find a PC, compare six digits) and its own
 * hardware requirement (the glasses, which is the whole point of it), so it gets a tab rather than
 * a corner of the player's.
 *
 * What this screen is *for* is getting a session going, and it is the only place that can:
 *
 * * **the computers already paired with**, read straight out of [PcLinkPairingStore] — a return
 *   visit is one tap on a name, not a trip through discovery. Rows are forgotten by a swipe or a
 *   long press, exactly as Recent's rows are deleted, down to the undo;
 * * **Find your PC**, which opens [PcConnectActivity] — that screen is for *new* computers now,
 *   not the toll on every visit. The pairing ceremony stays there and only there: it is
 *   security-sensitive and already tested, and a second copy would be a second thing to keep right;
 * * **a session that is already running**, reduced to a card saying so and the way back to its
 *   remote. Nothing about how it is doing — frames, bitrate, sound, the debugging door — lives
 *   here. That belongs to [PcLinkRemoteActivity], which is what the user is actually holding while
 *   a cast runs, and keeping a second copy in the tab is how the two drifted apart before.
 */
class PcMirrorFragment : Fragment() {

    private val ticker = Handler(Looper.getMainLooper())

    private lateinit var cardSession: MaterialCardView
    private lateinit var boxIdle: View
    private lateinit var tvServerName: TextView
    private lateinit var tvLinkState: TextView
    private lateinit var tvPairedLabel: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var adapter: PairedAdapter

    private var store: PcLinkPairingStore? = null

    private val tick = object : Runnable {
        override fun run() {
            applySessionState()
            ticker.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_pc_mirror, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cardSession = view.findViewById(R.id.cardSession)
        boxIdle = view.findViewById(R.id.boxIdle)
        tvServerName = view.findViewById(R.id.tvServerName)
        tvLinkState = view.findViewById(R.id.tvLinkState)
        tvPairedLabel = view.findViewById(R.id.tvPairedLabel)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        recycler = view.findViewById(R.id.rvPairedPcs)

        adapter = PairedAdapter(
            onClick = { connectTo(it) },
            onLongClick = { confirmForget(it) },
            notSeenLabel = getString(R.string.pclink_paired_no_address)
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        recycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        attachSwipeToForget()

        view.findViewById<MaterialButton>(R.id.btnFindPc).setOnClickListener {
            if (!requireGlasses()) return@setOnClickListener
            startActivity(Intent(requireContext(), PcConnectActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.btnOpenRemote).setOnClickListener {
            // The same flags the player brings it up with, so a remote that already exists in the
            // task resurfaces instead of a second one landing on top of it.
            startActivity(
                Intent(requireContext(), PcLinkRemoteActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        view.findViewById<MaterialButton>(R.id.btnDisconnect).setOnClickListener {
            PcLinkSession.end()
            // Don't wait for the next tick to admit it's gone.
            applySessionState()
        }
    }

    override fun onResume() {
        super.onResume()
        // Only while the tab is actually in front: nobody is watching a card they can't see, and
        // the session's state lives in the session either way.
        ticker.removeCallbacks(tick)
        ticker.post(tick)
        loadPairings()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    /** Which of the two states this screen is in — asked on a clock, like the remote's numbers. */
    private fun applySessionState() {
        if (view == null) return
        val stats = PcLinkSession.stats()
        cardSession.visibility = if (stats == null) View.GONE else View.VISIBLE
        boxIdle.visibility = if (stats == null) View.VISIBLE else View.GONE
        if (stats == null) return
        tvServerName.text = stats.serverName
        tvLinkState.setText(
            when (stats.link) {
                PcLinkSession.Link.CONNECTING -> R.string.pclink_state_connecting
                PcLinkSession.Link.STREAMING -> R.string.pclink_state_streaming
                PcLinkSession.Link.RECONNECTING -> R.string.pclink_state_reconnecting
                PcLinkSession.Link.FAILED -> R.string.pclink_state_failed
            }
        )
    }

    // --- the computers we know --------------------------------------------------------------

    private fun loadPairings() {
        // Resolved here, on the main thread and while we are certainly attached: requireContext()
        // inside the IO block would throw if the tab were swiped away mid-read.
        val appContext = context?.applicationContext ?: return
        val existing = store
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val store = existing ?: PcLinkPairingStore(appContext)
                store to store.getAll()
            }
            if (view == null) return@launch
            store = loaded.first
            showPairings(loaded.second)
        }
    }

    private fun showPairings(pairings: List<PcLinkPairing>) {
        adapter.submitList(pairings)
        val empty = pairings.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvPairedLabel.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /**
     * A known PC, one tap.
     *
     * The tap still goes through [PcConnectActivity], which owns re-authentication: the stored key
     * proves who is on the other end, and skipping that here would mean a second implementation of
     * the one exchange that must never be got wrong. What the tap saves is the *browsing* — that
     * screen opens straight onto this PC with a spinner, and drops back to its list only if the
     * address it was given no longer answers.
     */
    private fun connectTo(pairing: PcLinkPairing) {
        if (!requireGlasses()) return
        val host = pairing.lastHost
        val intent = Intent(requireContext(), PcConnectActivity::class.java)
        if (host != null) intent.putExtra(PcConnectActivity.EXTRA_PCLINK_AUTOCONNECT_HOST, host)
        startActivity(intent)
    }

    /**
     * PC Link needs the glasses, and says so here rather than letting the user get all the way
     * through a pairing ceremony first. The same rule guards [PcConnectActivity] itself — this is
     * only the earlier, kinder half of it.
     */
    private fun requireGlasses(): Boolean {
        if (GlassesPresence.present(requireContext())) return true
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pclink_needs_glasses_title)
            .setMessage(R.string.pclink_needs_glasses_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return false
    }

    /**
     * Swipe a row either direction to forget that PC — the same gesture, the same red-and-trash
     * backdrop and the same undo Recent's rows have, because they are the same kind of list and
     * nobody should have to learn it twice.
     */
    private fun attachSwipeToForget() {
        val bg = ColorDrawable(ContextCompat.getColor(requireContext(), R.color.rc_danger))
        val trash = ContextCompat.getDrawable(requireContext(), android.R.drawable.ic_menu_delete)
            ?.mutate()?.apply { setTint(Color.WHITE) }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val cb = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val pairing = adapter.itemAt(pos) ?: return
                forget(pairing, withUndo = true)
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

    /** Long-press forget (touch or D-pad) — a confirm dialog, since there's no swipe-undo for it. */
    private fun confirmForget(pairing: PcLinkPairing): Boolean {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pclink_forget_title, pairing.name))
            .setMessage(R.string.pclink_forget_body)
            .setPositiveButton(R.string.pclink_forget) { _, _ -> forget(pairing, withUndo = false) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return true
    }

    /**
     * Removal goes through the store, so the key is really gone and the PC's next approach earns a
     * fresh six-digit ceremony rather than a silent reconnection. Undo puts the same record back —
     * the long-term key included, which is why the row must be held whole until the Snackbar goes.
     */
    private fun forget(pairing: PcLinkPairing, withUndo: Boolean) {
        val store = store ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.forget(pairing.serverId) }
            loadPairings()
            if (!withUndo) return@launch
            Snackbar.make(recycler, getString(R.string.pclink_forgotten, pairing.name), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            store.addOrUpdate(pairing.serverId, pairing.name, pairing.ltk, pairing.lastHost)
                        }
                        loadPairings()
                    }
                }.show()
        }
    }

    /**
     * A [ListAdapter] with a diff, like Recent's — not for the animation but because the swipe
     * depends on it: `notifyDataSetChanged` after a dismissal rebinds the swiped holder without
     * telling ItemTouchHelper its item is gone, and the row comes back still translated off the
     * side of the screen.
     */
    private class PairedAdapter(
        private val onClick: (PcLinkPairing) -> Unit,
        private val onLongClick: (PcLinkPairing) -> Boolean,
        /** Shown instead of an address for a PC we have never recorded one for. */
        private val notSeenLabel: String
    ) : ListAdapter<PcLinkPairing, PairedAdapter.VH>(Diff) {

        object Diff : DiffUtil.ItemCallback<PcLinkPairing>() {
            // A PC is its fingerprint; the name and the address around it are just what we know
            // about it today.
            override fun areItemsTheSame(oldItem: PcLinkPairing, newItem: PcLinkPairing): Boolean =
                oldItem.serverId == newItem.serverId

            override fun areContentsTheSame(oldItem: PcLinkPairing, newItem: PcLinkPairing): Boolean =
                oldItem == newItem
        }

        fun itemAt(position: Int): PcLinkPairing? = currentList.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pc_server, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            // The stored name, always: it is the string that was on screen next to the code the
            // user compared, so it is the name they actually approved. Nothing here comes off the
            // network, so there is nothing for a bystander to spoof into this list.
            holder.title.text = item.name
            // The last address we successfully used, stated as exactly that. Whether the PC is
            // awake and reachable right now is a question this screen has not asked, so it does
            // not answer it.
            holder.subtitle.text = item.lastHost ?: notSeenLabel
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTitle)
            val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
    }
}
