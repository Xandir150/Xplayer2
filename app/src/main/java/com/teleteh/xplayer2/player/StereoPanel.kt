package com.teleteh.xplayer2.player

/**
 * Whose shape answers "are the glasses in 3D" — the question a PC Link session hangs on, since
 * the PC only packs its desktop side-by-side once it has been told the glasses are stereo
 * (protocol.md §2.16).
 *
 * [VirtualDesktopMath.panelIsStereo] says what a shape *means*; this says whose shape to read, and
 * the order is the whole point, because a cast reaches the eyes by one of two routes:
 *
 * * **through an external panel** — the ordinary phone case: the glasses are a second display and
 *   the picture is put on it through a `Presentation`. That panel is what the user is looking at,
 *   so it decides;
 * * **through this device's own screen** — a TV box, a pocket PC in desktop mode, anything whose
 *   *only* display is the glasses. There is no second panel there by construction; the app's own
 *   window is what the user sees in the headset, so its shape is the panel's shape.
 *
 * The remembered USB mode is a last resort and nothing more. See `c4e62cd`: it is the last mode
 * *we* commanded, it starts at 2D, and it never moves for a brand with no read-back — so a RayNeo
 * put into 3D by its own button reads as flat with complete confidence, and the PC is told to keep
 * sending mono for a panel that is waiting for two halves. A measurement cannot be wrong about
 * itself; only when there is nothing at all to measure does a stale guess beat no answer.
 */
object StereoPanel {

    /**
     * A screen, as measured, in pixels.
     *
     * Zero or negative means the measurement failed rather than "a very small screen" — a display
     * that is going away hands back zeros — so such a size is skipped rather than believed.
     */
    data class Size(val widthPx: Int, val heightPx: Int) {
        val measured: Boolean get() = widthPx > 0 && heightPx > 0
    }

    /** The rule: the external panel if there is one, else this device's own screen, else memory. */
    fun isStereo(externalPanel: Size?, ownScreen: Size?, remembered3d: Boolean): Boolean {
        val panel = externalPanel?.takeIf { it.measured } ?: ownScreen?.takeIf { it.measured }
        return panel?.let { VirtualDesktopMath.panelIsStereo(it.widthPx, it.heightPx) } ?: remembered3d
    }
}
