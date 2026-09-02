package com.teleteh.xplayer2.player

/**
 * Where a PC Link frame lands on the panel, in pixels. Pure — no GL, no Android types — so the
 * rule is checked on the JVM rather than discovered in the glasses.
 *
 * A cast is drawn by [OuToSbsGlView] in [RESIZE_MODE_SOURCE_ASPECT], and that mode has one
 * principle — a desktop has one true shape, known exactly, and is never stretched (`20acddc`:
 * the owner has seen a squashed 16:10 Mac once) — applied to two stream shapes:
 *
 * * **mono** — the frame *is* the desktop: fitted to the panel at its own aspect, the rest black.
 * * **side-by-side** — the frame is a *pair*, and the desktop's shape has to be recovered from
 *   it first, because the server packs the two eyes either at full width (a 32:9 frame for a
 *   16:9 desktop) or squeezed into the source's own width (a 16:9 frame holding two 8:9 eyes),
 *   and announces both as `"sbs"`. [eyeShape] tells them apart by the frame's aspect — a pair
 *   shaped like the stereo panel is a full-width one — and each eye's desktop is then fitted
 *   into its half of the panel, exactly as a mono desktop is fitted into the whole. Fitting a
 *   half to its own *pixel* aspect instead is what put a square-ish desktop in each eye
 *   (issue #13): a 16:9 desktop fills its eye at either width, a 16:10 one keeps its shape.
 *
 * The one misread, known and accepted: a desktop already wider than 2.5:1 (a 32:9
 * super-ultrawide) cast at half width is a frame shaped like a full-width pair, and is drawn as
 * one — two 16:9 eyes, the desktop squeezed into each. That is the price of the layout not being
 * on the wire; the day `config` carries it, [eyeShape] is where it plugs in.
 */
object PcLinkFrameLayout {

    /** A destination rectangle in panel pixels; [x], [y] is its bottom-left corner, as GL counts. */
    data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
        val right: Int get() = x + width
        val top: Int get() = y + height
    }

    /** A picture's size in pixels. */
    data class Shape(val width: Int, val height: Int)

    /**
     * The rectangle each draw of the frame lands in: one for a mono frame (the whole picture,
     * fitted), two for a stereo pair (the left half's, then the right half's), each the desktop
     * fitted into its eye's half of the panel.
     *
     * [sbs] is the renderer's "draw as a pair": the stream is packed *and* the panel is the
     * glasses' side-by-side one. A packed stream reaching a flat panel is drawn as mono — both
     * halves visible at the frame's own shape — which is all a flat panel can show of it, and is
     * the behaviour it had.
     */
    fun eyeRects(
        frameWidth: Int,
        frameHeight: Int,
        sbs: Boolean,
        panelWidth: Int,
        panelHeight: Int
    ): List<Rect> {
        if (!sbs) return listOf(fit(frameWidth, frameHeight, panelWidth, panelHeight))
        val eyeWidth = panelWidth / 2
        val desktop = eyeShape(frameWidth, frameHeight)
        val left = fit(desktop.width, desktop.height, eyeWidth, panelHeight)
        return listOf(left, left.copy(x = left.x + eyeWidth))
    }

    /**
     * The shape of the desktop one eye of a packed pair sees — the frame's layout, read off its
     * width, since the protocol does not say.
     *
     * A frame at least as wide as the stereo panel's 2.5:1 — the same threshold, deliberately,
     * as [VirtualDesktopMath.panelIsStereo], so the app has one notion of "two eyes wide", not
     * two — is a full-width pair, and each eye is half of it. Anything narrower is the half-width
     * packing, two eyes squeezed into the desktop's own width, and the desktop is the frame's
     * shape: the squeezed eye stretched back out. See the class doc for the one shape this
     * misreads.
     */
    fun eyeShape(frameWidth: Int, frameHeight: Int): Shape =
        if (VirtualDesktopMath.panelIsStereo(frameWidth, frameHeight)) Shape(frameWidth / 2, frameHeight)
        else Shape(frameWidth, frameHeight)

    /**
     * [frameWidth]×[frameHeight] fitted inside [panelWidth]×[panelHeight] at its own aspect and
     * centred — the letterbox. Integer throughout, so the answer is exact and the same on every
     * device; a frame or panel with no size yet fills the panel, as the renderer's own fit does.
     */
    private fun fit(frameWidth: Int, frameHeight: Int, panelWidth: Int, panelHeight: Int): Rect {
        if (frameWidth <= 0 || frameHeight <= 0 || panelWidth <= 0 || panelHeight <= 0) {
            return Rect(0, 0, panelWidth, panelHeight)
        }
        // Panel wider than the frame ⇔ panelW/panelH > frameW/frameH, cross-multiplied so that
        // no rounding decides a tie.
        val panelWider = panelWidth.toLong() * frameHeight > frameWidth.toLong() * panelHeight
        return if (panelWider) {
            val width = (panelHeight.toLong() * frameWidth / frameHeight).toInt().coerceAtLeast(1)
            Rect((panelWidth - width) / 2, 0, width, panelHeight)
        } else {
            val height = (panelWidth.toLong() * frameHeight / frameWidth).toInt().coerceAtLeast(1)
            Rect(0, (panelHeight - height) / 2, panelWidth, height)
        }
    }
}
