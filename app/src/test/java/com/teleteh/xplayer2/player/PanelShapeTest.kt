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
}
