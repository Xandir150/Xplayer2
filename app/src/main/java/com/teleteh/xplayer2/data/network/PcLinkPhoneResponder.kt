package com.teleteh.xplayer2.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * A PC's invitation to pair, delivered as one unicast UDP datagram (design §9.2).
 *
 * Carries no authority whatsoever — it is a doorbell. [host] is the datagram's *source* address,
 * not anything the payload claimed, so the ceremony always runs against whoever actually sent it.
 */
data class PcLinkPairInvite(
    /**
     * What the sender calls itself — an **unauthenticated claim**, not a name to keep. Fine for the
     * "«…» wants to pair" prompt, since the user is being asked whether to start a ceremony at all;
     * never store it or label a paired device with it. The name that gets stored comes out of the
     * ceremony's transcript, which is the one the user compared a code against.
     */
    val serverName: String,
    /**
     * Also an unauthenticated claim. Use it to choose which stored pairing to try first, and for
     * nothing else — in particular, never to badge or label a row.
     *
     * "It never grants trust" is too weak a rule to hold onto, because it reads as satisfied by the
     * thing that actually goes wrong: putting a spoofed id's name under a badge grants no trust by
     * itself, the badge does that, and the tap follows. State the prohibition, not the principle.
     */
    val serverId: String?,
    val host: String,
    val controlPort: Int,
    val protocolVersion: Int,
    val pairingVersion: Int
)

/**
 * Reverse discovery: lets a PC find this phone and offer to pair, so the user can start from either
 * device (design §9.2 / protocol.md §1.1).
 *
 * Listens on the same well-known UDP port as forward discovery — **48630** — because the probe
 * payload, not the port, says who should answer: PCs send `XPL2-PROBE v1`, PCs looking for phones
 * send `XPL2-PHONE-PROBE v1`, and the protocol's "ignore anything else, silently" rule lets both
 * live on one LAN without a second port. Sharing the port also means this socket receives our own
 * outgoing discovery broadcasts; they are server probes, so they fall straight through the ignore
 * path. [PcLinkDiscovery]'s own socket is unbound (ephemeral port) and doesn't conflict.
 *
 * Binding 48630 can still fail — most plausibly on a device that is itself running a PC Link server,
 * which is not a configuration we support but also not one worth crashing over. The failure is
 * logged and the responder simply does nothing: reverse discovery is a convenience, and the user
 * can always pick the PC from the list instead.
 *
 * Runs only while the PC Link screen is STARTED. That gate is the privacy story (the phone's
 * `clientId` is a stable LAN-visible identifier) and most of the abuse story (an invite can't
 * interrupt anything if nothing is listening).
 */
