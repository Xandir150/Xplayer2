package com.teleteh.xplayer2.ui.pclink

import com.teleteh.xplayer2.data.network.PcInputAvailability
import com.teleteh.xplayer2.data.network.PcInputUnavailable
import com.teleteh.xplayer2.data.network.PcLinkInputProtocol
import com.teleteh.xplayer2.player.PcLinkSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC Link remote's decisions, each pinned against the defect that made it worth extracting.
 *
 * All four shipped wrong once, and none of them showed up as a crash or a wrong pixel: a phone that
 * glowed under the goggles for a whole cast, a switch that clicked and did nothing, a volume drag
 * that confirmed a change nobody could hear, and a "Disconnected" with no way back to the pairing
 * ceremony. That is exactly the class of fault a unit test is good at and a device is bad at.
 */
class PcLinkRemotePolicyTest {

    private val streaming = PcLinkSession.Link.STREAMING
    private val connecting = PcLinkSession.Link.CONNECTING
    private val reconnecting = PcLinkSession.Link.RECONNECTING
    private val failed = PcLinkSession.Link.FAILED

    // --- the dimmer -----------------------------------------------------------------------------

    @Test
    fun `the first frames arriving are what arms the timer`() {
        // The whole defect in one line: the remote is created while the link is still connecting,
        // so the only schedule() it ever got was spent on a "no" and the phone never went dark.
        assertEquals(
            PcLinkRemotePolicy.Dim.ARM,
            PcLinkRemotePolicy.dimAction(streaming, connecting)
        )
    }

    @Test
    fun `a streaming reading while already streaming leaves the timer alone`() {
        // Load-bearing, not tidiness: this is asked once a second, and re-arming every tick would
        // restart the 5 s timer forever — the screen would never dim at all.
        assertEquals(
            PcLinkRemotePolicy.Dim.LEAVE_ALONE,
            PcLinkRemotePolicy.dimAction(streaming, streaming)
        )
    }

    @Test
    fun `losing the link lights the screen back up and disarms`() {
        for (down in listOf(connecting, reconnecting, failed)) {
            assertEquals(
                "a drop to $down must not leave the phone fading to black",
                PcLinkRemotePolicy.Dim.DISARM_AND_WAKE,
                PcLinkRemotePolicy.dimAction(down, streaming)
            )
        }
    }

    @Test
    fun `a link that is down and stays down is not woken every second`() {
        assertEquals(
            PcLinkRemotePolicy.Dim.LEAVE_ALONE,
            PcLinkRemotePolicy.dimAction(reconnecting, reconnecting)
        )
    }

    @Test
    fun `the first reading of all decides from scratch`() {
        // onPause forgets the last state, so coming back never acts against a state from before the
        // gap — the screen re-decides on the first tick either way.
        assertEquals(PcLinkRemotePolicy.Dim.ARM, PcLinkRemotePolicy.dimAction(streaming, null))
        assertEquals(
            PcLinkRemotePolicy.Dim.DISARM_AND_WAKE,
            PcLinkRemotePolicy.dimAction(connecting, null)
        )
    }

    @Test
    fun `a reconnect that recovers arms again`() {
        // The deterministic half of the defect: a touch made mid-reconnect disarmed the timer for
        // good, because schedule() drops the pending callback before it asks the policy.
        var last: PcLinkSession.Link? = null
        val seen = mutableListOf<PcLinkRemotePolicy.Dim>()
        for (link in listOf(connecting, streaming, streaming, reconnecting, streaming)) {
            seen += PcLinkRemotePolicy.dimAction(link, last)
            last = link
        }
        assertEquals(
            listOf(
                PcLinkRemotePolicy.Dim.DISARM_AND_WAKE,
                PcLinkRemotePolicy.Dim.ARM,
                PcLinkRemotePolicy.Dim.LEAVE_ALONE,
                PcLinkRemotePolicy.Dim.DISARM_AND_WAKE,
                PcLinkRemotePolicy.Dim.ARM
            ),
            seen
        )
    }

    // --- the eyes-free volume drag --------------------------------------------------------------

    @Test
    fun `the phone's volume is only the user's volume while the sound is here`() {
        assertTrue(PcLinkRemotePolicy.localVolumeIsHeard(audioAvailable = true, audioToGlasses = true))
    }

    @Test
    fun `sound handed back to the computer refuses the gesture`() {
        assertFalse(
            PcLinkRemotePolicy.localVolumeIsHeard(audioAvailable = true, audioToGlasses = false)
        )
    }

    @Test
    fun `a PC that sends no sound at all refuses it too`() {
        // Both legs matter. An older server offers nothing, yet routing still reads "here" (it is
        // `!muted`, and nothing was ever muted) — gating on the routing alone would let the drag
        // through and tick out a percentage for silence.
        assertFalse(
            PcLinkRemotePolicy.localVolumeIsHeard(audioAvailable = false, audioToGlasses = true)
        )
        assertFalse(
            PcLinkRemotePolicy.localVolumeIsHeard(audioAvailable = false, audioToGlasses = false)
        )
    }

