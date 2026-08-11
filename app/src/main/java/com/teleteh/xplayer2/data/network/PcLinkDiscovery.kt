package com.teleteh.xplayer2.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 * short window, and reports each distinct server exactly once via a callback dispatched on
 * [Dispatchers.Main]. Also supports probing a single, manually-entered host/IP.
 */
class PcLinkDiscovery {

    /**
     * Broadcasts [PROBE_MESSAGE] a few times over [listenDurationMs] and reports every distinct
     * server that replies. Safe to call repeatedly; each call uses its own socket.
     */
    fun discover(
        scope: CoroutineScope,
        listenDurationMs: Long = 4000L,
        onServer: (PcLinkServer) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val socket = try {
                DatagramSocket().apply {
                    soTimeout = SOCKET_POLL_TIMEOUT_MS
                    broadcast = true
                }
            } catch (_: Exception) {
                return@launch
            }
            try {
                val targets = broadcastTargets()
                if (targets.isEmpty()) return@launch

                val seen = HashSet<String>()
                val buf = ByteArray(MAX_PACKET_SIZE)
                val start = System.currentTimeMillis()
                var nextSendAt = 0L

                while (System.currentTimeMillis() - start < listenDurationMs) {
                    if (System.currentTimeMillis() >= nextSendAt) {
                        sendProbe(socket, targets)
                        nextSendAt = System.currentTimeMillis() + RETRY_INTERVAL_MS
                    }
                    receiveOnce(socket, buf)?.let { (server, senderKey) ->
                        if (seen.add(senderKey)) {
                            withContext(Dispatchers.Main) { onServer(server) }
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }
    }

    /**
     * Sends a single unicast probe to a manually-entered [host] (typed in by the user rather than
     * discovered) and reports the server if it replies within [timeoutMs].
     */
    fun probeHost(
        scope: CoroutineScope,
        host: String,
        timeoutMs: Long = 2000L,
        onServer: (PcLinkServer) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val socket = try {
                DatagramSocket().apply { soTimeout = timeoutMs.toInt().coerceAtLeast(1) }
            } catch (_: Exception) {
                return@launch
            }
            try {
                val addr = try { InetAddress.getByName(host) } catch (_: Exception) { return@launch }
                val probeBytes = PROBE_MESSAGE.toByteArray(Charsets.US_ASCII)
                socket.send(DatagramPacket(probeBytes, probeBytes.size, addr, UDP_PORT))

                val buf = ByteArray(MAX_PACKET_SIZE)
                val result = receiveOnce(socket, buf)
                if (result != null) {
                    withContext(Dispatchers.Main) { onServer(result.first) }
                }
            } catch (_: Exception) {
                // timeout / unreachable / malformed reply - just report nothing
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }
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
        } catch (_: Exception) {
            null
        }
    }

    /** All broadcast addresses worth probing: the universal one plus every local subnet's. */
    private fun broadcastTargets(): List<InetAddress> {
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

    companion object {
        const val UDP_PORT = 48630
        const val PROBE_MESSAGE = "XPL2-PROBE v1"

        private const val MAX_PACKET_SIZE = 2048
        private const val RETRY_INTERVAL_MS = 900L
        private const val SOCKET_POLL_TIMEOUT_MS = 400
        private const val DEFAULT_SERVER_NAME = "XPlayer Link"

        /**
         * Parses a server's probe-reply JSON, e.g.
         * `{"name": "Alex-PC", "protocolVersion": 1, "controlPort": 48631, "videoPort": 48632}`.
         * Returns null for malformed JSON or missing/invalid required fields. Extracted as a pure
         * function so it's directly unit-testable without a socket.
         */
        internal fun parseServerResponse(json: String, host: String): PcLinkServer? {
            return try {
                val obj = JSONObject(json)
                val protocolVersion = obj.optInt("protocolVersion", -1)
                val controlPort = obj.optInt("controlPort", -1)
                val videoPort = obj.optInt("videoPort", -1)
                if (protocolVersion <= 0 || controlPort <= 0 || videoPort <= 0) return null

                val name = obj.optString("name").ifBlank { DEFAULT_SERVER_NAME }
                PcLinkServer(
                    name = name,
                    host = host,
                    controlPort = controlPort,
                    videoPort = videoPort,
                    protocolVersion = protocolVersion
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
