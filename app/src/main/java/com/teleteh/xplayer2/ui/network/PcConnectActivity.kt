package com.teleteh.xplayer2.ui.network

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.network.PcLinkServer
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Connect to PC" screen for PC Link (PC -> glasses desktop streaming): lists PC servers found on
 * the LAN (via [RealPcLinkDiscoverySource], wrapping `data.network.PcLinkDiscovery`'s UDP
 * broadcast), offers manual IP entry (probed the same way discovery probes a single host), and —
 * for now — hands off to [PlayerActivity] via a placeholder intent carrying the `EXTRA_PCLINK_*`
 * extras. Real PC Link playback wiring (actually opening the stream) lands in a later package;
 * [PlayerActivity] itself isn't touched here.
 *
 * Discovery is accessed through [PcLinkDiscoverySource] (see PcServerListState.kt) rather than
 * `data.network.PcLinkDiscovery` directly, keeping this screen's dependency on that concurrently-
 * owned package to a single adapter class. Two independent sources: [discoverySource] runs the
 * repeating LAN passes (restarted by Refresh, parked while the screen isn't STARTED), while
 * [probeSource] serves manual-IP probes so a stray Refresh can't cancel one mid-flight.
 */
class PcConnectActivity : AppCompatActivity() {

    private val listState = PcServerListState()
    private lateinit var adapter: ServerAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var connectingOverlay: View
    private lateinit var btnRefresh: View
    private var discoverySource: PcLinkDiscoverySource = NoOpPcLinkDiscovery()
    private var probeSource: PcLinkDiscoverySource = NoOpPcLinkDiscovery()
    private var discoveryLoop: Job? = null
    private var connecting = false
    private var probing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pc_connect)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.pclink_title)
        toolbar.setNavigationOnClickListener { finish() }

        tvEmpty = findViewById(R.id.tvEmpty)
        connectingOverlay = findViewById(R.id.connectingOverlay)

        val rv = findViewById<RecyclerView>(R.id.rvPcServers)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ServerAdapter { onServerClick(it) }
        rv.adapter = adapter

        val etHost = findViewById<EditText>(R.id.etManualHost)
        etHost.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        findViewById<View>(R.id.btnConnectManual).setOnClickListener { tryConnectManual(etHost) }
        etHost.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                tryConnectManual(etHost); true
            } else false
        }

        btnRefresh = findViewById(R.id.btnRefresh)
        btnRefresh.setOnClickListener { restartDiscovery() }

        discoverySource = RealPcLinkDiscoverySource(this, lifecycleScope)
        probeSource = RealPcLinkDiscoverySource(this, lifecycleScope)
        restartDiscovery()
    }

    override fun onStop() {
        super.onStop()
        // repeatOnLifecycle has already parked the loop; this also closes the socket of the pass
        // that was in flight, instead of letting it broadcast out the rest of its window.
        discoverySource.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoverySource.stop()
        probeSource.stop()
    }

    /** Validates the typed host, then probes it directly for a real PC Link reply before connecting
     *  (so we hand PlayerActivity the server's actual ports, not a guess). */
    private fun tryConnectManual(etHost: EditText) {
        if (connecting || probing) return
        val host = when (val input = PcServerListState.validateHost(etHost.text?.toString().orEmpty())) {
            is HostInput.Valid -> input.host
            HostInput.Ipv6Unsupported -> {
                Toast.makeText(this, R.string.pclink_error_ipv6_unsupported, Toast.LENGTH_LONG).show()
                return
            }
            HostInput.Invalid -> {
                Toast.makeText(this, R.string.pclink_invalid_host, Toast.LENGTH_SHORT).show()
                return
            }
        }
        probing = true
        btnRefresh.isEnabled = false
        connectingOverlay.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        // One probe, one outcome: probeHost reports null itself when the host stays silent, so
        // there is no second wall-clock timer here to race it — nor a late reply that could fire
        // connectTo() after "unreachable" was already shown.
        probeSource.probeHost(host) { server ->
            if (!probing) return@probeHost
            probing = false
            btnRefresh.isEnabled = true
            if (server != null) {
                connectTo(server)
            } else {
                connectingOverlay.visibility = View.GONE
                updateEmptyState()
                Toast.makeText(this, R.string.pclink_manual_unreachable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * (Re)starts discovery: clears the current list and runs back-to-back passes for as long as the
     * screen is STARTED — a PC that only starts serving a minute from now still shows up, which the
     * old single 4 s pass never allowed. Refresh just re-triggers this.
     */
    private fun restartDiscovery() {
        listState.clear()
        adapter.submitList(listState.snapshot())
        updateEmptyState()
        val previous = discoveryLoop
        discoveryLoop = lifecycleScope.launch {
            // Wait out the previous loop before touching the source, or its dying pass could be
            // cancelled after the new one starts.
            previous?.cancelAndJoin()
            discoverySource.stop()
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    discoverySource.discover { onServerDiscovered(it) }.join()
                    delay(DISCOVERY_PASS_GAP_MS)
                }
            }
        }
    }

    /** Called on the main thread (see [PcLinkDiscoverySource.discover]). */
    private fun onServerDiscovered(server: PcLinkServer) {
        if (listState.addOrUpdate(server)) {
            adapter.submitList(listState.snapshot())
            updateEmptyState()
        }
    }

    private fun updateEmptyState() {
        tvEmpty.visibility = if (!connecting && !probing && listState.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onServerClick(server: PcLinkServer) = connectTo(server)

    private fun connectTo(server: PcLinkServer) {
        if (connecting) return
        connecting = true
        discoveryLoop?.cancel()
        discoverySource.stop()
        connectingOverlay.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        // Brief "connecting…" beat before the placeholder hand-off — real handshake/progress
        // reporting belongs to the PC Link playback-wiring package, not this UI shell.
        lifecycleScope.launch {
            delay(CONNECTING_DELAY_MS)
            val intent = Intent(this@PcConnectActivity, PlayerActivity::class.java).apply {
                putExtra(EXTRA_PCLINK_HOST, server.host)
                putExtra(EXTRA_PCLINK_CONTROL_PORT, server.controlPort)
                putExtra(EXTRA_PCLINK_VIDEO_PORT, server.videoPort)
                putExtra(EXTRA_PCLINK_PROTOCOL_VERSION, server.protocolVersion)
                putExtra(EXTRA_PCLINK_NAME, server.name)
            }
            DisplayUtils.startOnBestDisplay(this@PcConnectActivity, intent)
            finish()
        }
    }

    private class ServerAdapter(
        private val onClick: (PcLinkServer) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.VH>() {
        private val items = mutableListOf<PcLinkServer>()

        fun submitList(list: List<PcLinkServer>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pc_server, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.name
            holder.subtitle.text = "${item.host}:${item.controlPort}"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTitle)
            val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        }
    }

    companion object {
        /** Extras on the placeholder intent toward [PlayerActivity]; real handling of these lands
         *  with the PC Link playback-wiring package. */
        const val EXTRA_PCLINK_HOST = "com.teleteh.xplayer2.extra.PCLINK_HOST"
        const val EXTRA_PCLINK_CONTROL_PORT = "com.teleteh.xplayer2.extra.PCLINK_CONTROL_PORT"
        const val EXTRA_PCLINK_VIDEO_PORT = "com.teleteh.xplayer2.extra.PCLINK_VIDEO_PORT"
        const val EXTRA_PCLINK_PROTOCOL_VERSION = "com.teleteh.xplayer2.extra.PCLINK_PROTOCOL_VERSION"
        const val EXTRA_PCLINK_NAME = "com.teleteh.xplayer2.extra.PCLINK_NAME"

        private const val CONNECTING_DELAY_MS = 450L

        /** Breather between discovery passes (each pass listens ~4 s), so a pass starts every ~5 s. */
        private const val DISCOVERY_PASS_GAP_MS = 1000L
    }
}
