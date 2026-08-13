package com.teleteh.xplayer2.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Runs one [PairingSession] over its own short-lived control connection.
 *
 * Deliberately a separate, minimal client rather than a mode of [PcLinkClient]: the pairing
 * ceremony is a single-shot exchange with a human in the middle of it, and it has none of the
 * streaming client's concerns (no video socket, no reconnect/backoff, no ping loop, no codec
 * negotiation to speak of). Connect, say `hello`, run the handshake, report, close.
 *
 * The connection really is thrown away afterwards, including on success. A `videoToken` is bound to
 * the control session that issued it and dies with it (protocol.md §2.13), so the token this
 * session sees can't be handed to a player that will open its own connection — [PcLinkClient] gets
 * a fresh one when it authenticates with the LTK we just stored. What survives this class is the
 * pairing.
 *
 * Threading: everything network-facing runs on [Dispatchers.IO] inside the job returned by [start];
 * every [Listener] callback is dispatched on the main thread. [accept], [decline] and [cancel] are
 * safe to call from the UI thread at any time — they queue an event the session loop picks up.
 */
class PcLinkPairingClient(
    context: Context?,
    private val host: String,
    private val controlPort: Int,
    private val session: PairingSession,
    private val listener: Listener,
    private val clientName: String = defaultClientName(),
    private val codecs: List<PcCodecCapability> = PcLinkClient.deviceCodecs()
) {

    interface Listener {
        /**
         * The 6-digit code is ready. Show it with [serverName] — the name the server bound into the
         * transcript, not the one discovery advertised — and call [accept] or [decline].
         */
        fun onSasReady(sas: String, serverName: String, serverId: String)

        /**
         * Write to the pairing store now, synchronously.
         *
         * Two shapes, told apart by [PairingEffect.Persist.fresh]: a completed ceremony (the PC has
         * already persisted, so from here on both sides are paired) or, on a reconnect, nothing but
         * the §2.18.7 encryption pin the session just earned.
         */
        fun onPersist(persist: PairingEffect.Persist)

        /** Terminal. Exactly one call per [start]. */
        fun onFinished(outcome: PairingOutcome)
    }

    private enum class UserEvent { ACCEPT, DECLINE }

    private val pendingUserEvents = ConcurrentLinkedQueue<UserEvent>()

    /**
     * The control connection's §2.18 dress code. Plaintext until the session says otherwise with a
     * [PairingEffect.EngageEncryption]; from then on it seals everything this client sends and
     * refuses anything that arrives out of its envelope.
     */
    private val link = PcLinkSessionLink(PeerRole.CLIENT)

    /** Held so [cancel] can break a blocked read instead of waiting out its timeout. */
    @Volatile private var socket: Socket? = null

    @Volatile private var job: Job? = null

    private val appContext: Context? = context?.applicationContext

    /** Starts the ceremony. Call once; the returned [Job] completes after [Listener.onFinished]. */
    fun start(scope: CoroutineScope): Job {
        val started = scope.launch(Dispatchers.IO) { run() }
        job = started
        return started
    }

    /** The user tapped "Pair" on the code sheet. */
    fun accept() {
        pendingUserEvents.add(UserEvent.ACCEPT)
    }

    /** The user tapped "Cancel". */
    fun decline() {
        pendingUserEvents.add(UserEvent.DECLINE)
    }

    /**
     * Abandons the ceremony without telling the peer (screen closed, activity destroyed). Prefer
     * [decline] while a dialog is up: it sends `pair_reject`, so the PC's dialog goes away too
     * instead of sitting there until its 90 s timeout.
     */
    fun cancel() {
        job?.cancel()
        closeSocket()
    }

    private suspend fun run() {
        var outcome: PairingOutcome? = null
        try {
            val connected = openControlSocket()
            if (connected == null) {
                report(PairingOutcome.Failure(PairingFailure.CONNECTION_LOST))
                return
            }
            socket = connected
            outcome = connected.use { runSession(it) }
        } catch (e: IOException) {
            Log.w(TAG, "Pairing connection to $host:$controlPort failed", e)
        } finally {
            closeSocket()
            // A cancelled job means the screen went away; nobody is left to hear the result.
            if (currentCoroutineContext().isActive) {
                report(outcome ?: PairingOutcome.Failure(PairingFailure.CONNECTION_LOST))
            }
        }
    }

    private suspend fun runSession(socket: Socket): PairingOutcome {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        val splitter = PcLinkLineSplitter()
        val buffer = ByteArray(READ_BUFFER)
        var finished: PairingOutcome? = null

        // `hello` must precede everything, and its protocolVersion is inside the pairing transcript
        // — so it has to be the same number the session was built with. It is also always plaintext
        // (§2.18.4), which is what `link` answers with until the ceremony engages the envelope.
        writeLine(output, PcLinkProtocol.helloLine(clientName, codecs))

        finished = apply(session.start(), output) ?: finished

        while (finished == null && currentCoroutineContext().isActive) {
            drainUserEvents(output)?.let { finished = it; break }
            session.onTick().let { effects -> apply(effects, output)?.let { finished = it } }
            if (finished != null) break

            val read = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                // Just the poll interval expiring: loop round to re-check the clock and the queue.
                continue
            } catch (_: IOException) {
                -1
            }
            if (read < 0) {
                finished = apply(session.onDisconnected(), output)
                    ?: PairingOutcome.Failure(PairingFailure.CONNECTION_LOST)
                break
            }
            splitter.feed(buffer, 0, read)
            while (true) {
                val line = splitter.nextLine() ?: break
                // A stray newline is not a message. Dropped while the channel is plaintext, exactly
                // as v1 dropped it; once the envelope is engaged §2.18.6 has no such tolerance and
                // `accept` refuses it like any other line in the wrong dress.
                if (!link.isEncrypted && line.isBlank()) continue
                val inner = try {
                    link.accept(line)
                } catch (e: PcLinkLinkException) {
                    // §2.18.6: after a failed envelope there is no next line. Not a dropped
                    // connection and not something to resynchronize past — the session is over.
                    Log.w(TAG, "Ending the pairing session: ${e.failure}")
                    finished = PairingOutcome.Failure(PairingFailure.PROTOCOL)
                    break
                }
                finished = apply(session.onLine(inner), output)
                if (finished != null) break
            }
        }
        return finished ?: PairingOutcome.Failure(PairingFailure.CONNECTION_LOST)
    }

    private suspend fun drainUserEvents(output: OutputStream): PairingOutcome? {
        while (true) {
            val event = pendingUserEvents.poll() ?: return null
            val effects = when (event) {
                UserEvent.ACCEPT -> session.onUserAccept()
                UserEvent.DECLINE -> session.onUserDecline()
            }
            apply(effects, output)?.let { return it }
        }
    }

    /** Performs one batch of effects, in order. Returns the outcome once the session is done. */
    private suspend fun apply(effects: List<PairingEffect>, output: OutputStream): PairingOutcome? {
        var outcome: PairingOutcome? = null
        for (effect in effects) {
            when (effect) {
                is PairingEffect.Send -> writeLine(output, effect.line)

                is PairingEffect.ShowSas -> withContext(Dispatchers.Main) {
                    listener.onSasReady(effect.sas, effect.serverName, effect.serverId)
                }

                is PairingEffect.Persist -> withContext(Dispatchers.Main) {
                    listener.onPersist(effect)
                }

                // Ordering is the contract, and it is why this is applied in sequence rather than
                // sorted: everything before this effect in the batch went out plaintext, everything
                // after it is sealed (§2.18.4).
                is PairingEffect.EngageEncryption -> link.engage(effect.keys)

                is PairingEffect.Finished -> outcome = effect.outcome

                // Every failure costs the connection: that is what makes one code attempt per
                // handshake true. The socket is closed by run()'s use{} either way.
                PairingEffect.Close -> closeSocket()
            }
        }
        return outcome
    }

    /**
     * Writes one line, sealed if the session has engaged the §2.18.4 envelope and plain if it has
     * not. Newline framing belongs here either way.
     */
    private fun writeLine(output: OutputStream, line: String) {
        output.write(link.seal(line).toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun openControlSocket(): Socket? {
        val socket = Socket()
        return try {
            bindToLocalNetwork(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, controlPort), CONNECT_TIMEOUT_MS)
            // Short poll timeout, not a deadline: the session's own timers decide when to give up,
            // and a blocked read would otherwise ignore both the clock and the user's Cancel.
            socket.soTimeout = READ_POLL_TIMEOUT_MS
            socket
        } catch (e: Exception) {
            Log.w(TAG, "Can't reach $host:$controlPort for pairing", e)
            try { socket.close() } catch (_: Exception) { }
            null
        }
    }

    private fun closeSocket() {
        val open = socket ?: return
        socket = null
        try { open.close() } catch (_: Exception) { }
    }

    private suspend fun report(outcome: PairingOutcome) = withContext(Dispatchers.Main) {
        listener.onFinished(outcome)
    }

    /**
     * Best-effort pin to the Wi-Fi/Ethernet network — same reasoning as `PcLinkDiscovery`'s copy of
     * this: with an always-on VPN, or Wi-Fi flagged "no internet", the default network isn't the
     * LAN the PC is on and the connection quietly goes out the wrong interface. Duplicated rather
     * than shared because both owners are private to their class.
     */
    private fun bindToLocalNetwork(socket: Socket) {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val network = pickLanNetwork(cm) ?: return
        try {
            network.bindSocket(socket)
        } catch (_: Exception) {
            // Network vanished between the snapshot and the bind; the unbound socket still works on
            // an ordinary single-network phone.
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
        private const val TAG = "PcLinkPairingClient"

        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_POLL_TIMEOUT_MS = 250
        private const val READ_BUFFER = 8 * 1024

        /**
         * What this phone calls itself on the PC's pairing dialog. Bound into the transcript, so
         * it's also part of what the 6-digit code authenticates.
         */
        fun defaultClientName(): String {
            val model = Build.MODEL?.trim().orEmpty()
            val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
            return when {
                model.isEmpty() && manufacturer.isEmpty() -> "XPlayer2"
                model.isEmpty() -> manufacturer
                // "Pixel 9 Pro", not "Google Pixel 9 Pro" — MODEL usually already carries the brand.
                manufacturer.isEmpty() || model.startsWith(manufacturer, ignoreCase = true) -> model
                else -> "$manufacturer $model"
            }
        }
    }
}
