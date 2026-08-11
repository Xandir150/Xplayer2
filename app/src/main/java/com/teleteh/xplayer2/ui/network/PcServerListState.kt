package com.teleteh.xplayer2.ui.network

/**
 * A PC-side "XPlayer Link" server, as announced by discovery or entered manually.
 *
 * This mirrors the shape of `com.teleteh.xplayer2.data.network.PcLinkDiscovery`'s
 * `PcLinkServer` (owned by a concurrent package — LAN discovery/mDNS). Kept as our own copy here
 * so this UI package compiles standalone; once discovery lands, the two should be unified (either
 * by having that package's type implement/replace this one, or by mapping between them at the call
 * site) — not done here to keep this package's boundary strict.
 */
data class PcLinkServer(
    val name: String,
    val host: String,
    val controlPort: Int,
    val videoPort: Int,
    val protocolVersion: Int,
)

/**
 * Thin contract for PC discovery so [PcConnectActivity] compiles and works (manual IP entry, list
 * state) independent of when `data.network.PcLinkDiscovery` lands. Expected real shape (per the
 * PC Link project plan):
 * ```
 * class PcLinkDiscovery(scope/context) {
 *     fun discover(onServer: (PcLinkServer) -> Unit)
 *     fun probeHost(host: String, onServer: (PcLinkServer) -> Unit)
 *     fun stop()
 * }
 * ```
 * A small adapter can wrap the real class behind this interface once it exists.
 */
interface PcLinkDiscoverySource {
    /** Starts (or restarts) LAN discovery; [onServer] fires once per server found. */
    fun discover(onServer: (PcLinkServer) -> Unit)

    /** Probes a single host directly (used for manual-IP entry that isn't on the discovered list). */
    fun probeHost(host: String, onServer: (PcLinkServer) -> Unit)

    /** Stops any in-flight discovery/probing. */
    fun stop()
}

/**
 * Placeholder discovery source: finds nothing on its own. Used until the real
 * `data.network.PcLinkDiscovery` (agent A2) is wired in — manual IP entry in [PcConnectActivity]
 * doesn't depend on it, so the screen is still fully usable in the meantime.
 */
class NoOpPcLinkDiscovery : PcLinkDiscoverySource {
    override fun discover(onServer: (PcLinkServer) -> Unit) { /* no-op */ }
    override fun probeHost(host: String, onServer: (PcLinkServer) -> Unit) { /* no-op */ }
    override fun stop() { /* no-op */ }
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

        /**
         * Validates + normalizes a manually-typed host: bare IPv4 or hostname, with an optional
         * `:port` suffix (the port is ignored here — [PcConnectActivity] uses its own default
         * control/video ports for manual entries). Returns the bare host, or null if invalid/blank.
         */
        fun validateHost(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            // A bare host[:port] never contains a slash or scheme — reject full URLs/paths outright
            // rather than let substringBefore(":") silently reduce "http://1.2.3.4" down to "http".
            if (trimmed.contains("/")) return null
            val hostPart = trimmed.substringBefore(":").trim()
            if (hostPart.isEmpty()) return null
            return if (IPV4_REGEX.matches(hostPart) || HOSTNAME_REGEX.matches(hostPart)) hostPart else null
        }
    }
}
