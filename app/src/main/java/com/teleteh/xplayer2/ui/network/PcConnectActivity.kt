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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.network.PairingFailure
import com.teleteh.xplayer2.data.network.PairingOutcome
import com.teleteh.xplayer2.data.network.PairingSession
import com.teleteh.xplayer2.data.network.PcLinkPairInvite
import com.teleteh.xplayer2.data.network.PcLinkPairing
import com.teleteh.xplayer2.data.network.PcLinkPairingClient
import com.teleteh.xplayer2.data.network.PcLinkPairingCrypto
import com.teleteh.xplayer2.data.network.PcLinkDiscovery
import com.teleteh.xplayer2.data.network.PcLinkPairingStore
import com.teleteh.xplayer2.data.network.PcLinkPhoneResponder
import com.teleteh.xplayer2.data.network.PcLinkServer
import com.teleteh.xplayer2.player.PlayerActivity
import com.teleteh.xplayer2.ui.util.DisplayUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Connect to PC" screen for PC Link (PC -> glasses desktop streaming): lists PC servers found on
 * the LAN (via [RealPcLinkDiscoverySource], wrapping `data.network.PcLinkDiscovery`'s UDP
 * broadcast), offers manual IP entry (probed the same way discovery probes a single host), and owns
 * the device-pairing ceremony before handing off to [PlayerActivity] with the `EXTRA_PCLINK_*`
 * extras.
 *
 * Pairing (design doc `xplayer-link-server/docs/pairing-design.md`) sits between "user tapped a PC"
 * and the hand-off:
 *
 * * **Never paired** — run the SAS ceremony: the phone and the PC each show six digits, the user
 *   compares them and taps Pair here (and Accept on the PC), and only then is a long-term key
 *   stored on both sides.
 * * **Already paired** — a silent mutual-HMAC re-authentication behind a spinner. If the PC has
 *   forgotten us we offer a fresh ceremony, but only behind an explicit tap: re-pairing must always
 *   resurface the code, so a MITM can't force one invisibly (§8.4). If the PC fails to prove
 *   itself, we say so and offer nothing.
 * * **PC-initiated** — while this screen is open, [PcLinkPhoneResponder] answers PC probes and
 *   turns a `pair_invite` into a prompt that runs the identical ceremony.
 *
 * The ceremony's control connection is separate and short-lived ([PcLinkPairingClient]); the player
 * opens its own and re-authenticates with the key stored here.
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
    private lateinit var tvConnecting: TextView
    private lateinit var btnRefresh: View
    private var discoverySource: PcLinkDiscoverySource = NoOpPcLinkDiscovery()
    private var probeSource: PcLinkDiscoverySource = NoOpPcLinkDiscovery()
    private var discoveryLoop: Job? = null
    private var connecting = false
    private var probing = false

    /** Null until the identity has been loaded off the main thread; rows still connect meanwhile. */
    private var store: PcLinkPairingStore? = null
    private var identity: PcLinkPairingCrypto.Identity? = null
    private var responder: PcLinkPhoneResponder? = null

    /** The pairing/auth exchange in flight, if any, and the dialogs belonging to it. */
    private var pairingClient: PcLinkPairingClient? = null
    private var pairingDialog: AlertDialog? = null
    private var promptDialog: AlertDialog? = null

    /**
     * Paired PCs by fingerprint and by last-known address, mapped to their **stored** names — see
     * [pairedNameFor] for why a badged row must never be labelled from discovery. Refreshed
     * whenever the store changes.
     */
    private var pairedById: Map<String, String> = emptyMap()
    private var pairedByHost: Map<String, String> = emptyMap()

    /**
     * A re-pair the player bounced back to us ([EXTRA_PCLINK_REPAIR]), held until the identity has
     * finished loading — the prompt is worthless without a key to pair with.
     */
    private var pendingRepair: PairingTarget? = null

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
        tvConnecting = findViewById(R.id.tvConnecting)

        val rv = findViewById<RecyclerView>(R.id.rvPcServers)
        rv.layoutManager = LinearLayoutManager(this)
        adapter = ServerAdapter(
            onClick = { onServerClick(it) },
            onLongClick = { onServerLongClick(it) },
            pairedLabel = getString(R.string.pclink_paired_badge),
            pairedName = { pairedNameFor(it) }
        )
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
        pendingRepair = repairRequestFrom(intent)
        loadIdentity()
        restartDiscovery()
    }

    override fun onStart() {
        super.onStart()
        syncResponder()
    }

    override fun onStop() {
        super.onStop()
        // repeatOnLifecycle has already parked the loop; this also closes the socket of the pass
        // that was in flight, instead of letting it broadcast out the rest of its window.
        discoverySource.stop()
        // The phone answers PC probes only while this screen is up: that gate is the privacy story
        // for a stable, LAN-visible clientId, and it means a stray invite can't interrupt anything.
        responder?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        discoverySource.stop()
        probeSource.stop()
        responder?.stop()
        pairingClient?.cancel()
        pairingDialog?.dismiss()
        promptDialog?.dismiss()
    }

    /**
     * Loads (or generates once) this phone's long-term identity. Off the main thread: it touches
     * SharedPreferences and, on first run, generates a keypair and an AndroidKeyStore key.
     */
    private fun loadIdentity() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val store = PcLinkPairingStore(applicationContext)
                Triple(store, store.identity(), store.getAll())
            }
            store = loaded.first
            identity = loaded.second
            applyPairings(loaded.third)
            syncResponder()
            consumePendingRepair()
        }
    }

    /**
     * The player's connection was refused with `unknown_client` (protocol.md §2.14): that PC has
     * forgotten this phone, and only a fresh ceremony can fix it.
     *
     * The extras describe the PC the player was talking to, so no discovery pass is needed to find
     * it again — but nothing starts on its own. §8.4 makes the tap mandatory: a re-pair must always
     * put the 6-digit code back in front of the user, or an attacker who can drop our packets could
     * strip the pairing and have us silently re-establish one with them.
     */
    private fun repairRequestFrom(intent: Intent?): PairingTarget? {
        if (intent?.getBooleanExtra(EXTRA_PCLINK_REPAIR, false) != true) return null
        val host = intent.getStringExtra(EXTRA_PCLINK_HOST)?.takeIf { it.isNotBlank() } ?: return null
        val controlPort = intent.getIntExtra(EXTRA_PCLINK_CONTROL_PORT, 0)
        val videoPort = intent.getIntExtra(EXTRA_PCLINK_VIDEO_PORT, 0)
        if (controlPort <= 0 || videoPort <= 0) return null
        val name = intent.getStringExtra(EXTRA_PCLINK_NAME)?.takeIf { it.isNotBlank() } ?: host
        return PairingTarget(
            host = host,
            controlPort = controlPort,
            displayName = name,
            // Ports we already know, so the hand-off after a successful ceremony needs no probe.
            server = PcLinkServer(
                name = name,
                host = host,
                controlPort = controlPort,
                videoPort = videoPort,
                protocolVersion = intent.getIntExtra(
                    EXTRA_PCLINK_PROTOCOL_VERSION, PcLinkDiscovery.PROTOCOL_VERSION
                ),
                // Deliberately not the fingerprint the player bounced back. We are here *because*
                // that PC refused it, and the reason may be that it regenerated its identity — in
                // which case the old id names nothing. Carrying it would put a known-suspect value
                // in a field every reader is entitled to treat as an identity hint; the ceremony
                // below produces the real one, and [startPlayer] takes it from there.
                serverId = null
            )
        )
    }

    private fun consumePendingRepair() {
        val target = pendingRepair ?: return
        val identity = identity ?: return
        pendingRepair = null
        showPrompt(
            title = getString(R.string.pclink_repair_title),
            message = getString(R.string.pclink_repair_body),
            positiveRes = R.string.pclink_repair_accept,
            onAccept = {
                claimScreenForSession()
                startPairing(identity, target)
            }
        )
    }

    /** Starts/stops the reverse-discovery responder to match the screen's state and readiness. */
    private fun syncResponder() {
        val id = identity ?: return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        val existing = responder ?: PcLinkPhoneResponder(
            context = this,
            clientName = PcLinkPairingClient.defaultClientName(),
            clientId = id.fingerprint,
            onInvite = { onPairInvite(it) }
        ).also { responder = it }
        existing.start(lifecycleScope)
    }

    private fun applyPairings(pairings: List<PcLinkPairing>) {
        pairedById = pairings.associate { it.serverId to it.name }
        // Newest first (getAll's order), so associateBy's last-wins would pick the *oldest* record
        // when a regenerated PC has left an orphan on the same address — reverse it so the fresh
        // pairing's name is the one shown, matching findByHost.
        pairedByHost = pairings.reversed()
            .mapNotNull { pairing -> pairing.lastHost?.lowercase()?.let { it to pairing.name } }
            .toMap()
        adapter.notifyDataSetChanged()
    }

    private fun refreshPairings() {
        val store = store ?: return
        lifecycleScope.launch {
            applyPairings(withContext(Dispatchers.IO) { store.getAll() })
        }
    }

    /**
     * The stored pairing for [server], or null if we've never paired with it.
     *
     * When the discovery reply carried a `serverId` that is the whole answer — a fingerprint we
     * don't know means a PC we haven't paired with, even if something else once lived at that
     * address. Only for a server that didn't advertise one do we fall back to the address.
     */
    private fun storedPairingFor(server: PcLinkServer): PcLinkPairing? {
        val store = store ?: return null
        val serverId = server.serverId
        return if (serverId != null) store.get(serverId) else store.findByHost(server.host)
    }

    /**
     * The **stored** name for a paired PC, or null if we aren't paired with it.
     *
     * The stored name is the one bound into the pairing transcript — the string that was on screen
     * next to the 6-digit code the user compared, so it is the name they actually approved. The
     * `name` in a discovery reply is neither: it is an arbitrary string from an unauthenticated
     * datagram that anyone on the LAN can send.
     *
     * Which one a row shows matters precisely because the "Paired" badge next to it asserts trust.
     * `serverId` is public — it is broadcast in the clear — so a bystander can spoof a reply
     * carrying a paired fingerprint and any name they like, and the row would read as a PC the user
     * recognizes. The ceremony still can't be faked (tapping it fails the server's proof), but the
     * label would already have lied, which is enough to get the tap. So a badged row is labelled
     * from the store, and only an unpaired row shows what the network claimed.
     */
    private fun pairedNameFor(server: PcLinkServer): String? {
        val serverId = server.serverId
        return if (serverId != null) pairedById[serverId] else pairedByHost[server.host.lowercase()]
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
        showOverlay(R.string.pclink_connecting)
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
                hideOverlay()
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

    private fun showOverlay(messageRes: Int) {
        tvConnecting.setText(messageRes)
        connectingOverlay.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
    }

    private fun hideOverlay() {
        connectingOverlay.visibility = View.GONE
        updateEmptyState()
    }

    private fun onServerClick(server: PcLinkServer) = connectTo(server)

    /** Long-press offers to forget a paired PC; there is nothing to forget about the others. */
    private fun onServerLongClick(server: PcLinkServer): Boolean {
        if (connecting || probing) return false
        val pairing = storedPairingFor(server) ?: return false
        showPrompt(
            title = getString(R.string.pclink_forget_title, pairing.name),
            message = getString(R.string.pclink_forget_body),
            positiveRes = R.string.pclink_forget,
            onAccept = { forget(pairing) }
        )
        return true
    }

    private fun forget(pairing: PcLinkPairing) {
        val store = store ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.forget(pairing.serverId) }
            refreshPairings()
            Toast.makeText(
                this@PcConnectActivity,
                getString(R.string.pclink_forgotten, pairing.name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Entry point for every "connect to this PC" path. Decides between silent re-authentication and
     * the pairing ceremony, then hands off.
     */
    private fun connectTo(server: PcLinkServer) {
        if (connecting) return
        val identity = identity
        if (identity == null) {
            // The identity is still loading (first launch, generating a keypair). It's a moment,
            // and the row stays tappable rather than us pairing with no key.
            Toast.makeText(this, R.string.pclink_connecting, Toast.LENGTH_SHORT).show()
            return
        }
        claimScreenForSession()

        val pairing = storedPairingFor(server)
        val target = PairingTarget(server.host, server.controlPort, server.name, server)
        if (pairing != null) {
            startAuth(identity, target, pairing)
        } else {
            startPairing(identity, target)
        }
    }

    /**
     * Marks the screen as busy with one PC and parks discovery for the duration: a ceremony has a
     * human in it, and rows shuffling underneath while someone reads a code is no help to anyone.
     */
    private fun claimScreenForSession() {
        connecting = true
        discoveryLoop?.cancel()
        discoverySource.stop()
    }

    /** Silent re-authentication with a stored key, behind a spinner (§5). */
    private fun startAuth(
        identity: PcLinkPairingCrypto.Identity,
        target: PairingTarget,
        pairing: PcLinkPairing
    ) {
        showOverlay(R.string.pclink_authenticating)
        val store = store
        // The known pairing leads; the rest are only ever tried against the server's own proof, so
        // a PC whose address changed since we paired still authenticates instead of failing.
        val candidates = listOf(pairing) +
            (store?.getAll()?.filter { it.serverId != pairing.serverId } ?: emptyList())
        runSession(target, PairingSession.authenticate(identity, candidates))
    }

    /** First-time pairing: the 6-digit comparison ceremony (§4). */
    private fun startPairing(identity: PcLinkPairingCrypto.Identity, target: PairingTarget) {
        showOverlay(R.string.pclink_pairing_progress)
        runSession(
            target,
            PairingSession.pair(identity, PcLinkPairingClient.defaultClientName())
        )
    }

    private fun runSession(target: PairingTarget, session: PairingSession) {
        pairingClient?.cancel()
        val client = PcLinkPairingClient(
            context = this,
            host = target.host,
            controlPort = target.controlPort,
            session = session,
            listener = object : PcLinkPairingClient.Listener {
                override fun onSasReady(sas: String, serverName: String, serverId: String) =
                    showSasDialog(sas, serverName)

                override fun onPaired(serverId: String, serverName: String, ltk: ByteArray) {
                    // Stored synchronously: the hand-off below starts a PlayerActivity that reads
                    // this very record, so it must be on disk before the outcome is reported.
                    store?.addOrUpdate(serverId, serverName, ltk, target.host)
                }

                override fun onFinished(outcome: PairingOutcome) = onSessionFinished(target, outcome)
            }
        )
        pairingClient = client
        client.start(lifecycleScope)
    }

    private fun showSasDialog(sas: String, serverName: String) {
        pairingDialog?.dismiss()
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_pc_pairing, null)
        view.findViewById<TextView>(R.id.tvPairingCode).text = PcLinkPairingCrypto.formatSas(sas)
        pairingDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.pclink_pair_title, serverName))
            .setView(view)
            .setPositiveButton(R.string.pclink_pair_accept) { _, _ -> pairingClient?.accept() }
            .setNegativeButton(R.string.common_cancel) { _, _ -> pairingClient?.decline() }
            // Back/outside tap is a Cancel, not a silent dismissal: telling the PC we're gone takes
            // its dialog down now instead of leaving it up for the full 90 s timeout.
            .setOnCancelListener { pairingClient?.decline() }
            .show()
    }

    private fun onSessionFinished(target: PairingTarget, outcome: PairingOutcome) {
        pairingDialog?.dismiss()
        pairingDialog = null
        pairingClient = null
        when (outcome) {
            is PairingOutcome.Success -> {
                if (outcome.paired) refreshPairings() else touchPairing(outcome, target)
                handOff(target, outcome.serverId, outcome.serverName)
            }

            is PairingOutcome.Failure -> {
                connecting = false
                hideOverlay()
                restartDiscovery()
                onPairingFailed(target, outcome.reason)
            }
        }
    }

    private fun touchPairing(outcome: PairingOutcome.Success, target: PairingTarget) {
        val store = store ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                store.touch(outcome.serverId, target.host, outcome.serverName)
            }
        }
    }

    private fun onPairingFailed(target: PairingTarget, reason: PairingFailure) {
        when (reason) {
            // The user knows: they just tapped Cancel.
            PairingFailure.DECLINED_LOCALLY -> Unit

            // §8.4: the PC has forgotten us. Offer a fresh ceremony, but only behind a tap — an
            // automatic re-pair would let a MITM strip the pairing without the code ever resurfacing.
            PairingFailure.UNKNOWN_TO_PC -> showPrompt(
                title = getString(R.string.pclink_repair_title),
                message = getString(R.string.pclink_repair_body),
                positiveRes = R.string.pclink_repair_accept,
                onAccept = {
                    val identity = identity ?: return@showPrompt
                    claimScreenForSession()
                    startPairing(identity, target)
                }
            )

            // An impostor or a corrupted pairing. No re-pair button: "Forget this PC" is a
            // long-press away for someone who has decided it really is corruption.
            PairingFailure.AUTH_FAILED -> showMessage(R.string.pclink_auth_failed)

            PairingFailure.DECLINED_BY_PC -> toast(R.string.pclink_pair_cancelled)
            PairingFailure.PC_BUSY -> toast(R.string.pclink_pair_busy)
            PairingFailure.RATE_LIMITED -> toast(R.string.pclink_pair_rate_limited)
            PairingFailure.VERSION_UNSUPPORTED -> toast(R.string.pclink_pair_version)
            PairingFailure.TIMEOUT -> toast(R.string.pclink_pair_timeout)
            PairingFailure.CONNECTION_LOST -> toast(R.string.pclink_pair_lost)
            PairingFailure.PROTOCOL, PairingFailure.CONFIRM_MISMATCH -> toast(R.string.pclink_pair_failed)
        }
    }

    /**
     * A PC asked to pair (design §9.2). The invite carries no authority — it only saves the user
     * finding the PC in the list — so it becomes a prompt, never an action, and one at a time.
     */
    private fun onPairInvite(invite: PcLinkPairInvite) {
        if (connecting || probing || promptDialog != null || pairingDialog != null) return
        val identity = identity ?: return
        showPrompt(
            title = getString(R.string.pclink_invite_title, invite.serverName),
            message = null,
            positiveRes = R.string.pclink_invite_accept,
            negativeRes = R.string.pclink_invite_ignore,
            onAccept = {
                claimScreenForSession()
                // The ceremony runs against the datagram's source address, not anything it claimed.
                startPairing(
                    identity,
                    PairingTarget(invite.host, invite.controlPort, invite.serverName, server = null)
                )
            }
        )
    }

    /**
     * Hands off to the player. [PairingTarget.server] is absent when we got here from an invite, so
     * the ports aren't known yet — probe for them rather than guess, and if the PC doesn't answer,
     * say the pairing worked and leave the user on the list.
     */
    private fun handOff(target: PairingTarget, serverId: String, serverName: String) {
        val server = target.server
        if (server != null) {
            startPlayer(server, serverId, serverName)
            return
        }
        probeSource.probeHost(target.host) { probed ->
            if (probed != null) {
                startPlayer(probed, serverId, serverName)
            } else {
                connecting = false
                hideOverlay()
                refreshPairings()
                restartDiscovery()
            }
        }
    }

    private fun startPlayer(server: PcLinkServer, serverId: String, serverName: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(EXTRA_PCLINK_HOST, server.host)
            putExtra(EXTRA_PCLINK_CONTROL_PORT, server.controlPort)
            putExtra(EXTRA_PCLINK_VIDEO_PORT, server.videoPort)
            putExtra(EXTRA_PCLINK_PROTOCOL_VERSION, server.protocolVersion)
            // The transcript-bound name, for the same reason the list labels paired rows from the
            // store: the player prints this in its status overlay, and `server.name` is whatever an
            // unauthenticated datagram claimed. Falls back to the advertised name only if the
            // exchange somehow yielded none.
            putExtra(EXTRA_PCLINK_NAME, serverName.ifEmpty { server.name })
            // [serverId] comes from the exchange we just completed, so it is the fingerprint the
            // pairing was actually stored under. `server.serverId` is only ever a hint — discovery
            // is unauthenticated (§8.4), and on the re-pair path it is the *stale* fingerprint the
            // player bounced back to us, which is precisely the one that no longer works when a PC
            // forgot us by regenerating its identity. Preferring the hint there would hand the
            // player an id with no stored pairing, earning another `unknown_client` and another
            // bounce: a re-pair loop in which every ceremony succeeds.
            putExtra(EXTRA_PCLINK_SERVER_ID, serverId.ifEmpty { server.serverId.orEmpty() })
        }
        DisplayUtils.startOnBestDisplay(this, intent)
        finish()
    }

    private fun showPrompt(
        title: String,
        message: String?,
        positiveRes: Int,
        negativeRes: Int = R.string.common_cancel,
        onAccept: () -> Unit
    ) {
        promptDialog?.dismiss()
        promptDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setPositiveButton(positiveRes) { _, _ -> promptDialog = null; onAccept() }
            .setNegativeButton(negativeRes) { _, _ -> promptDialog = null }
            .setOnDismissListener { promptDialog = null }
            .show()
    }

    private fun showMessage(messageRes: Int) {
        promptDialog?.dismiss()
        promptDialog = AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { promptDialog = null }
            .show()
    }

    private fun toast(messageRes: Int) =
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()

    /** Where a ceremony is headed. [server] is null for an invite, whose ports we don't know yet. */
    private data class PairingTarget(
        val host: String,
        val controlPort: Int,
        val displayName: String,
        val server: PcLinkServer?
    )

    private class ServerAdapter(
        private val onClick: (PcLinkServer) -> Unit,
        private val onLongClick: (PcLinkServer) -> Boolean,
        private val pairedLabel: String,
        /** The PC's stored name when paired, else null — see [pairedNameFor]. */
        private val pairedName: (PcLinkServer) -> String?
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
            // A paired PC is named from the store, never from the discovery reply: the badge below
            // asserts trust, and the advertised name is an unauthenticated string anyone on the LAN
            // can choose. Only an unpaired row shows what the network claimed about itself.
            val stored = pairedName(item)
            holder.title.text = stored ?: item.name
            val address = "${item.host}:${item.controlPort}"
            // Badge in the subtitle rather than a new view: it keeps item_pc_server.xml as it is,
            // and "paired" is exactly the sort of detail that belongs next to the address.
            holder.subtitle.text = if (stored != null) "$address · $pairedLabel" else address
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener { onLongClick(item) }
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvTitle)
            val subtitle: TextView = view.findViewById(R.id.tvSubtitle)
        }
    }

    companion object {
        /**
         * Extras on the intent toward [PlayerActivity].
         *
         * [startPlayer] is the only place in the app that mints these, and that is load-bearing
         * rather than incidental. It runs only after a completed pairing or re-authentication, so
         * [EXTRA_PCLINK_NAME] and [EXTRA_PCLINK_SERVER_ID] carry values the *exchange* proved
         * rather than values a discovery reply claimed — which is what makes them safe for the
         * player to print. Its status overlay shows that name on "couldn't verify this PC", and a
         * name an attacker chose is precisely the reassurance that talks someone past a warning.
         *
         * So a future "connect without pairing" shortcut must not simply launch [PlayerActivity]
         * with these extras: it would reintroduce that substitution at the new producer, where
         * nothing on this side would catch it. Any new producer either completes an exchange first,
         * or leaves the name and fingerprint out.
         */
        const val EXTRA_PCLINK_HOST = "com.teleteh.xplayer2.extra.PCLINK_HOST"
        const val EXTRA_PCLINK_CONTROL_PORT = "com.teleteh.xplayer2.extra.PCLINK_CONTROL_PORT"
        const val EXTRA_PCLINK_VIDEO_PORT = "com.teleteh.xplayer2.extra.PCLINK_VIDEO_PORT"
        const val EXTRA_PCLINK_PROTOCOL_VERSION = "com.teleteh.xplayer2.extra.PCLINK_PROTOCOL_VERSION"
        const val EXTRA_PCLINK_NAME = "com.teleteh.xplayer2.extra.PCLINK_NAME"

        /**
         * The PC's identity fingerprint, so the player can look up the pairing this screen just
         * established or verified and authenticate its own connection with it. The video token
         * issued during pairing is deliberately *not* passed along: it dies with the control
         * session that minted it (protocol.md §2.13).
         */
        const val EXTRA_PCLINK_SERVER_ID = "com.teleteh.xplayer2.extra.PCLINK_SERVER_ID"

        /**
         * Set by [PlayerActivity] on the way *back* here, alongside the extras above, when its own
         * connection was refused with `unknown_client`: this screen reappears and offers a fresh
         * ceremony for that PC. A request, never an instruction — see [repairRequestFrom].
         */
        const val EXTRA_PCLINK_REPAIR = "com.teleteh.xplayer2.extra.PCLINK_REPAIR"

        /** Breather between discovery passes (each pass listens ~4 s), so a pass starts every ~5 s. */
        private const val DISCOVERY_PASS_GAP_MS = 1000L
    }
}
