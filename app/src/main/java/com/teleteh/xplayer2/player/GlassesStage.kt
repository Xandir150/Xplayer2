package com.teleteh.xplayer2.player

/**
 * The glasses are one screen, and exactly one thing may be on it.
 *
 * A film and a streamed desktop cannot share the panel, and — since PC Link grew audio — cannot
 * share the user's ears either. Starting either one has to end the other, in both directions.
 *
 * The reason this is a process-wide registry rather than a check inside [PlayerActivity] is that
 * there can be more than one of those. `PlayerActivity` is `singleTop`, which only routes an intent
 * into the running instance when it is the top activity of the task — and with the glasses attached
 * it usually is not (the phone-side remote sits in front of it, and the media library is reached by
 * `REORDER_TO_FRONT` rather than by finishing anything). So a film started from the library while a
 * desktop is streaming lands in a *second* activity, whose `onStop` deliberately keeps the older one
 * alive because its picture is on the external panel. Both then run: two pictures for one panel, two
 * sound sources for one pair of ears. That is the bug this exists to make impossible.
 *
 * The eviction is silent by design. Picking a film is not an ambiguous act, and a modal asking the
 * user to confirm what they just did is friction with no decision in it. It is not invisible,
 * though: the caller says what happened (see [Handover]), and the evicted screen really does go
 * away rather than sitting there still looking connected.
 *
 * Not covered, deliberately: the PC Link pairing ceremony. It has something to lose — six digits
 * compared on two devices — and it lives in `PcConnectActivity`, which occupies nothing here.
 */
object GlassesStage {

    /** What an occupant is putting on the glasses. */
    enum class Use { NOTHING, LOCAL_VIDEO, PC_LINK }

    /** What a claim had to take off the panel first. */
    data class Handover(val endedPcLink: Boolean = false, val endedLocalVideo: Boolean = false) {
        val endedAnything: Boolean get() = endedPcLink || endedLocalVideo
    }

    /**
     * Anything that can occupy the glasses. [PlayerActivity] is the only real implementation; the
     * interface exists so the arbitration above can be exercised without an Android runtime.
     */
    interface Occupant {
        /** What this is showing *right now*. */
        val glassesUse: Use

        /**
         * End it and get out of the way: stop the playback or the PC Link session, drop the
         * external presentation, finish. Called on the main thread, and allowed to unregister
         * itself while it runs — [claim] iterates over a snapshot precisely because `finish()`
         * eventually leads back here.
         */
        fun releaseGlasses()
    }

    private val occupants = ArrayList<Occupant>()

    /** Main thread only, like every Android lifecycle callback that drives it. */
    fun register(occupant: Occupant) {
        if (occupants.none { it === occupant }) occupants.add(occupant)
    }

    fun unregister(occupant: Occupant) {
        occupants.removeAll { it === occupant }
    }

    /** Live occupants, newest last. Test seam and diagnostics — never mutate through it. */
    val current: List<Occupant> get() = occupants.toList()

    /**
     * [claimant] is taking the glasses. Everything else showing anything comes off first.
     *
     * Any other occupant is evicted, not only one in a different mode: two films are as wrong as a
     * film and a desktop, and a second `PlayerActivity` playing its own file is the same defect
     * wearing a different hat. Returns what was ended, so the caller can tell the user which of
     * their things went away.
     */
    fun claim(claimant: Occupant?): Handover {
        var endedPcLink = false
        var endedLocalVideo = false
        // A snapshot: releasing an occupant finishes an activity, which unregisters it.
        for (occupant in occupants.toList()) {
            if (occupant === claimant) continue
            when (occupant.glassesUse) {
                Use.NOTHING -> continue
                Use.PC_LINK -> endedPcLink = true
                Use.LOCAL_VIDEO -> endedLocalVideo = true
            }
            occupant.releaseGlasses()
        }
        return Handover(endedPcLink = endedPcLink, endedLocalVideo = endedLocalVideo)
    }
}
