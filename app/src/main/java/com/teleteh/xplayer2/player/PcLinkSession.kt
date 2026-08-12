package com.teleteh.xplayer2.player

/**
 * How the running PC Link session is doing, for everyone who is not the player.
 *
 * The session itself lives in [PlayerActivity] — the sockets, the codec, the audio track — and that
 * is where it belongs: it is the thing putting a picture on the glasses. But the *phone* is a remote
 * control in this app, and the screen the user watches the numbers on (the PC-Mirror tab, in
 * `MainActivity`) is a different activity in a different task position, often with the player
 * stopped behind it. There is no lifecycle relationship between the two, so there is no
 * `ViewModel`, no binder, no shared owner to hang this on.
 *
 * Hence a process-wide registry, exactly like [GlassesStage] next door and for the same reason: the
 * player is findable by nothing else. It holds no state of its own — the numbers are read straight
 * out of whoever is streaming, on demand — so there is nothing here to go stale, and nothing to keep
 * publishing into while the tab is closed.
 *
 * **Pull, not push.** Readers ask on their own clock (see `PcLinkStatsHistory`). A callback would
 * fire when the numbers *change*, and a desktop that nobody is touching sends no frames for minutes
 * at a time — which is precisely when "0 fps" is the answer the user came to read.
 */
object PcLinkSession {

    /** Where the link is in its own lifecycle, so a reader can say more than a number. */
    enum class Link { CONNECTING, STREAMING, RECONNECTING, FAILED }

    /**
     * One reading of a live session.
     *
     * The throughput fields are **cumulative counters**, not rates: a rate needs two readings and an
     * interval, and the interval belongs to whoever is sampling (the player's own debug overlay and
     * the tab sample at different periods). Counters restart at zero when a session reconnects, so a
     * reader that differences them must floor the result at zero rather than draw a negative dip.
     */
    data class Stats(
        /**
         * Which session these numbers belong to. Monotonic per process: a reader that keeps history
         * across readings uses a change here to know that what it has collected describes a
         * *different* session and must be thrown away — same PC and same address included.
         */
        val sessionId: Long,
        /** The PC's name as the pairing exchange proved it (never as discovery claimed it). */
        val serverName: String,
        val link: Link,
        /** Frames the decoder has actually rendered, cumulative. */
        val framesRendered: Long,
        /** Bytes read off the video socket, cumulative. */
        val videoBytes: Long,
        /** Frames thrown away to catch up, cumulative. */
        val droppedFrames: Long,
        /**
         * Round trip to the PC on the control connection; null until a pong has actually come back,
         * and again whenever there is no client to ask (parked, refused, failed). Never faked as a
         * zero — the clock behind it is millisecond-granular, so a real 0 is a same-millisecond LAN
         * round trip and has to stay tellable from "no reading yet".
         */
        val rttMs: Float?,
        /** Codec short name (`avc`, `hevc`), or null before the server's `config` arrives. */
        val codec: String?,
        val width: Int,
        val height: Int,
        /** `sbs` or `mono` — how the server packs the stream. */
        val stereo: String?,
        /** Sample rate of the PC's sound as we are playing it, or 0 when no sound is arriving. */
        val audioRateHz: Int,
        val audioChannels: Int,
        val audioBufferedMs: Int,
        /** Times the audio ran dry: ours (the stream) plus the platform's (the track). */
        val audioDropouts: Long,
        /** (video pts − audio pts) on the server's clock; null until both are known. */
        val audioSkewMs: Long?,
        /** True while the PC's sound is being played here rather than on the PC itself. */
        val audioToGlasses: Boolean,
        /** True when there is sound to route at all — an older server sends none. */
        val audioAvailable: Boolean
    )

    /**
     * Whoever is running a session. [PlayerActivity] is the only real implementation; the interface
     * exists so this can be exercised — and the tab's history driven — without an Android runtime.
     */
    interface Host {
        /** This host's session right now, or null when it isn't running one. */
        fun pcLinkStats(): Stats?

        /**
         * Put the PC's sound in the glasses, or hand it back to the PC's own speakers. The wire
         * message that releases the desktop's speakers is the host's business; this only says which
         * way the user wants it.
         */
        fun setPcLinkAudioToGlasses(enabled: Boolean)

        /** End the session and take the desktop off the glasses. */
        fun endPcLink()

        /**
         * Re-anchor the world-fixed desktop dead ahead of wherever the user is looking now. The
         * yaw this is fighting is gyro-integrated and drifts over a long session, so this is a
         * routine gesture rather than an error recovery — which is why the remote offers it as a
         * button *and* as a long-press anywhere on its surface.
         */
        fun recenterPcLink()
    }

    private val hosts = ArrayList<Host>()

    /** Main thread only, like the lifecycle callbacks that drive it. */
    fun register(host: Host) {
        if (hosts.none { it === host }) hosts.add(host)
    }

    fun unregister(host: Host) {
        hosts.removeAll { it === host }
    }

    /**
     * The live session, or null when nothing is streaming.
     *
     * There can be more than one registered host — `PlayerActivity` is `singleTop` and a second
     * instance is routine (see [GlassesStage]) — but at most one of them is in PC Link mode, because
     * claiming the glasses evicts the rest. Newest first, so if that invariant ever slips the answer
     * is the session that started last rather than a corpse.
     */
    fun stats(): Stats? = liveHost()?.second

    /** Applies the routing choice to the live session; no-op when there isn't one. */
    fun setAudioToGlasses(enabled: Boolean) {
        liveHost()?.first?.setPcLinkAudioToGlasses(enabled)
    }

    /** Ends the live session; no-op when there isn't one. */
    fun end() {
        liveHost()?.first?.endPcLink()
    }

    /** Re-centres the live session's desktop; no-op when there isn't one. */
    fun recenter() {
        liveHost()?.first?.recenterPcLink()
    }

    private fun liveHost(): Pair<Host, Stats>? {
        for (i in hosts.indices.reversed()) {
            val host = hosts[i]
            val stats = host.pcLinkStats() ?: continue
            return host to stats
        }
        return null
    }

    private var nextSessionId = 0L

    /** A fresh id for a session that is starting now. Main thread only. */
    fun newSessionId(): Long = ++nextSessionId
}
