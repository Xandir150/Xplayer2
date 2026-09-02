package com.teleteh.xplayer2.ui.pclink

import com.teleteh.xplayer2.data.network.PcDepthState
import com.teleteh.xplayer2.data.network.PcInputAvailability
import com.teleteh.xplayer2.data.network.PcInputUnavailable
import com.teleteh.xplayer2.player.PcLinkSession
import java.util.Locale
import kotlin.math.abs

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

    /**
     * Whether the media strip is on screen (§2.19.7): the PC granted input **and** its offer lists
     * `"media"`.
     *
     * Not tied to the user's control switch: pausing a film is the whole point of the strip, and it
     * should not require turning the pad into a touchpad first. Not shown on a grant without the
     * entry either — that is a server built before the section, which would skip every `"c"` as an
     * unknown event, and a row of buttons that do nothing is worse than no row.
     */
    fun mediaStrip(availability: PcInputAvailability?): Boolean =
        availability is PcInputAvailability.Live && availability.offer.hasMedia

    // --- 3D from the remote (protocol.md 2.20) ------------------------------------------------

    /** What the 3D block of the remote shows for the last `depth` the PC sent. */
    enum class DepthPanel {
        /**
         * Nothing — not a pixel. No `depth` yet, a server built before §2.20, or a PC that is not
         * converting right now (glasses in 2D under an `"auto"` setting is the common case).
         */
        HIDDEN,

        /** The two sliders and the reset: the PC is converting, and this is what to adjust by eye. */
        SLIDERS,

        /**
         * One quiet line saying 3D is switched off in the window. The one refusal worth the room:
         * a user who came here for the sliders would otherwise go looking for them on this phone,
         * and the switch is on the PC.
         */
        OFF_LINE
    }

    /**
     * `active` is the whole cue (§2.20.2): it says whether the stream being sent is stereo the
     * server made, and `setting` only explains an absence. An `"off"` that is somehow converting
     * still gets sliders — the picture is what the person is judging, and it is in 3D.
     */
    fun depthPanel(state: PcDepthState?): DepthPanel = when {
        state == null -> DepthPanel.HIDDEN
        state.active -> DepthPanel.SLIDERS
        state.switchedOff -> DepthPanel.OFF_LINE
        else -> DepthPanel.HIDDEN
    }

    /**
     * Whether an incoming `depth` may move a slider right now — the "do not fight the finger" rule.
     *
     * `depth` is the only source of truth for the sliders, and the phone renders what the last one
     * said (§2.20.2). But a drag is a stream of `set_depth`s and each is acknowledged with a `depth`
     * carrying the value that was sent a moment ago, not the one under the finger now — applied as
     * they arrive, they would drag the thumb back behind the finger ten times a second. So a slider
     * being dragged is left alone, and for [holdUntilMs] after the last value it sent (long enough
     * for the acknowledgement of that value to have landed on any LAN) it still is; then the latest
     * `depth` is applied, and if the PC clamped or the window moved the same slider, the thumb goes
     * where the truth is.
     */
    fun sliderFollowsServer(dragging: Boolean, nowMs: Long, holdUntilMs: Long): Boolean =
        !dragging && nowMs >= holdUntilMs

    /**
     * The strength figure as the desktop window prints it — thousandths as a fraction to three
     * places, `20` → `0.020` — so a number read off the phone can be repeated back to someone
     * looking at the window. Locale-independent for the same reason: the window's is.
     */
    fun showDivergence(thousandths: Int): String =
        String.format(Locale.ROOT, "%.3f", thousandths / 1000.0)

    /**
     * The convergence figure as the window prints it: a real minus sign for "towards you", a plus
     * for "away", nothing for the zero that leaves the picture in charge — `-120` → `−0.12`.
     */
    fun showConvergence(thousandths: Int): String {
        val sign = when {
            thousandths > 0 -> "+"
            thousandths < 0 -> "−"
            else -> ""
        }
        return sign + String.format(Locale.ROOT, "%.2f", abs(thousandths) / 1000.0)
    }
}