    // --- the routing switch ---------------------------------------------------------------------

    @Test
    fun `the tap commands the opposite of what the session says`() {
        assertEquals(false, PcLinkRemotePolicy.audioTapCommand(sessionSaysToGlasses = true))
        assertEquals(true, PcLinkRemotePolicy.audioTapCommand(sessionSaysToGlasses = false))
    }

    @Test
    fun `with no session there is nothing to command`() {
        // The button is checkable, so a tap flips it whatever we do; sending nothing lets the next
        // reading (a second away at most) put it back where truth is.
        assertNull(PcLinkRemotePolicy.audioTapCommand(sessionSaysToGlasses = null))
    }

    // --- the re-pair hand-off -------------------------------------------------------------------

    @Test
    fun `the player launches the ceremony when it is the screen in front`() {
        assertEquals(
            PcLinkRemotePolicy.RepairLauncher.PLAYER,
            PcLinkRemotePolicy.repairLauncher(playerStarted = true, remoteStarted = true)
        )
        assertEquals(
            PcLinkRemotePolicy.RepairLauncher.PLAYER,
            PcLinkRemotePolicy.repairLauncher(playerStarted = true, remoteStarted = false)
        )
    }

    @Test
    fun `during a cast the remote makes the launch the stopped player cannot`() {
        assertEquals(
            PcLinkRemotePolicy.RepairLauncher.REMOTE,
            PcLinkRemotePolicy.repairLauncher(playerStarted = false, remoteStarted = true)
        )
    }

    @Test
    fun `with the app in the background the request waits rather than being dropped`() {
        assertEquals(
            PcLinkRemotePolicy.RepairLauncher.NOBODY,
            PcLinkRemotePolicy.repairLauncher(playerStarted = false, remoteStarted = false)
        )
    }

    // --- driving the PC (protocol.md 2.19) ------------------------------------------------------

    private val inputLive = PcInputAvailability.Live(PcLinkInputProtocol.CLIENT_OFFER)
    private val notEncrypted = PcInputAvailability.Off(PcInputUnavailable.NOT_ENCRYPTED)
    private val operatorOff = PcInputAvailability.Off(PcInputUnavailable.OPERATOR_OFF)

    /**
     * Nothing is claimed before the PC has answered.
     *
     * A "not encrypted" that shows for the second between the video connection and the first
     * `config`, and then turns into a working switch, teaches the user that this message is noise —
     * which is expensive, because when it is real it is the only thing telling them where the fix
     * is.
     */
    @Test
    fun `before the PC has answered there is nothing to show`() {
        assertEquals(PcLinkRemotePolicy.InputRow.HIDDEN, PcLinkRemotePolicy.inputRow(null))
    }

    @Test
    fun `a granted session offers the switch`() {
        assertEquals(PcLinkRemotePolicy.InputRow.READY, PcLinkRemotePolicy.inputRow(inputLive))
    }

    /**
     * The two refusals stay apart all the way to the screen.
     *
     * They are the reason this returns four things instead of a boolean: one is fixed on this
     * phone (forget the PC and pair it again, against a build that speaks §2.18), the other on the
     * PC (a switch the person holding the phone may not be standing next to). "Input unavailable"
     * sends every one of those users to the wrong place.
     */
    @Test
    fun `an unencrypted session says so, rather than saying unavailable`() {
        assertEquals(
            PcLinkRemotePolicy.InputRow.NOT_ENCRYPTED,
            PcLinkRemotePolicy.inputRow(notEncrypted)
        )
    }

    @Test
    fun `a switch left off on the PC says that instead`() {
        assertEquals(
            PcLinkRemotePolicy.InputRow.OPERATOR_OFF,
            PcLinkRemotePolicy.inputRow(operatorOff)
        )
    }

    /** Control needs both halves: the user asking for it and the PC still allowing it. */
    @Test
    fun `control holds only while the user wants it and the PC allows it`() {
        assertTrue(PcLinkRemotePolicy.controlHolds(userWantsControl = true, availability = inputLive))
        assertFalse(PcLinkRemotePolicy.controlHolds(userWantsControl = false, availability = inputLive))
    }

    /**
     * The PC withdrawing permission mid-session takes control away, which is what runs the release
     * of whatever is held down.
     *
     * §2.19.1 has the server repeat `input` on every `config` while input is live, so the field
     * going missing is a real event and not a gap in a message — and §2.19.5 only makes the server
     * let go when a *session* ends, which this is not. If the phone did not notice, a Ctrl held at
     * the moment the operator flipped the switch would stay down until the PC rebooted.
     */
    @Test
    fun `permission withdrawn mid-session drops control`() {
        assertFalse(PcLinkRemotePolicy.controlHolds(userWantsControl = true, availability = operatorOff))
        assertFalse(PcLinkRemotePolicy.controlHolds(userWantsControl = true, availability = null))
    }
}
