package com.teleteh.xplayer2.data.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * "Is there a pair of glasses on this phone right now?" — asked before anything that only makes
 * sense with them on.
 *
 * PC Link is the case this exists for: it puts a computer's desktop on the glasses and has nothing
 * to put it on otherwise. A session without them is not a degraded experience, it is a socket
 * costing the PC its bitrate and its speakers for a picture nobody can see — so the entry point
 * refuses rather than starts.
 *
 * **Two independent answers, either of which is a yes**, because neither is complete on its own:
 *
 * * **A known pair over USB HID** — [GlassesController.anyAttached], the VID/PID list mirrored from
 *   wheaney/XRLinuxDriver. It spans every vendor we know of (XREAL, VITURE, RayNeo, Rokid) and it
 *   is flavour-independent: VITURE is on that list by VID/PID, so the `play` build — which ships no
 *   VITURE SDK at all — still recognises a pair of Lumas plugged in. What it cannot do is recognise
 *   hardware that shipped after this list was written, or a plain USB-C DisplayPort dongle.
 * * **An external display** — which is what glasses *are* to Android once the DisplayPort link is
 *   up, whatever the badge on them says.
 *
 * Deliberately **not** `DisplayUtils.findUltraWideExternalDisplay`. That one is looking for the
 * ultra-wide side-by-side panel a pair of glasses presents in 3D mode; in 2D the very same glasses
 * are an ordinary 1920x1080 display, and that is exactly the case that has been biting us. The
 * question here is "is a second screen attached", not "is it the stereo one".
 *
 * The predicate itself is pure — [present] takes what was found rather than looking it up — so the
 * rules below (what counts as a panel, and that either signal suffices) are testable on the JVM.
 */
object GlassesPresence {

    /** One external display, reduced to the three facts the rule below actually uses. */
    data class Panel(val displayId: Int, val flags: Int, val state: Int)

    /**
     * Whether [panel] is a screen the user can actually see something on.
     *
     * The default display is the phone itself and never counts. A display the system reports as
     * OFF is a panel that has powered down — glasses resting on a table with the proximity sensor
     * tripped, most often — and is no better than an absent one. A private display belongs to
     * whoever created it (a screen recorder, a virtual-display test harness) and is not a screen in
     * the room.
     */
    fun isUsablePanel(panel: Panel): Boolean =
        panel.displayId != Display.DEFAULT_DISPLAY &&
            panel.state != Display.STATE_OFF &&
            (panel.flags and Display.FLAG_PRIVATE) == 0

    /** The rule: a known pair over HID, or any usable external panel. */
    fun present(hidAttached: Boolean, panels: List<Panel>): Boolean =
        hidAttached || panels.any { isUsablePanel(it) }

    /** The same rule, against this device right now. */
    fun present(context: Context): Boolean =
        present(GlassesController.anyAttached(context), panels(context))

    /**
     * Every display this app can see, the phone's own included — [isUsablePanel] does the filtering,
     * so the raw list is what a caller gets and what a test can be handed.
     *
     * Both categories are asked: `DISPLAY_CATEGORY_PRESENTATION` is the platform's own idea of
     * "somewhere to show something", but it is a filtered view, and a panel that is attached while
     * momentarily unsuitable for a presentation is still a panel.
     */
    fun panels(context: Context): List<Panel> = try {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        (dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).asList() + dm.displays.asList())
            .distinctBy { it.displayId }
            .map { Panel(it.displayId, it.flags, it.state) }
    } catch (_: Throwable) {
        emptyList()
    }
}
