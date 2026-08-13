package com.teleteh.xplayer2.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which panel is a stereo one.
 *
 * The cost of getting this wrong is not subtle and is worth stating: answer "stereo" for a flat
 * panel and the user gets two half-width desktops side by side; answer "flat" for an SBS panel and
 * one desktop is stretched across both eyes, which the eyes cannot fuse at all. Both were shipped
 * at some point — the first by dividing the viewport unconditionally, the second by asking the
 * glasses for a mode they answer from memory rather than from the panel.
 */
class PanelShapeTest {

    @Test
    fun `the glasses' two real modes are told apart`() {
        assertTrue("3840x1080 is the side-by-side panel", VirtualDesktopMath.panelIsStereo(3840, 1080))
        assertFalse("1920x1080 is the flat one", VirtualDesktopMath.panelIsStereo(1920, 1080))
    }

    @Test
    fun `the other shapes the glasses come in`() {
        // 1920x1200 (some 16:10 panels) and the 90 Hz SBS mode are the same two answers.
        assertFalse(VirtualDesktopMath.panelIsStereo(1920, 1200))
        assertTrue(VirtualDesktopMath.panelIsStereo(3840, 1200))
    }

    @Test
    fun `a phone is never mistaken for a stereo panel`() {
        // Portrait and landscape, and the tall aspect a modern phone has.
        assertFalse(VirtualDesktopMath.panelIsStereo(1440, 3088))
        assertFalse(VirtualDesktopMath.panelIsStereo(3088, 1440))
    }

    @Test
    fun `nothing real sits near the threshold`() {
        // The margin is the whole reason a measurement beats a remembered mode: 21:9 ultrawide
        // monitors and 32:9 doubled panels are on opposite sides of it with room to spare.
        assertFalse("21:9 is a wide monitor, not two eyes", VirtualDesktopMath.panelIsStereo(2560, 1080))
        assertTrue("32:9 is two 16:9 halves", VirtualDesktopMath.panelIsStereo(5120, 1440))
    }

    @Test
    fun `a panel with no height is not a stereo panel`() {
        // getRealMetrics on a display that is going away can hand back zeros, and a divide by
        // zero here would surface as a crash in the middle of a cast.
        assertFalse(VirtualDesktopMath.panelIsStereo(3840, 0))
        assertFalse(VirtualDesktopMath.panelIsStereo(0, 0))
    }

    // --- Whose shape gets read: StereoPanel ------------------------------------------------------

    private val sbsPanel = StereoPanel.Size(3840, 1080)
    private val flatPanel = StereoPanel.Size(1920, 1080)
    private val phoneScreen = StereoPanel.Size(2400, 1080)

    @Test
    fun `the external panel decides whenever there is one`() {
        // The ordinary phone: the glasses are a second display and the phone's own screen has
        // nothing to say about what they are showing.
        assertTrue(
            StereoPanel.isStereo(
                externalPanel = sbsPanel, ownScreen = phoneScreen, remembered3d = false
            )
        )
        assertFalse(
            StereoPanel.isStereo(
                externalPanel = flatPanel, ownScreen = sbsPanel, remembered3d = true
            )
        )
    }

    @Test
    fun `with no external panel this device's own screen may itself be the glasses`() {
        // A TV box or a pocket PC in desktop mode: switching the glasses to 3D changes the shape
        // of the only display there is, and nothing else in the app will ever report it.
        assertTrue(
            StereoPanel.isStereo(externalPanel = null, ownScreen = sbsPanel, remembered3d = false)
        )
        assertFalse(
            StereoPanel.isStereo(externalPanel = null, ownScreen = flatPanel, remembered3d = true)
        )
    }

    @Test
    fun `a phone answering for itself is flat`() {
        // No panel, so the desktop is flattened into this window — there is no second eye to send,
        // and the remembered mode must not talk the PC into stereo for a phone screen.
        assertFalse(
            StereoPanel.isStereo(externalPanel = null, ownScreen = phoneScreen, remembered3d = true)
        )
    }

    @Test
    fun `a measurement that failed is skipped, not believed`() {
        // Zeros come back from a display that is going away; taking them at face value would
        // answer "flat" for a stereo panel that is merely mid-handshake.
        assertTrue(
            StereoPanel.isStereo(
                externalPanel = StereoPanel.Size(0, 0), ownScreen = sbsPanel, remembered3d = false
            )
        )
        assertTrue(
            StereoPanel.isStereo(
                externalPanel = StereoPanel.Size(3840, 0),
                ownScreen = null,
                remembered3d = true
            )
        )
    }

    @Test
    fun `the remembered mode is the last resort and only that`() {
        // Nothing to measure at all — no panel, no readable screen. A stale guess beats no answer,
        // but it never gets a say while anything can be measured (the cases above).
        assertTrue(StereoPanel.isStereo(externalPanel = null, ownScreen = null, remembered3d = true))
        assertFalse(StereoPanel.isStereo(externalPanel = null, ownScreen = null, remembered3d = false))
    }
}
