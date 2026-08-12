package com.teleteh.xplayer2.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Losing the panel, and what has to stop when it does.
 *
 * The regression this exists to prevent has already happened once, in the form the last line here
 * describes: with the glasses in 2D there is no `Presentation` — they are an ordinary external
 * display and the phone's own window is mirrored into them — so a check written as
 * "presentation != null" would watch a PC Link session survive the glasses coming off, still
 * costing the PC its bitrate and holding its speakers silent.
 */
class ExternalPanelPolicyTest {

    @Test
    fun `a live panel ends nothing`() {
        assertFalse(
            ExternalPanelPolicy.shouldEndSession(
                panelAlive = true, hasPresentation = true, isPcLink = true
            )
        )
        assertFalse(
            ExternalPanelPolicy.shouldEndSession(
                panelAlive = true, hasPresentation = true, isPcLink = false
            )
        )
    }

    @Test
    fun `a film showing through a presentation stops when the panel goes`() {
        assertTrue(
            ExternalPanelPolicy.shouldEndSession(
                panelAlive = false, hasPresentation = true, isPcLink = false
            )
        )
    }

    @Test
    fun `a film playing on the phone is untouched by an external panel it never used`() {
        // Nothing was on the glasses, so nothing is lost with them — the film keeps playing here.
        assertFalse(
            ExternalPanelPolicy.shouldEndSession(
                panelAlive = false, hasPresentation = false, isPcLink = false
            )
        )
    }

    @Test
    fun `a cast that never had a panel is asked the same question, later`() {
        // The rule was only ever wired to the moment a panel is *lost*, so a cast that never got
        // one — glasses whose HID enumerated while DisplayPort never came up, or a hub with no
        // alt-mode — was never asked about at all: it ran on with the desktop flattened into the
        // player's own window, the PC's speakers held silent, and no remote (none is made without a
        // presentation). The entry grace is what makes the question get asked.
        assertTrue(
            ExternalPanelPolicy.shouldEndSession(
                panelAlive = false, hasPresentation = false, isPcLink = true
            )
        )
        // And it must be long enough to be a grace and not a verdict: tapping a PC with the glasses
        // still on the desk is an ordinary way in, and ending that session inside the hot-plug
        // debounce would be a worse fault than the one being fixed.
        assertTrue(
            "the entry grace must outlast the hot-plug debounce, or DisplayPort never gets to finish",
            ExternalPanelPolicy.ENTRY_GRACE_MS > ExternalPanelPolicy.RECONCILE_DEBOUNCE_MS
        )
    }

    @Test
    fun `a cast with no presentation still ends — the 2D-panel case`() {
        // Glasses in 2D: an ordinary 1920x1080 external display, mirrored into rather than
        // presented onto, so there is no presentation to test for. The session must end anyway.
        assertTrue(
            ExternalPanelPolicy.shouldEndSession(
                panelAlive = false, hasPresentation = false, isPcLink = true
            )
        )
    }
}
