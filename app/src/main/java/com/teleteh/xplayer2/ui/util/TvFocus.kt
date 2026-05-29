package com.teleteh.xplayer2.ui.util

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.teleteh.xplayer2.R

/**
 * Helpers to make D-pad / TV navigation usable and visible. The platform's default focus
 * highlight is too subtle (and custom menu items built from plain TextViews aren't even
 * focusable), so on a TV/box without a touchscreen the selection looks frozen.
 */
object TvFocus {

    /** Draw a bright focus ring (foreground) over a control so D-pad selection is obvious. */
    fun ring(v: View) {
        v.foreground = ContextCompat.getDrawable(v.context, R.drawable.tv_focus_ring)
    }

    /** Recursively apply [ring] to every Button / ImageButton under [root]. */
    fun applyToButtons(root: View) {
        if (root is Button || root is ImageButton) ring(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyToButtons(root.getChildAt(i))
        }
    }

    /** Make a custom menu item (e.g. a TextView) reachable by D-pad and show focus on it. */
    fun makeFocusableItem(v: View) {
        v.isFocusable = true
        v.isFocusableInTouchMode = false
        ring(v)
    }
}
