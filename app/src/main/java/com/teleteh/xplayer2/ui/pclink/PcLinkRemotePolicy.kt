package com.teleteh.xplayer2.ui.pclink

import com.teleteh.xplayer2.data.network.PcInputAvailability
import com.teleteh.xplayer2.data.network.PcInputUnavailable
import com.teleteh.xplayer2.player.PcLinkSession

/**
 * The decisions [PcLinkRemoteActivity] turns on, taken out of the views so they can be argued with
 * in a test.
 *
 * Every one of them is here because the version living inside the activity was wrong in a way that
 * nobody could see: a screen that never went dark, a switch that sent the value it already had, a
 * volume gesture that confirmed a change nobody could hear. None of them needs an Android runtime
 * to be right, so none of them should need a device to be checked.
 */
object PcLinkRemotePolicy {

    /** What a link-state reading has to do to the dim timer. */
    enum class Dim {
        /** Nothing changed — leave the timer exactly as it is. */
        LEAVE_ALONE,

        /** Frames are arriving again: arm the idle timer so the phone can go dark. */
        ARM,

        /** Frames have stopped: disarm the timer and light the screen back up. */
        DISARM_AND_WAKE
    }

    /**
     * `RemoteScreenDim` asks its policy **only at the moment the timer is armed**, and
     * `schedule()` drops the pending callback *before* it asks — so a call made in the wrong state
     * does not merely fail to arm, it actively disarms. The remote is created while the link is
     * still `CONNECTING` (the handshake is four messages and two TCP connects behind the activity
     * launch), which means `onResume`'s single `schedule()` is spent on a "no" and, without this,
     * a cast nobody touches never goes dark for its whole life. One stray touch during a reconnect
     * does the same thing permanently.
     *
     * Edge-triggered, exactly like the film remote's play/pause hook: this is consulted once a
     * second, and re-arming on every tick would restart the 5 s timer forever — the screen would
     * then never dim at all, which is the same defect wearing the opposite mask.
     *
     * @param link the reading just taken.
     * @param lastLink the reading this policy last acted on, or null on the first one.
     */
    fun dimAction(link: PcLinkSession.Link, lastLink: PcLinkSession.Link?): Dim = when {
        link == lastLink -> Dim.LEAVE_ALONE
        link == PcLinkSession.Link.STREAMING -> Dim.ARM
        // Mid-reconnect, or with the link down, the user is looking at this screen to find out what
        // happened — so a timer armed while streaming must not be allowed to black it out.
        else -> Dim.DISARM_AND_WAKE
    }

    /**
     * Whether the phone's media volume is the volume the user is hearing.
     *
     * The remote's eyes-free drag drives `STREAM_MUSIC`, which is the right stream only while the
     * PC's sound is being played *here*. Handed back to the computer, the gesture ticks and reports
     * a percentage for a level nobody can hear — and silently moves the level the sound will come
     * back at.
     *
     * Both legs are load-bearing. [audioToGlasses] alone would still let the drag through against
     * an older server, which sends no sound at all while reporting the routing as "here" (there is
     * nothing to mute, so `!pcAudioMuted` is true).
     */
    fun localVolumeIsHeard(audioAvailable: Boolean, audioToGlasses: Boolean): Boolean =
        audioAvailable && audioToGlasses

    /**
     * What tapping the routing row must command, given what the session says right now — or null
     * when there is no session to command.
     *
     * Deliberately takes the session's value and never the button's: `MaterialButton` is
     * `checkable`, and `performClick()` calls `toggle()` **before** it dispatches the click
     * listener, so `!isChecked` read inside the listener is the value the session already holds.
     * Sending that is a no-op the host early-returns on, and the next reading paints the button
     * straight back — a switch that clicks, ripples and does nothing, in either direction.
     */
    fun audioTapCommand(sessionSaysToGlasses: Boolean?): Boolean? =
        sessionSaysToGlasses?.let { !it }

    /** Who is in a position to put the re-pair ceremony on screen. */
    enum class RepairLauncher {
        /** The player itself — it is started, so it may launch. */
        PLAYER,

        /** The remote in front of it, which is the started activity during a cast. */
        REMOTE,

        /** Neither: keep the request pending rather than dropping it. */
        NOBODY
    }

    /**
     * A PC that has forgotten this phone can only be answered with a fresh six-digit ceremony, and
     * a *stopped* activity may not launch one. During a cast the player is exactly that: the remote
     * is an opaque full-screen activity in front of it, so the player never comes back to STARTED
     * while the remote is up — and the deferral its own code writes ("it waits for onStart") waits
     * forever, leaving the remote on a bare "Disconnected".
     *
     * The app *is* in the foreground, just not that activity, so the launch is legal — it simply
     * has to be made by whichever screen is in front.
     */
    fun repairLauncher(playerStarted: Boolean, remoteStarted: Boolean): RepairLauncher = when {
        playerStarted -> RepairLauncher.PLAYER
        remoteStarted -> RepairLauncher.REMOTE
        else -> RepairLauncher.NOBODY
    }

    /** What the "control the PC" section of the remote shows for a given session. */
    enum class InputRow {
        /** Nothing at all: there is no session yet, or nothing useful to say about one. */
        HIDDEN,

        /** The PC is accepting input — offer the switch. */
        READY,

        /** Refused because this session is not encrypted. The fix is on this phone: re-pair. */
        NOT_ENCRYPTED,

        /** Refused because the PC's operator has not turned input on. The fix is on the PC. */
        OPERATOR_OFF
    }

    /**
     * What to show where the input switch goes.
     *
     * The two refusals are the whole reason this returns four things instead of a boolean.
     * "Input unavailable" is a dead end for a user: there is nothing on this screen that fixes it,
     * and nothing that says where the fix is. [NOT_ENCRYPTED] and [OPERATOR_OFF] are genuinely
     * different problems with different owners — one is answered by re-pairing this phone against a
     * PC that speaks §2.18, the other by a switch on the PC that the person holding the phone may
     * not even be standing next to — and telling them apart is the entire help this screen can give.
     *
     * Hidden until the first `config` arrives ([availability] null) rather than showing a refusal:
     * during the handshake there is no answer yet, and a "not encrypted" that appears for a second
     * and then turns into a working switch teaches the user to distrust the message.
     */
    fun inputRow(availability: PcInputAvailability?): InputRow = when (availability) {
        null -> InputRow.HIDDEN
        is PcInputAvailability.Live -> InputRow.READY
        is PcInputAvailability.Off -> when (availability.reason) {
            PcInputUnavailable.NOT_ENCRYPTED -> InputRow.NOT_ENCRYPTED
            PcInputUnavailable.OPERATOR_OFF -> InputRow.OPERATOR_OFF
        }
    }

    /**
     * Whether the remote may keep driving the PC right now.
     *
     * Control is switched on by the user but revoked by the world: the PC's operator can flip input
     * off, the link can drop, the session can end. Every one of those has to put the pad back to
     * being a volume slider rather than leaving it sending into nothing — and, more importantly,
     * has to run the release of whatever is still held down.
     */
    fun controlHolds(userWantsControl: Boolean, availability: PcInputAvailability?): Boolean =
        userWantsControl && availability is PcInputAvailability.Live
}
