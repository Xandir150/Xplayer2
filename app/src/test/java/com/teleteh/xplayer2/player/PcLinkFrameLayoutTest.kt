package com.teleteh.xplayer2.player

import com.teleteh.xplayer2.player.PcLinkFrameLayout.Rect
import com.teleteh.xplayer2.player.PcLinkFrameLayout.Shape
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where a cast lands on the panel ([PcLinkFrameLayout]).
 *
 * The bug this guards against was shipped and reported (issue #13): the server's half-width
 * stereo layout sends a 16:9 frame holding two squeezed 8:9 eyes, announced as `"sbs"` exactly
 * like the 32:9 full-width one, and the phone fitted each half to its own pixel aspect — a
 * square-ish desktop in each eye, with black either side. The frame's shape is not the desktop's;
 * the desktop's is read back off the frame's width first, and it is that which fills the eye.
 */
class PcLinkFrameLayoutTest {

    /** The glasses' 3D panel: 1920×1080 per eye, side by side. */
    private val panelWidth = 3840
    private val panelHeight = 1080
    private val leftEye = Rect(0, 0, 1920, 1080)
    private val rightEye = Rect(1920, 0, 1920, 1080)

    private fun onSbsPanel(frameWidth: Int, frameHeight: Int, sbs: Boolean = true) =
        PcLinkFrameLayout.eyeRects(frameWidth, frameHeight, sbs, panelWidth, panelHeight)

    // --- side-by-side: each half is an eye, at the desktop's shape ------------------------------

    @Test
    fun `a full-width pair lands each eye on exactly half the panel`() {
        // 32:9 — two 16:9 eyes at the desktop's full resolution.
        assertEquals(listOf(leftEye, rightEye), onSbsPanel(3840, 1080))
    }

    @Test
    fun `a half-width pair lands each eye on exactly half the panel too`() {
        // 16:9 — the same two eyes squeezed into the source's own width. Fitting a half to its
        // 8:9 pixel aspect gave 960×1080 in the middle of each eye; the desktop is 16:9 and the
        // eye is 16:9, so the desktop is the whole eye.
        assertEquals(listOf(leftEye, rightEye), onSbsPanel(1920, 1080))
    }

    @Test
    fun `a 16 by 10 Mac keeps its shape in 3D, at either width`() {
        // The owner objected to a squashed desktop once, in 2D, and 2D letterboxes for it. A
        // 1728×1080 Mac in 3D is the same desktop: pillarboxed to 1728 per eye with 96 px of
        // black either side, whether the pair came at full width (3456 wide) or half (1728).
        val pillarboxed = listOf(Rect(96, 0, 1728, 1080), Rect(2016, 0, 1728, 1080))
        assertEquals("full width", pillarboxed, onSbsPanel(3456, 1080))
        assertEquals("half width", pillarboxed, onSbsPanel(1728, 1080))
    }

    @Test
    fun `a wide desktop is letterboxed per eye`() {
        // A 3440×1440 ultrawide cast at half width: 2.39:1 is under the 2.5 that means a
        // full-width pair, so it is read as the desktop it is and fitted 1920 wide per eye.
        assertEquals(
            listOf(Rect(0, 138, 1920, 803), Rect(1920, 138, 1920, 803)),
            onSbsPanel(3440, 1440)
        )
    }

    @Test
    fun `a 16 by 9 desktop on a 16 by 10 panel is letterboxed per eye`() {
        // 1920×1200 per eye exists too; the eye box is the panel's, and the desktop keeps its
        // shape inside it with 60 px of black top and bottom — at either width.
        val letterboxed = listOf(Rect(0, 60, 1920, 1080), Rect(1920, 60, 1920, 1080))
        assertEquals(
            "full width",
            letterboxed,
            PcLinkFrameLayout.eyeRects(3840, 1080, sbs = true, panelWidth = 3840, panelHeight = 1200)
        )
        assertEquals(
            "half width",
            letterboxed,
            PcLinkFrameLayout.eyeRects(1920, 1080, sbs = true, panelWidth = 3840, panelHeight = 1200)
        )
    }

    @Test
    fun `a 16 by 10 desktop on a 16 by 10 panel fills its eyes`() {
        assertEquals(
            listOf(Rect(0, 0, 1920, 1200), Rect(1920, 0, 1920, 1200)),
            PcLinkFrameLayout.eyeRects(3840, 1200, sbs = true, panelWidth = 3840, panelHeight = 1200)
        )
    }

    @Test
    fun `the eyes tile the panel exactly`() {
        val (left, right) = onSbsPanel(1920, 1080)
        assertEquals(0, left.x)
        assertEquals(left.right, right.x)
        assertEquals(panelWidth, right.right)
        assertEquals(panelHeight, left.top)
        assertEquals(panelHeight, right.top)
    }

    @Test
    fun `which packing a pair is, is read off its width`() {
        // Full width is two desktops wide — the stereo panel's own 2.5:1 threshold, so there is
        // one notion of "two eyes wide" in the app, not two. Half width is the desktop's own.
        assertEquals(Shape(1920, 1080), PcLinkFrameLayout.eyeShape(3840, 1080))
        assertEquals(Shape(1920, 1080), PcLinkFrameLayout.eyeShape(1920, 1080))
        assertEquals(Shape(1728, 1080), PcLinkFrameLayout.eyeShape(3456, 1080))
        assertEquals(Shape(1728, 1080), PcLinkFrameLayout.eyeShape(1728, 1080))
        // 21:9 sits under the threshold at half width (2.37) and well over it at full (4.74).
        assertEquals(Shape(2560, 1080), PcLinkFrameLayout.eyeShape(2560, 1080))
        assertEquals(Shape(2560, 1080), PcLinkFrameLayout.eyeShape(5120, 1080))
    }

    @Test
    fun `the known misread - a 32 by 9 desktop cast at half width is drawn as a full-width pair`() {
        // A 5120×1440 frame is either a 32:9 super-ultrawide squeezed to half width or a 16:9
        // desktop at full width, and nothing on the wire says which. It is read as the latter:
        // two 16:9 eyes, filled, the ultrawide squeezed into each. This is a documented
        // expectation, not a target — the price of the layout not being in `config`, and the
        // case that goes away the day it is.
        assertEquals(Shape(2560, 1440), PcLinkFrameLayout.eyeShape(5120, 1440))
        assertEquals(listOf(leftEye, rightEye), onSbsPanel(5120, 1440))
    }

    // --- mono: the frame is the desktop, and keeps its shape ------------------------------------

    @Test
    fun `a 16 by 10 desktop is letterboxed in mono, as it was`() {
        // The glasses in 2D are an ordinary 1920×1080 display. A 16:10 Mac fits at 1728 wide with
        // 96 px of black either side — `20acddc` — and must not be squashed to fill.
        assertEquals(
            listOf(Rect(96, 0, 1728, 1080)),
            PcLinkFrameLayout.eyeRects(1920, 1200, sbs = false, panelWidth = 1920, panelHeight = 1080)
        )
    }

    @Test
    fun `a mono frame the panel's own shape fills it`() {
        assertEquals(
            listOf(Rect(0, 0, 1920, 1080)),
            PcLinkFrameLayout.eyeRects(1920, 1080, sbs = false, panelWidth = 1920, panelHeight = 1080)
        )
        // …at any scale: a stream sent smaller than the panel is the same shape.
        assertEquals(
            listOf(Rect(0, 0, 1920, 1080)),
            PcLinkFrameLayout.eyeRects(1280, 720, sbs = false, panelWidth = 1920, panelHeight = 1080)
        )
    }

    @Test
    fun `a wider panel pillarboxes, a taller one letterboxes`() {
        // The phone-local fallback: a 16:9 desktop on a 20:9 phone screen sits centred.
        assertEquals(
            listOf(Rect(240, 0, 1920, 1080)),
            PcLinkFrameLayout.eyeRects(1920, 1080, sbs = false, panelWidth = 2400, panelHeight = 1080)
        )
        // A 16:9 desktop on a 16:10 panel: 60 px of black top and bottom.
        assertEquals(
            listOf(Rect(0, 60, 1920, 1080)),
            PcLinkFrameLayout.eyeRects(1920, 1080, sbs = false, panelWidth = 1920, panelHeight = 1200)
        )
    }

    @Test
    fun `a packed stream on a flat panel is shown as it is`() {
        // Glasses in 2D, or the moment before the PC hears the panel changed: the whole 32:9
        // pair, both halves visible, fitted to its own shape — the behaviour it always had. Only
        // a stereo panel makes halves into eyes.
        assertEquals(
            listOf(Rect(0, 270, 1920, 540)),
            PcLinkFrameLayout.eyeRects(3840, 1080, sbs = false, panelWidth = 1920, panelHeight = 1080)
        )
    }

    @Test
    fun `a frame with no size yet fills the panel rather than dividing by zero`() {
        assertEquals(
            listOf(Rect(0, 0, 1920, 1080)),
            PcLinkFrameLayout.eyeRects(0, 0, sbs = false, panelWidth = 1920, panelHeight = 1080)
        )
        assertEquals(
            listOf(Rect(0, 0, 1920, 1080)),
            PcLinkFrameLayout.eyeRects(1920, 0, sbs = false, panelWidth = 1920, panelHeight = 1080)
        )
        assertEquals(
            listOf(leftEye, rightEye),
            PcLinkFrameLayout.eyeRects(0, 0, sbs = true, panelWidth = 3840, panelHeight = 1080)
        )
    }
}
