package com.teleteh.xplayer2.ui.network

import android.content.Context
import com.teleteh.xplayer2.data.network.PcLinkDiscovery
import com.teleteh.xplayer2.data.network.PcLinkServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren

/**
 * Thin contract for PC discovery so [PcConnectActivity] doesn't call
 * `com.teleteh.xplayer2.data.network.PcLinkDiscovery` (owned by a concurrent package) directly.
 * [RealPcLinkDiscoverySource] below adapts the real thing; [NoOpPcLinkDiscovery] is a harmless
 * default for the moment between `onCreate` and the first `startDiscovery()` call.
 */
interface PcLinkDiscoverySource {
    /**
     * Runs one discovery pass; [onServer] fires on the main thread for each server found (again if
     * a known one's announcement changed). The returned [Job] completes when the pass's listen
     * window ends, so a caller can chain passes back-to-back.
     */
    fun discover(onServer: (PcLinkServer) -> Unit): Job

    /**
     * Probes a single host directly (used for manual-IP entry that isn't on the discovered list).
     * [onResult] fires exactly once on the main thread: the server, or null when it's unreachable
     * — the caller never needs a timer of its own to decide "no answer".
     */
    fun probeHost(host: String, onResult: (PcLinkServer?) -> Unit): Job

    /** Cancels any in-flight discovery/probing owned by this instance. Reusable afterwards. */
    fun stop()
}

/** Finds nothing on its own; manual IP entry in [PcConnectActivity] doesn't depend on it. */
class NoOpPcLinkDiscovery : PcLinkDiscoverySource {
    override fun discover(onServer: (PcLinkServer) -> Unit): Job = completedJob()

    override fun probeHost(host: String, onResult: (PcLinkServer?) -> Unit): Job {
        // Still answers, so a caller waiting on the single-shot contract can't hang.
        onResult(null)
        return completedJob()
    }

    override fun stop() { /* no-op */ }

    private fun completedJob(): Job = Job().apply { complete() }
}

/**
 * Adapts the real `com.teleteh.xplayer2.data.network.PcLinkDiscovery` (UDP broadcast discovery +
 * unicast host probing) to [PcLinkDiscoverySource]. That class takes a [CoroutineScope] per call
 * rather than owning one — so this wraps [parentScope] in a child [Job] whose children we can
 * cancel on [stop], without touching the parent scope (e.g. the activity's `lifecycleScope`)
 * itself. [stop] cancels the children only, leaving this instance usable for the next pass.
 */
class RealPcLinkDiscoverySource(
    context: Context,
    parentScope: CoroutineScope
) : PcLinkDiscoverySource {
    private val discovery = PcLinkDiscovery(context.applicationContext)
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    override fun discover(onServer: (PcLinkServer) -> Unit): Job =
        discovery.discover(scope, onServer = onServer)

    override fun probeHost(host: String, onResult: (PcLinkServer?) -> Unit): Job =
        discovery.probeHost(scope, host, onResult = onResult)

    override fun stop() {
        job.cancelChildren()
    }
}

/** Outcome of validating a manually-typed host, so the UI can say *why* it was rejected. */
sealed interface HostInput {
    /** A usable host: bare IPv4 or hostname, any `:port` suffix already stripped. */
    data class Valid(val host: String) : HostInput

    /** A bare IPv6 literal (`fe80::1`, `[::1]:48631`) — unsupported, and must not be truncated. */
    data object Ipv6Unsupported : HostInput

    /** Blank, a URL, junk, or a `:port` outside 1..65535. */
    data object Invalid : HostInput
}

/**
 * Pure state-holder for the discovered-server list shown by [PcConnectActivity]: dedupe, sort, and
 * manual-IP validation. No Android dependencies on purpose — this project has no Robolectric
 * dependency yet, so keeping this class plain Kotlin lets it be covered by a plain JVM unit test.
 */
class PcServerListState {
    private val byKey = LinkedHashMap<String, PcLinkServer>()

    /**
     * Adds a newly-discovered server, or updates it if already known (dedupe key: host + control
     * port — the same machine can't announce twice on the same control port). Returns true if the
     * visible snapshot would change (new entry, or an existing one changed).
     */
    fun addOrUpdate(server: PcLinkServer): Boolean {
        val key = keyOf(server)
        val prev = byKey.put(key, server)
        return prev != server
    }

    /** Forgets all discovered servers (e.g. right before a fresh discovery pass). */
    fun clear() {
        byKey.clear()
    }

    fun isEmpty(): Boolean = byKey.isEmpty()

    /** Stable, friendly display order: by name (case-insensitive), then host. */
    fun snapshot(): List<PcLinkServer> =
        byKey.values.sortedWith(compareBy({ it.name.lowercase() }, { it.host }))

    private fun keyOf(server: PcLinkServer): String = "${server.host.lowercase()}:${server.controlPort}"

    companion object {
        private val IPV4_REGEX =
            Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")

        // A conservative hostname/mDNS-name check: dot-separated labels of letters/digits/hyphens,
        // no leading/trailing hyphen per label. Good enough to reject obvious junk without trying to
        // be a full RFC-1035 validator.
        private val HOSTNAME_REGEX =
            Regex("^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

        private val PORT_RANGE = 1..65535

        /**
         * Validates + normalizes a manually-typed host: bare IPv4 or hostname, with an optional
         * `:port` suffix (the port is validated but then ignored — discovery always probes
         * [PcLinkDiscovery.UDP_PORT] and takes the real ports from the reply).
         *
         * IPv6 literals get their own outcome rather than being run through `substringBefore(":")`,
         * which used to turn `fe80::1` into a probe of the garbage host `fe80`.
         */
        fun validateHost(input: String): HostInput {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return HostInput.Invalid
            // A bare host[:port] never contains a slash or scheme — reject full URLs/paths outright
            // rather than let substringBefore(":") silently reduce "http://1.2.3.4" down to "http".
            if (trimmed.contains("/")) return HostInput.Invalid
            // "[::1]" / "[::1]:48631" and any bare literal with 2+ colons is IPv6, not host:port.
            if (trimmed.startsWith("[") || trimmed.count { it == ':' } > 1) {
                return HostInput.Ipv6Unsupported
            }

            val hostPart = trimmed.substringBefore(":").trim()
            if (hostPart.isEmpty()) return HostInput.Invalid
            if (trimmed.contains(":")) {
                val port = trimmed.substringAfter(":").trim().toIntOrNull()
                if (port == null || port !in PORT_RANGE) return HostInput.Invalid
            }
            val valid = IPV4_REGEX.matches(hostPart) || HOSTNAME_REGEX.matches(hostPart)
            return if (valid) HostInput.Valid(hostPart) else HostInput.Invalid
        }
    }
}
