package com.teleteh.xplayer2.data.glasses

import android.view.Display
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule PC Link refuses to start without.
 *
 * Two things have gone wrong here before and both are pinned below: assuming a pair of glasses is
 * recognisable by its USB id (plenty are not — a newer model, a plain DisplayPort dongle), and
 * assuming the glasses' panel is the ultra-wide one (in 2D it is an ordinary 1920x1080 display,
 * which is exactly the shape of the bug that kept reaching users).
 */
class GlassesPresenceTest {

    private fun panel(
        displayId: Int = 1,
        flags: Int = 0,
        state: Int = Display.STATE_ON
    ) = GlassesPresence.Panel(displayId, flags, state)

    @Test
    fun `a known pair over USB is enough on its own`() {
        // No external display yet: DisplayPort can take a moment to come up after the HID device
        // enumerates, and the answer must not be "no glasses" in that window.
        assertTrue(GlassesPresence.present(hidAttached = true, panels = emptyList()))
    }

    @Test
    fun `an external panel is enough on its own`() {
        // The case the HID list cannot cover: a vendor or model that is not on it, or a plain
        // USB-C DisplayPort dongle.
        assertTrue(GlassesPresence.present(hidAttached = false, panels = listOf(panel())))
    }

    @Test
    fun `an ordinary 1080p panel counts — glasses in 2D are exactly that`() {
        // Nothing in the rule looks at shape. A pair of glasses in 2D mode presents a plain
        // 1920x1080 external display, and the ultra-wide test used elsewhere would reject it.
        assertTrue(GlassesPresence.present(hidAttached = false, panels = listOf(panel(displayId = 7))))
    }

    @Test
    fun `the phone's own screen is not a second screen`() {
        assertFalse(
            GlassesPresence.present(
                hidAttached = false,
                panels = listOf(panel(displayId = Display.DEFAULT_DISPLAY))
            )
        )
    }

    @Test
    fun `a panel that has powered down is not a panel`() {
        // Glasses on a table with the proximity sensor tripped: the display is still enumerated,
        // but there is nobody it can show anything to.
        assertFalse(
            GlassesPresence.present(
                hidAttached = false,
                panels = listOf(panel(state = Display.STATE_OFF))
            )
        )
    }

    @Test
    fun `a private virtual display is not a screen in the room`() {
        // A screen recorder's surface, or a test harness's virtual display. Nobody is wearing it.
        assertFalse(
            GlassesPresence.present(
                hidAttached = false,
                panels = listOf(panel(flags = Display.FLAG_PRIVATE))
            )
        )
    }

    @Test
    fun `one real panel among unusable ones is still a yes`() {
        assertTrue(
            GlassesPresence.present(
                hidAttached = false,
                panels = listOf(
                    panel(displayId = Display.DEFAULT_DISPLAY),
                    panel(displayId = 3, state = Display.STATE_OFF),
                    panel(displayId = 4, flags = Display.FLAG_PRIVATE),
                    panel(displayId = 5)
                )
            )
        )
    }

    @Test
    fun `nothing attached is a no`() {
        assertFalse(GlassesPresence.present(hidAttached = false, panels = emptyList()))
        assertFalse(
            GlassesPresence.present(
                hidAttached = false,
                panels = listOf(panel(displayId = Display.DEFAULT_DISPLAY))
            )
        )
    }
}
