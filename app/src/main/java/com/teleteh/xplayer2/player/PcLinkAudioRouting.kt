package com.teleteh.xplayer2.player

/**
 * Whether the PC has sound for us — the one fact both audio controls are drawn from.
 *
 * Pulled out of `PlayerActivity` because the question was being answered by looking at the live
 * `AudioTrack`, and the `AudioTrack` is not the answer. Muting is acknowledged on the wire by the
 * server re-sending `config` *without* its `audio` (protocol.md §2.17), which destroys the track —
 * so "there is no track" was reading our own mute back as "this PC sends no sound". The only thing
 * holding the controls up through a mute was the mute itself, and unmuting takes that away a full
 * round trip before the PC's answer can put a track back. For that window:
 *
 * * the overlay's speaker button vanished from under the finger that had just tapped it, and
 * * the remote's switch snapped to ON, greyed itself out and captioned itself "This PC isn't
 *   sending any sound" — three statements, none of them true, and the middle one taking away the
 *   only way to undo the other two.
 *
 * The fix is to keep what the PC last *announced* as a fact of its own, which our own mute cannot
 * retract. Nothing honest is lost by that: a PC with nothing to send still says so, because a
 * `config` with no audio arriving while we are *not* muting is exactly that statement.
 */
object PcLinkAudioRouting {

    /**
     * What the PC's latest `config` says about having sound for us.
     *
     * @param configHasAudio the `config` that just landed carried an `audio` block.
     * @param wasOffered what the PC had said before it.
     * @param mutedHere the user has the sound left on the computer's own speakers.
     */
    fun offeredAfterConfig(
        configHasAudio: Boolean,
        wasOffered: Boolean,
        mutedHere: Boolean
    ): Boolean = when {
        configHasAudio -> true
        // A `config` with no audio while we are muting IS the acknowledgement of the mute; it is
        // not news about the PC's capture. Reading it as "this PC has no sound" is what took the
        // way back away from the user.
        mutedHere -> wasOffered
        // Unmuted and the PC still sends nothing: an older server, a capture that died, "stream
        // audio" turned off over there. That is a real absence and the controls should say so.
        else -> false
    }

    /**
     * Whether there is sound to route at all: the remote's switch is usable and the player's
     * overlay shows its speaker button.
     *
     * @param offered the PC has announced audio and has not since withdrawn it — see
     *   [offeredAfterConfig].
     * @param playing a track is alive here right now.
     * @param mutedHere kept as a leg of its own so the muted state can always be undone, which is
     *   what `failPcLinkAudio` promises when *this phone* is the side that could not play the
     *   sound the PC offered.
     */
    fun hasSoundToRoute(offered: Boolean, playing: Boolean, mutedHere: Boolean): Boolean =
        offered || playing || mutedHere
}
