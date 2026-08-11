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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Connect to PC" screen for PC Link (PC -> glasses desktop streaming): lists PC servers found on
 * the LAN, offers manual IP entry, and — for now — hands off to [PlayerActivity] via a placeholder
 * intent carrying the `EXTRA_PCLINK_*` extras. Real PC Link playback wiring (actually opening the
 * stream) lands in a later package; [PlayerActivity] itself isn't touched here.
 *
 * Discovery is accessed through [PcLinkDiscoverySource] (see PcServerListState.kt) rather than the
 * real `com.teleteh.xplayer2.data.network.PcLinkDiscovery` directly, so this screen — and its unit
 * test — compile and work (manual-IP path, list state) independent of when that concurrent package
 * lands. [NoOpPcLinkDiscovery] is the placeholder in the meantime; swap it for a real adapter once
 * PcLinkDiscovery exists.
 */
class PcConnectActivity : AppCompatActivity() {

    private val listState = PcServerListState()
    private lateinit var adapter: ServerAdapter
    private lateinit var tvEmpty: TextView
    private lateinit var connectingOverlay: View
    private var discovery: PcLinkDiscoverySource = NoOpPcLinkDiscovery()
    private var connecting = false

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

        findViewById<View>(R.id.btnRefresh).setOnClickListener { startDiscovery() }

        startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery.stop()
    }

    private fun tryConnectManual(etHost: EditText) {
        val host = PcServerListState.validateHost(etHost.text?.toString().orEmpty())
        if (host == null) {
            Toast.makeText(this, R.string.pclink_invalid_host, Toast.LENGTH_SHORT).show()
            return
        }
        connectTo(
            PcLinkServer(
                name = host,
                host = host,
                controlPort = DEFAULT_CONTROL_PORT,
                videoPort = DEFAULT_VIDEO_PORT,
                protocolVersion = DEFAULT_PROTOCOL_VERSION,
            )
        )
    }

    /** (Re)starts discovery: clears the current list and asks [discovery] to look again. */
    private fun startDiscovery() {
        discovery.stop()
        listState.clear()
        adapter.submitList(listState.snapshot())
        updateEmptyState()
        // TODO(PC Link wiring): swap in a real com.teleteh.xplayer2.data.network.PcLinkDiscovery
        // (wrapped behind PcLinkDiscoverySource) once agent A2 lands it. Until then this finds
        // nothing on its own — manual IP entry above still works.
        discovery = NoOpPcLinkDiscovery()
        discovery.discover { server -> onServerDiscovered(server) }
    }

    private fun onServerDiscovered(server: PcLinkServer) {
        runOnUiThread {
            if (listState.addOrUpdate(server)) {
                adapter.submitList(listState.snapshot())
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        tvEmpty.visibility = if (!connecting && listState.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onServerClick(server: PcLinkServer) = connectTo(server)

    private fun connectTo(server: PcLinkServer) {
        if (connecting) return
        connecting = true
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
        // Placeholder default ports for manually-entered hosts, until the PC Link wire protocol's
        // real defaults are settled by the server/discovery packages. Discovered servers carry
        // their own ports and never use these.
        const val DEFAULT_CONTROL_PORT = 7890
        const val DEFAULT_VIDEO_PORT = 7891
        const val DEFAULT_PROTOCOL_VERSION = 1

        /** Extras on the placeholder intent toward [PlayerActivity]; real handling of these lands
         *  with the PC Link playback-wiring package. */
        const val EXTRA_PCLINK_HOST = "com.teleteh.xplayer2.extra.PCLINK_HOST"
        const val EXTRA_PCLINK_CONTROL_PORT = "com.teleteh.xplayer2.extra.PCLINK_CONTROL_PORT"
        const val EXTRA_PCLINK_VIDEO_PORT = "com.teleteh.xplayer2.extra.PCLINK_VIDEO_PORT"
        const val EXTRA_PCLINK_PROTOCOL_VERSION = "com.teleteh.xplayer2.extra.PCLINK_PROTOCOL_VERSION"
        const val EXTRA_PCLINK_NAME = "com.teleteh.xplayer2.extra.PCLINK_NAME"

        private const val CONNECTING_DELAY_MS = 450L
    }
}