class PcLinkPhoneResponder(
    context: Context?,
    private val clientName: String,
    private val clientId: String,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onInvite: (PcLinkPairInvite) -> Unit
) {

    private val appContext: Context? = context?.applicationContext

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var job: Job? = null

    /** Last time we forwarded an invite from each source, for the per-source dedupe. */
    private val lastInviteAt = HashMap<String, Long>()

    /** Starts listening. Safe to call when already running (no-op). */
    fun start(scope: CoroutineScope): Job? {
        if (job?.isActive == true) return job
        val started = scope.launch(Dispatchers.IO) { listen() }
        job = started
        return started
    }

    /** Stops listening and releases the port. Reusable afterwards. */
    fun stop() {
        job?.cancel()
        job = null
        val open = socket
        socket = null
        try { open?.close() } catch (_: Exception) { }
        synchronized(lastInviteAt) { lastInviteAt.clear() }
    }

    private suspend fun listen() {
        val bound = openSocket() ?: return
        socket = bound
        val buffer = ByteArray(MAX_PACKET_SIZE)
        try {
            bound.use {
                while (currentCoroutineContext().isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        it.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue // poll interval; loop round so cancellation is noticed promptly
                    } catch (_: IOException) {
                        // Closed socket (stop/cancellation) or a junk datagram: either way, done.
                        break
                    }
                    handle(it, packet)
                }
            }
        } finally {
            socket = null
        }
    }

    private suspend fun handle(socket: DatagramSocket, packet: DatagramPacket) {
        val host = packet.address?.hostAddress ?: return
        val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)

        if (isPhoneProbe(payload)) {
            val reply = replyJson(clientName, clientId).toByteArray(Charsets.UTF_8)
            try {
                socket.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
            } catch (_: Exception) {
                // One unanswered probe is nothing: the PC re-broadcasts every second.
            }
            return
        }

        val invite = parseInvite(payload, host) ?: return
        if (!shouldForwardInvite(host)) return
        withContext(Dispatchers.Main) { onInvite(invite) }
    }

    /**
     * Per-source dedupe: a PC broadcasting its probe every second may well re-send an invite too,
     * and each one must not become another prompt. The UI's own "one prompt at a time" rule is the
     * other half of this.
     */
    private fun shouldForwardInvite(host: String): Boolean = synchronized(lastInviteAt) {
        val now = nowMs()
        val previous = lastInviteAt[host]
        if (previous != null && now - previous < INVITE_DEDUPE_MS) return false
        // Bound the map: a hostile LAN peer must not be able to grow it by spoofing source
        // addresses. Clearing wholesale is fine — the worst case is one duplicate prompt.
        if (lastInviteAt.size >= MAX_TRACKED_SOURCES) lastInviteAt.clear()
        lastInviteAt[host] = now
        return true
    }

    private fun openSocket(): DatagramSocket? = try {
        // Not SO_REUSEADDR: if something already holds 48630 on this device we want to know, not to
        // silently share a port and steal half of someone else's datagrams.
        DatagramSocket(null).apply {
            soTimeout = SOCKET_POLL_TIMEOUT_MS
            bind(InetSocketAddress(PcLinkDiscovery.UDP_PORT))
            bindToLocalNetwork(this)
        }
    } catch (e: Exception) {
        Log.i(TAG, "UDP ${PcLinkDiscovery.UDP_PORT} unavailable; reverse discovery is off", e)
        null
    }

    /**
     * Best-effort pin to the Wi-Fi/Ethernet network so unicast replies leave by the interface the
     * probe arrived on rather than through a VPN's default route. Replicated from
     * [PcLinkDiscovery]'s private copy (both owners keep theirs private).
     */
    private fun bindToLocalNetwork(socket: DatagramSocket) {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val network = pickLanNetwork(cm) ?: return
        try {
            network.bindSocket(socket)
        } catch (_: Exception) {
            // Already bound to a port on some devices, or the network vanished. Receiving still
            // works on the wildcard bind; only the reply's route is left to the system.
        }
    }

    @Suppress("DEPRECATION") // allNetworks: a synchronous snapshot is what a one-shot bind needs
    private fun pickLanNetwork(cm: ConnectivityManager): Network? {
        val networks = try { cm.allNetworks } catch (_: Exception) { return null }
        var ethernet: Network? = null
        for (network in networks) {
            val caps = try { cm.getNetworkCapabilities(network) } catch (_: Exception) { null } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network
            if (ethernet == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernet = network
            }
        }
        return ethernet
    }

    companion object {
        private const val TAG = "PcLinkPhoneResponder"

        /** The PC's "any phones out there?" probe (protocol.md §1.1), ASCII, 19 bytes. */
        const val PHONE_PROBE_MESSAGE = "XPL2-PHONE-PROBE v1"

        private const val MAX_PACKET_SIZE = 2048
        private const val SOCKET_POLL_TIMEOUT_MS = 400
        private const val INVITE_DEDUPE_MS = 10_000L
        private const val MAX_TRACKED_SOURCES = 64

        private val PORT_RANGE = 1..65535

        /**
         * True for the phone probe, tolerating trailing CR/LF exactly as §1 does for the server
         * probe (so an ad-hoc `netcat` probe works). Everything else — including forward
         * discovery's own `XPL2-PROBE v1`, which lands here because both share the port — is not
         * ours to answer.
         */
        internal fun isPhoneProbe(payload: String): Boolean =
            payload.trimEnd('\r', '\n') == PHONE_PROBE_MESSAGE

        /** This phone's unicast answer to a phone probe. */
        internal fun replyJson(clientName: String, clientId: String): String = JSONObject()
            .put("name", clientName)
            .put("deviceType", "client")
            .put("protocolVersion", PcLinkDiscovery.PROTOCOL_VERSION)
            .put("pairingVersion", PcLinkPairingCrypto.PAIRING_VERSION)
            .put("clientId", clientId)
            .toString()

        /**
         * Parses a `pair_invite` datagram, or null if it isn't one / isn't usable.
         *
         * [host] is the datagram's source address and always wins: the payload has no address field
         * for a reason. `serverId` is kept only as a hint for picking a stored pairing, and an
         * unusable `pairingVersion` is dropped here rather than surfacing a prompt that could only
         * end in `pair_reject {"version"}`.
         */
        internal fun parseInvite(payload: String, host: String): PcLinkPairInvite? = try {
            val json = JSONObject(payload)
            val name = json.optString("name").trim()
            val controlPort = json.optInt("controlPort", -1)
            val pairingVersion = json.optInt("pairingVersion", PcLinkPairingCrypto.PAIRING_VERSION)
            when {
                json.optString("type") != "pair_invite" -> null
                name.isEmpty() -> null
                controlPort !in PORT_RANGE -> null
                json.optInt("protocolVersion", -1) != PcLinkDiscovery.PROTOCOL_VERSION -> null
                pairingVersion != PcLinkPairingCrypto.PAIRING_VERSION -> null
                else -> PcLinkPairInvite(
                    serverName = name,
                    serverId = json.optString("serverId").trim().ifEmpty { null },
                    host = host,
                    controlPort = controlPort,
                    protocolVersion = PcLinkDiscovery.PROTOCOL_VERSION,
                    pairingVersion = pairingVersion
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
