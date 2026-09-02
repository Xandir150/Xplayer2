package com.teleteh.xplayer2.player

import android.content.Context

/**
 * Swapping the eyes.
 *
 * Beta users with an inverted setup — glasses, or a film, that put the left eye's image on the
 * right — asked for a switch. It is a renderer flag, not a change to the source, and the whole of
 * it is: each physical eye samples the *other* half of the packed frame (or, under Lazy 3D, is
 * shown the view synthesised for the other eye). The draw per eye is otherwise untouched — the
 * left eye is still drawn into the left half of the panel, with its own vertical shift — so
 * nothing is re-prepared and the next frame is simply the other way round.
 *
 * Remembered phone-wide, because a setup that is inverted is inverted for every film, so it is
 * set once: the switch is in the main screen's toolbar, next to the glasses-mode action, and only
 * while that action reads "3D …" — on a 2D panel there are no eyes to swap. It is the *player's*
 * switch — SBS and OU files, and Lazy 3D — and never applies to a PC Link cast: a person who set
 * it for their films must not get a swapped desktop.
 *
 * Pure so it can be argued with in a test; `OuToSbsGlView` is GL all the way down and takes these
 * answers as they are.
 */
object StereoEyeSwap {

    /** The preferences file and key the choice is remembered under. */
    const val PREFS = "display"
    const val KEY = "swap_eyes"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /**
     * SBS source — `useRightHalf` for the renderer's `drawEyeFromSbs`: the left eye takes the
     * left half and the right eye the right, unless swapped.
     */
    fun sbsUseRightHalf(leftEye: Boolean, swap: Boolean): Boolean = leftEye == swap

    /**
     * OU source — `fromTopHalf` for the renderer's `drawEyeFromOu`, in its own terms: the half at
     * texture offset ½, which the right eye has always been given; swapped, the left eye gets it.
     */
    fun ouFromTopHalf(leftEye: Boolean, swap: Boolean): Boolean = leftEye == swap

    /**
     * Lazy 3D — the sign of the depth warp's disparity, −1 being the view synthesised for the
     * left eye; swapped, each eye is shown the other's view.
     */
    fun lazy3dEyeSign(leftEye: Boolean, swap: Boolean): Float = if (leftEye == swap) 1f else -1f

    /**
     * What the renderer is actually handed: the remembered choice, except during a PC Link cast,
     * where it is always off.
     */
    fun rendererFlag(pcLink: Boolean, preference: Boolean): Boolean = preference && !pcLink

    /**
     * Whether the toolbar switch is on screen at all: only while the glasses-mode action beside it
     * would read "3D …" — glasses this app can switch are connected, and they are in a 3D mode.
     * In 2D, or with no glasses, the toolbar must not grow a button.
     */
    fun toggleShown(glassesSwitchable: Boolean, in3d: Boolean): Boolean = glassesSwitchable && in3d
}
