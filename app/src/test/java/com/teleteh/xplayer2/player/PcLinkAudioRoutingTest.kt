package com.teleteh.xplayer2.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio controls' one question — "is there sound to route" — and the mute that used to answer
 * it wrongly in both directions.
 *
 * The defect these pin: muting is acknowledged by the server re-sending `config` *without* its
 * `audio`, so our own mute destroys the local track. Asking the track whether the PC has sound
 * therefore reads our own mute back as the PC's answer, and the only thing keeping the controls
 * alive through a mute was the mute itself — which unmuting takes away a round trip before the PC
 * can put a track back. In that window the player's speaker button disappeared from under the
 * finger that had just tapped it, and the remote's switch snapped to ON, greyed itself out and
 * said "This PC isn't sending any sound".
 */
class PcLinkAudioRoutingTest {

    /**
     * The three facts `PlayerActivity` keeps about the PC's sound, driven through the same two
     * rules it drives them through — so the sequences below are the user's, in order.
     */
    private class Session {
        /** The user's routing choice: true = the sound stays on the computer. */
        var muted = false
            private set
        private var playing = false
        private var offered = false

        /** A `config` arrives. `applyPcLinkAudioConfig` builds or drops the track to match it. */
        fun configFromPc(withAudio: Boolean) {
            offered = PcLinkAudioRouting.offeredAfterConfig(
                configHasAudio = withAudio,
                wasOffered = offered,
                mutedHere = muted
            )
            playing = withAudio
        }

        /** The overlay's speaker button, or the remote's switch. */
        fun tapTheControl() {
            muted = !muted
        }

        /** The switch is usable and the speaker button is on screen. */
        val soundToRoute: Boolean
            get() = PcLinkAudioRouting.hasSoundToRoute(
                offered = offered,
                playing = playing,
                mutedHere = muted
            )
    }

    // --- the defect --------------------------------------------------------------------------

    @Test
    fun `unmuting does not take the control away while the PC's answer is on the wire`() {
        val session = Session()
        session.configFromPc(withAudio = true)
        assertTrue(session.soundToRoute)

        // Sound back to the computer. The PC acknowledges by re-sending config with no audio,
        // which drops the track here.
        session.tapTheControl()
        session.configFromPc(withAudio = false)
        assertTrue("a muted session is the one state that must stay undoable", session.soundToRoute)

        // Sound back to the glasses. There is no track yet — asking for one is what this tap
        // does — and the control the finger is still on must survive the round trip.
        session.tapTheControl()
        assertTrue(
            "the control that asks for the sound back must not vanish while asking",
            session.soundToRoute
        )

        // The PC answers, and nothing about the controls changes as it lands.
        session.configFromPc(withAudio = true)
        assertTrue(session.soundToRoute)
    }

    @Test
    fun `the acknowledgement of a mute is not the PC saying it has nothing to send`() {
        assertTrue(
            PcLinkAudioRouting.offeredAfterConfig(
                configHasAudio = false, wasOffered = true, mutedHere = true
            )
        )
    }

    // --- the honest absences, which must stay honest -------------------------------------------

    @Test
    fun `a PC that sends no sound gets no control`() {
        val session = Session()
        session.configFromPc(withAudio = false)
        assertFalse(session.soundToRoute)
    }

    @Test
    fun `a capture that dies while the sound is ours is admitted`() {
        val session = Session()
        session.configFromPc(withAudio = true)
        // Nothing was muted here: the PC withdrawing its audio is the PC's own news.
        session.configFromPc(withAudio = false)
        assertFalse(session.soundToRoute)
    }

    @Test
    fun `a PC that loses its capture while muted is only admitted once the user asks again`() {
        val session = Session()
        session.configFromPc(withAudio = true)
        session.tapTheControl()
        session.configFromPc(withAudio = false)

        // Something on the PC side stopped the capture while we were muted. Indistinguishable from
        // the acknowledgement until the user asks for the sound back — so the way back stays.
        session.configFromPc(withAudio = false)
        assertTrue(session.soundToRoute)

        // They ask, and the PC still has nothing. Now it is the PC's answer, and it is shown.
        session.tapTheControl()
        session.configFromPc(withAudio = false)
        assertFalse(session.soundToRoute)
    }

    @Test
    fun `a phone that cannot play what the PC offered keeps its way back`() {
        // failPcLinkAudio: the track would not open, so we mute and tell the PC to stop spending
        // the bandwidth. Its KDoc promises the user a way back, which is a visible control.
        val session = Session()
        session.configFromPc(withAudio = true)
        session.tapTheControl()
        assertTrue(session.muted)
        assertTrue(session.soundToRoute)
    }
}
