package com.teleteh.xplayer2.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.Charset

/**
 * A PC Link server discovered on the LAN (or reached directly by IP).
 */
data class PcLinkServer(
    val name: String,
    val host: String,
    val controlPort: Int,
    val videoPort: Int,
    val protocolVersion: Int
)

/**
 * LAN discovery for XPlayer "PC Link" desktop-streaming servers.
 *
 * Modeled on [DlnaDiscovery]: broadcasts a UDP probe, listens for unicast JSON replies over a
 * short window, and reports servers via a callback dispatched on [Dispatchers.Main]. Also supports
 * probing a single, manually-entered host/IP.
 *
 * [context] is only used to pin the sockets to the Wi-Fi/Ethernet network (see
 * [bindToLocalNetwork]); pass null to skip that (unit tests). [nowMs] and [broadcastTargets] are
 * seams so the listen loop can be exercised on the JVM without `android.os.SystemClock` or real
 * LAN traffic.
 */
class PcLinkDiscovery(
    context: Context?,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val broadcastTargets: () -> List<InetAddress> = { defaultBroadcastTargets() }
) {

    private val appContext: Context? = context?.applicationContext

    /**
     * Broadcasts [PROBE_MESSAGE] every [RETRY_INTERVAL_MS] over [listenDurationMs] and reports each
     * server that replies (again if a known host's announcement changed). Safe to call repeatedly;
     * each call uses its own socket. Cancelling the returned [Job] stops the pass and closes the
     * socket within one [SOCKET_POLL_TIMEOUT_MS] poll; the [Job] completes when the window ends.
     */
    fun discover(
        scope: CoroutineScope,
        listenDurationMs: Long = DEFAULT_LISTEN_DURATION_MS,
        onServer: (PcLinkServer) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        val targets = broadcastTargets()
        if (targets.isEmpty()) return@launch
        val socket = openSocket(broadcast = true, timeoutMs = SOCKET_POLL_TIMEOUT_MS.toLong())
            ?: return@launch
        // use{} closes the socket on every exit path, cancellation included.
        socket.use { runDiscoveryPass(it, targets, listenDurationMs, onServer) }
    }

    /**
     * Sends a single unicast probe to a manually-entered [host] (typed in by the user rather than
     * discovered). [onResult] fires exactly once on [Dispatchers.Main] — with the server, or null
     * if the host didn't answer within [timeoutMs], isn't resolvable, or replied with junk — so the
     * caller doesn't need a wall-clock timer of its own racing this one. The single exception is
     * the caller cancelling the returned [Job], which means it no longer wants the answer.
     */
    fun probeHost(
        scope: CoroutineScope,
        host: String,
        timeoutMs: Long = DEFAULT_PROBE_TIMEOUT_MS,
        onResult: (PcLinkServer?) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        // The blocking work runs in a child coroutine so the timeout can fire while it's stuck in a
        // syscall: InetAddress.getByName() can sit on DNS for 10-30 s — long before the socket's own
        // soTimeout is even in play — and a withTimeout() wrapped straight around it could not
        // interrupt it. Racing an awaitable child does bound the time until we report.
        val work = async(Dispatchers.IO) { runCatching { blockingProbe(host, timeoutMs) }.getOrNull() }
        val server = try {
            withTimeout(timeoutMs) { work.await() }
        } catch (_: TimeoutCancellationException) {
            // Abandoned, not awaited: a thread stuck in DNS finishes on its own and its socket is
            // closed by blockingProbe's use{}. The reply, if any, is dropped.
            work.cancel()
            null
        }
        withContext(Dispatchers.Main) { onResult(server) }
    }

    /** The broadcast/listen loop. Bails out promptly once the coroutine is cancelled. */
    private suspend fun runDiscoveryPass(
        socket: DatagramSocket,
        targets: List<InetAddress>,
        listenDurationMs: Long,
        onServer: (PcLinkServer) -> Unit
    ) {
        val seen = PassDedupe()
        val buf = ByteArray(MAX_PACKET_SIZE)
        // elapsedRealtime, not currentTimeMillis: an NTP step mid-window would otherwise stretch
        // the pass to minutes or end it instantly.
        val deadline = nowMs() + listenDurationMs
        var nextSendAt = Long.MIN_VALUE
        while (currentCoroutineContext().isActive && nowMs() < deadline) {
            if (nowMs() >= nextSendAt) {
                sendProbe(socket, targets)
                nextSendAt = nowMs() + RETRY_INTERVAL_MS
            }
            // Re-check after the (blocking) send: no point sitting in receive() for a cancelled pass.
            if (!currentCoroutineContext().isActive) break
            val (server, senderKey) = receiveOnce(socket, buf) ?: continue
            if (seen.shouldEmit(senderKey, server)) {
                // Suspends, so cancellation surfaces here as CancellationException — deliberately
                // not caught anywhere on this path.
                withContext(Dispatchers.Main) { onServer(server) }
            }
        }
    }

    /** One unicast probe + reply, all blocking. Never throws; returns null when the host is silent. */
    private fun blockingProbe(host: String, timeoutMs: Long): PcLinkServer? {
        val addr = try { InetAddress.getByName(host) } catch (_: Exception) { return null }
        val socket = openSocket(broadcast = false, timeoutMs = timeoutMs) ?: return null
        return socket.use {
            val probeBytes = PROBE_MESSAGE.toByteArray(Charsets.US_ASCII)
            try {
                it.send(DatagramPacket(probeBytes, probeBytes.size, addr, UDP_PORT))
            } catch (_: IOException) {
                return@use null
            }
            receiveOnce(it, ByteArray(MAX_PACKET_SIZE))?.first
        }
    }

    private fun openSocket(broadcast: Boolean, timeoutMs: Long): DatagramSocket? {
        val socket = try { DatagramSocket() } catch (_: Exception) { return null }
        return try {
            socket.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
            socket.broadcast = broadcast
            bindToLocalNetwork(socket)
            socket
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) { }
            null
        }
    }

    /**
     * Best-effort: pin [socket] to the Wi-Fi (or Ethernet) network.
     *
     * An unbound socket goes out over the system's *default* network — which, with an always-on VPN
     * or with the Wi-Fi flagged "no internet" (the classic cinema rig: travel router with no WAN,
     * phone on LTE), is not the LAN the PC is on. Probes then leave through the wrong interface and
     * discovery quietly finds nothing. Note we deliberately do NOT require NET_CAPABILITY_VALIDATED
     * here — an unvalidated LAN is exactly the case being rescued. Falls back to the unbound socket
     * when there's no such network (or no Context).
     */
    private fun bindToLocalNetwork(socket: DatagramSocket) {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val network = pickLanNetwork(cm) ?: return
        try {
            network.bindSocket(socket)
        } catch (_: Exception) {
            // Network vanished between the snapshot and the bind — the unbound socket still works
            // on a normal single-network phone.
        }
    }

    @Suppress("DEPRECATION") // allNetworks: a synchronous snapshot is exactly what a one-shot bind needs
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

    private fun sendProbe(socket: DatagramSocket, targets: List<InetAddress>) {
        val probeBytes = PROBE_MESSAGE.toByteArray(Charsets.US_ASCII)
        for (addr in targets) {
            try {
                socket.send(DatagramPacket(probeBytes, probeBytes.size, addr, UDP_PORT))
            } catch (_: Exception) {
                // one bad interface/address shouldn't stop the others
            }
        }
    }

    /** Blocks for at most the socket's `soTimeout`; returns the parsed server + a de-dupe key. */
    private fun receiveOnce(socket: DatagramSocket, buf: ByteArray): Pair<PcLinkServer, String>? {
        return try {
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val text = String(resp.data, 0, resp.length, Charset.forName("UTF-8"))
            val host = resp.address?.hostAddress ?: return null
            val server = parseServerResponse(text, host) ?: return null
            Pair(server, host)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            // closed socket (cancellation), ICMP port-unreachable, oversized datagram…
            null
        }
    }

    /**
     * Per-pass dedupe. A server that answers every 900 ms probe must produce one callback, not
     * five — but keying on the sender IP alone (and never revisiting it) also hid a server that
     * restarted with a different name or ports, leaving a stale row on screen. So: remember the
     * payload, and emit again whenever it changed.
     */
    internal class PassDedupe {
        private val seen = HashMap<String, PcLinkServer>()

        fun shouldEmit(senderKey: String, server: PcLinkServer): Boolean =
            seen.put(senderKey, server) != server
    }

    companion object {
        const val UDP_PORT = 48630
        const val PROBE_MESSAGE = "XPL2-PROBE v1"

        /** The only wire protocol this client speaks (`xpl_proto::PROTOCOL_VERSION`). */
        const val PROTOCOL_VERSION = 1

        const val DEFAULT_LISTEN_DURATION_MS = 4000L
        const val DEFAULT_PROBE_TIMEOUT_MS = 2000L

        private const val MAX_PACKET_SIZE = 2048
        private const val RETRY_INTERVAL_MS = 900L
        private const val SOCKET_POLL_TIMEOUT_MS = 400
        private val PORT_RANGE = 1..65535

        /** All broadcast addresses worth probing: the universal one plus every local subnet's. */
        private fun defaultBroadcastTargets(): List<InetAddress> {
            val targets = LinkedHashSet<InetAddress>()
            try {
                targets.add(InetAddress.getByName("255.255.255.255"))
            } catch (_: Exception) {
                // ignore
            }
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    for (ifaceAddr in iface.interfaceAddresses) {
                        val broadcast = ifaceAddr.broadcast
                        if (broadcast is Inet4Address) targets.add(broadcast)
                    }
                }
            } catch (_: SocketException) {
                // ignore, fall back to whatever we already collected
            }
            return targets.toList()
        }

        /**
         * Parses a server's probe-reply JSON, e.g.
         * `{"name": "Alex-PC", "protocolVersion": 1, "controlPort": 48631, "videoPort": 48632}`.
         *
         * Validation is kept in lockstep with the reference decoder (Rust `xpl_proto`'s
         * `DiscoveryReply`): `name` is a required non-empty String there (no default — so no
         * "XPlayer Link" fallback here either), both ports are `u16`, and this client only speaks
         * [PROTOCOL_VERSION]. Unknown fields are ignored, like the reference. Returns null for
         * malformed JSON or missing/invalid required fields. Extracted as a pure function so it's
         * directly unit-testable without a socket.
         */
        internal fun parseServerResponse(json: String, host: String): PcLinkServer? {
            return try {
                val obj = JSONObject(json)
                val name = obj.optString("name").trim()
                if (name.isEmpty()) return null
                if (obj.optInt("protocolVersion", -1) != PROTOCOL_VERSION) return null
                val controlPort = obj.optInt("controlPort", -1)
                val videoPort = obj.optInt("videoPort", -1)
                // Bound to real port numbers: an out-of-range controlPort would otherwise ride the
                // intent extras all the way to a Socket() that throws.
                if (controlPort !in PORT_RANGE || videoPort !in PORT_RANGE) return null

                PcLinkServer(
                    name = name,
                    host = host,
                    controlPort = controlPort,
                    videoPort = videoPort,
                    protocolVersion = PROTOCOL_VERSION
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
