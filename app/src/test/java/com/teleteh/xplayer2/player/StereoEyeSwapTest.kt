package com.teleteh.xplayer2.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which half of a packed frame each eye is shown, with and without the swap — pinned here because
 * the renderer that acts on these answers is GL all the way down and cannot be asked on the JVM.
 *
 * The defaults are what has always shipped: they are asserted so the switch can only ever
 * *invert* the picture, never move it.
 */
class StereoEyeSwapTest {

    // --- side-by-side ---------------------------------------------------------------------------

    @Test
    fun `unswapped, each eye takes its own half of a side-by-side frame`() {
        assertFalse(StereoEyeSwap.sbsUseRightHalf(leftEye = true, swap = false))
        assertTrue(StereoEyeSwap.sbsUseRightHalf(leftEye = false, swap = false))
    }

    @Test
    fun `swapped, each eye takes the other eye's half`() {
        assertTrue(StereoEyeSwap.sbsUseRightHalf(leftEye = true, swap = true))
        assertFalse(StereoEyeSwap.sbsUseRightHalf(leftEye = false, swap = true))
    }

    // --- over-under -----------------------------------------------------------------------------

    /**
     * In the renderer's own terms: the half at texture offset ½ (its "top") has always gone to the
     * right eye and the other half to the left. The switch exchanges them and changes nothing else.
     */
    @Test
    fun `over-under keeps the halves it has always given each eye, and exchanges them when swapped`() {
        assertFalse(StereoEyeSwap.ouFromTopHalf(leftEye = true, swap = false))
        assertTrue(StereoEyeSwap.ouFromTopHalf(leftEye = false, swap = false))
        assertTrue(StereoEyeSwap.ouFromTopHalf(leftEye = true, swap = true))
        assertFalse(StereoEyeSwap.ouFromTopHalf(leftEye = false, swap = true))
    }

    // --- Lazy 3D --------------------------------------------------------------------------------

    /** −1 is the view synthesised for the left eye; swapped, each eye is shown the other's view. */
    @Test
    fun `lazy 3D swaps the two synthesised views`() {
        assertEquals(-1f, StereoEyeSwap.lazy3dEyeSign(leftEye = true, swap = false), 0f)
        assertEquals(1f, StereoEyeSwap.lazy3dEyeSign(leftEye = false, swap = false), 0f)
        assertEquals(1f, StereoEyeSwap.lazy3dEyeSign(leftEye = true, swap = true), 0f)
        assertEquals(-1f, StereoEyeSwap.lazy3dEyeSign(leftEye = false, swap = true), 0f)
    }

    /** A swap is an exchange: whatever the state, the two eyes are never shown the same thing. */
    @Test
    fun `the two eyes never get the same half or the same view`() {
        for (swap in listOf(false, true)) {
            assertNotEquals(
                StereoEyeSwap.sbsUseRightHalf(leftEye = true, swap),
                StereoEyeSwap.sbsUseRightHalf(leftEye = false, swap)
            )
            assertNotEquals(
                StereoEyeSwap.ouFromTopHalf(leftEye = true, swap),
                StereoEyeSwap.ouFromTopHalf(leftEye = false, swap)
            )
            assertNotEquals(
                StereoEyeSwap.lazy3dEyeSign(leftEye = true, swap),
                StereoEyeSwap.lazy3dEyeSign(leftEye = false, swap)
            )
        }
    }

    // --- what the renderer is handed ------------------------------------------------------------

    @Test
    fun `a film gets the remembered preference`() {
        assertTrue(StereoEyeSwap.rendererFlag(pcLink = false, preference = true))
        assertFalse(StereoEyeSwap.rendererFlag(pcLink = false, preference = false))
    }

    /**
     * The owner's rule: the switch is the player's. A person who set it for their films must not
     * get a swapped desktop, so a cast ignores the preference even while it is on.
     */
    @Test
    fun `a PC Link cast ignores the preference, even when it is on`() {
        assertFalse(StereoEyeSwap.rendererFlag(pcLink = true, preference = true))
        assertFalse(StereoEyeSwap.rendererFlag(pcLink = true, preference = false))
    }

    // --- the toolbar switch ---------------------------------------------------------------------

    /** Shown only while the glasses-mode action beside it reads "3D …"; in 2D, or with no glasses, nothing. */
    @Test
    fun `the switch is on screen only while the glasses read 3D`() {
        assertTrue(StereoEyeSwap.toggleShown(glassesSwitchable = true, in3d = true))
        assertFalse("a 2D panel has no eyes to swap", StereoEyeSwap.toggleShown(glassesSwitchable = true, in3d = false))
        assertFalse("no glasses, no switch", StereoEyeSwap.toggleShown(glassesSwitchable = false, in3d = true))
        assertFalse(StereoEyeSwap.toggleShown(glassesSwitchable = false, in3d = false))
    }
}
