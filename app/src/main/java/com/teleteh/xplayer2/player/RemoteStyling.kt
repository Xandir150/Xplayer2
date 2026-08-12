package com.teleteh.xplayer2.player

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.teleteh.xplayer2.R

/**
 * The look the two remotes share.
 *
 * [RemoteControlActivity] is the original and this is lifted from it, so the PC Link remote is
 * visibly the same object with different buttons on it rather than a second designer's idea of one:
 * the same tonal row colour, the same accent for "this is on", the same focus ring.
 */
object RemoteStyling {

    /**
     * Draws a bright focus ring over every button in the tree so D-pad / TV selection is visible —
     * the platform's default highlight is far too subtle on a dark remote, and these screens are
     * routinely driven from a TV box with no touchscreen at all.
     */
    fun applyTvFocusHighlight(context: Context, view: View) {
        if (view is MaterialButton || view is ImageButton) {
            view.foreground = ContextCompat.getDrawable(context, R.drawable.tv_focus_ring)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTvFocusHighlight(context, view.getChildAt(i))
        }
    }

    /** Same, rooted at the activity's content view. */
    fun applyTvFocusHighlight(activity: Activity) =
        applyTvFocusHighlight(activity, activity.findViewById(android.R.id.content))

    fun themeColor(context: Context, attr: Int): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(attr, out, true)
        return out.data
    }

    /**
     * A toggle row's two states: active is filled with the theme accent (Material You on Android
     * 12+, else brand purple), inactive is the same tonal colour the non-toggle rows use, so a card
     * of buttons reads as one surface with one of them lit.
     */
    fun applyToggleStyle(button: MaterialButton, checked: Boolean) {
        val context = button.context
        button.strokeWidth = 0
        if (checked) {
            button.backgroundTintList =
                ColorStateList.valueOf(themeColor(context, androidx.appcompat.R.attr.colorPrimary))
            button.setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnPrimary))
        } else {
            button.backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.rc_row))
            button.setTextColor(context.getColor(R.color.rc_on_surface))
        }
    }
}
