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
 *
 * The second half of the table is the opposite fault, reported by the first beta users: a device
 * whose *only* screen is the glasses has no external panel to lose and never had one, and the rule
 * ended a cast that was working perfectly.
 */
class ExternalPanelPolicyTest {

    // Nothing has an external panel to lose unless a test says otherwise.
    private fun ended(
        panelAlive: Boolean,
        hasPresentation: Boolean,
        isPcLink: Boolean,
        glassesAttached: Boolean = false,
        panelEverAlive: Boolean = hasPresentation,
    ) = ExternalPanelPolicy.shouldEndSession(
        panelAlive = panelAlive,
        hasPresentation = hasPresentation,
        isPcLink = isPcLink,
        glassesAreOwnScreen = ExternalPanelPolicy.glassesAreTheOnlyScreen(
            glassesAttached = glassesAttached,
            panelEverAlive = panelEverAlive,
        ),
    )

    @Test
    fun `a live panel ends nothing`() {
        assertFalse(ended(panelAlive = true, hasPresentation = true, isPcLink = true))
        assertFalse(ended(panelAlive = true, hasPresentation = true, isPcLink = false))
    }

    @Test
    fun `a film showing through a presentation stops when the panel goes`() {
        assertTrue(ended(panelAlive = false, hasPresentation = true, isPcLink = false))
    }

    @Test
    fun `a film playing on the phone is untouched by an external panel it never used`() {
        // Nothing was on the glasses, so nothing is lost with them — the film keeps playing here.
        assertFalse(ended(panelAlive = false, hasPresentation = false, isPcLink = false))
    }

    @Test
    fun `a cast that never had a panel and has no glasses either is asked the same question, later`() {
        // The rule was only ever wired to the moment a panel is *lost*, so a cast that never got
        // one — glasses unplugged on the way in, or a panel that died during the ceremony — was
        // never asked about at all: it ran on with the desktop flattened into the player's own
        // window, the PC's speakers held silent, and no remote (none is made without a
        // presentation). The entry grace is what makes the question get asked.
        assertTrue(ended(panelAlive = false, hasPresentation = false, isPcLink = true))
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
        assertTrue(ended(panelAlive = false, hasPresentation = false, isPcLink = true))
    }

    @Test
    fun `a cast on a device whose only screen is the glasses is left alone`() {
        // The one the beta reports are about: an XREAL Beam Pro, a pocket PC in desktop mode, a TV
        // box. There is no second panel there by definition — the app's own window IS what the
        // user sees in the headset — and the entry grace was ending the session after eight
        // seconds with "connect your glasses" on a cast that was working.
        assertFalse(
            ended(
                panelAlive = false, hasPresentation = false, isPcLink = true,
                glassesAttached = true, panelEverAlive = false,
            )
        )
    }

    @Test
    fun `taking the goggles off still ends the cast, USB or no USB`() {
        // The carve-out that keeps the fix above from undoing the one before it. Unplugging the
        // DisplayPort side, or the proximity sensor powering the panel down when the goggles come
        // off, leaves USB HID attached and answering happily — so "a pair is attached" cannot on
        // its own be permission to keep streaming. A panel this cast HAS had and lost is a picture
        // nobody is looking at.
        assertTrue(
            ended(
                panelAlive = false, hasPresentation = false, isPcLink = true,
                glassesAttached = true, panelEverAlive = true,
            )
        )
        assertTrue(
            ended(
                panelAlive = false, hasPresentation = true, isPcLink = true,
                glassesAttached = true, panelEverAlive = true,
            )
        )
    }

    @Test
    fun `unplugging the glasses from a device they were the screen of ends the cast`() {
        // Same device as the case above, one step further: USB is gone, so there is neither a
        // panel nor a pair, and nothing is showing the desktop anywhere.
        assertTrue(
            ended(
                panelAlive = false, hasPresentation = false, isPcLink = true,
                glassesAttached = false, panelEverAlive = false,
            )
        )
    }

    @Test
    fun `a film is never kept alive by the USB signal`() {
        // The exemption is PC Link's alone. A film has somewhere else to go — the phone — so the
        // question "is anything still showing this" has a different answer, and the glasses being
        // plugged in says nothing about the panel that just died under a presentation.
        assertTrue(
            ended(
                panelAlive = false, hasPresentation = true, isPcLink = false,
                glassesAttached = true, panelEverAlive = false,
            )
        )
    }
}
