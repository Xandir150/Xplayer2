package com.teleteh.xplayer2.ui

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.color.MaterialColors
import com.teleteh.xplayer2.ui.util.TvFocus

/**
 * An alert dialog that is readable on a glasses panel in 3D mode.
 *
 * A system dialog is a window of its own. [SbsMirrorLayout] mirrors the activity's content, but it
 * cannot reach into another window, so on a 32:9 screen a stock dialog stretches across both eyes.
 * This builder has the same fluent calls as the ones the app uses on `AlertDialog.Builder`. On a
 * normal screen it shows a normal `AlertDialog`. On an ultra-wide screen it builds its own dialog
 * with the content inside a [SbsMirrorLayout], so each eye gets a complete copy.
 */
object Sbs3dDialog {

    /** True when [context] is on a screen wide enough that the panel is in side-by-side mode. */
    fun isUltraWide(context: Context): Boolean {
        val m = context.resources.displayMetrics
        return m.heightPixels > 0 && m.widthPixels.toFloat() / m.heightPixels >= 3.2f
    }

    fun builder(context: Context) = Builder(context)

    class Builder(private val context: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var view: View? = null
        private var items: Array<out CharSequence>? = null
        private var itemListener: DialogInterface.OnClickListener? = null
        private var positive: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
        private var negative: Pair<CharSequence, DialogInterface.OnClickListener?>? = null
        private var onDismiss: DialogInterface.OnDismissListener? = null

        fun setTitle(t: CharSequence?) = apply { title = t }
        fun setTitle(resId: Int) = apply { title = context.getString(resId) }
        fun setMessage(m: CharSequence?) = apply { message = m }
        fun setMessage(resId: Int) = apply { message = context.getString(resId) }
        fun setView(v: View?) = apply { view = v }
        fun setItems(labels: Array<out CharSequence>, l: DialogInterface.OnClickListener) =
            apply { items = labels; itemListener = l }

        fun setPositiveButton(text: CharSequence, l: DialogInterface.OnClickListener?) =
            apply { positive = text to l }
        fun setPositiveButton(resId: Int, l: DialogInterface.OnClickListener?) =
            setPositiveButton(context.getString(resId), l)
        fun setNegativeButton(text: CharSequence, l: DialogInterface.OnClickListener?) =
            apply { negative = text to l }
        fun setNegativeButton(resId: Int, l: DialogInterface.OnClickListener?) =
            setNegativeButton(context.getString(resId), l)
        fun setOnDismissListener(l: DialogInterface.OnDismissListener?) = apply { onDismiss = l }

        fun show(): Dialog = if (isUltraWide(context)) showMirrored() else showSystem()

        private fun showSystem(): Dialog {
            val b = AlertDialog.Builder(context)
            title?.let { b.setTitle(it) }
            message?.let { b.setMessage(it) }
            view?.let { b.setView(it) }
            items?.let { labels -> itemListener?.let { b.setItems(labels, it) } }
            positive?.let { b.setPositiveButton(it.first, it.second) }
            negative?.let { b.setNegativeButton(it.first, it.second) }
            b.setOnDismissListener(onDismiss)
            return b.show()
        }

        private fun showMirrored(): Dialog {
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            val dm = context.resources.displayMetrics
            fun dp(v: Int) = (v * dm.density).toInt()
            val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.rgb(32, 32, 36))
            val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
            val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.rgb(120, 170, 255))

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(20), dp(24), dp(12))
                background = GradientDrawable().apply {
                    setColor(surface)
                    cornerRadius = dp(16).toFloat()
                }
            }
            title?.let {
                card.addView(TextView(context).apply {
                    text = it
                    textSize = 20f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(onSurface)
                    setPadding(0, 0, 0, dp(12))
                })
            }
            message?.let {
                card.addView(TextView(context).apply {
                    text = it
                    textSize = 16f
                    setTextColor(onSurface)
                    setPadding(0, 0, 0, dp(12))
                })
            }
            var firstFocus: View? = null
            view?.let { v ->
                (v.parent as? ViewGroup)?.removeView(v)
                card.addView(v, LinearLayout.LayoutParams(-1, -2))
            }
            items?.let { labels ->
                val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                labels.forEachIndexed { i, label ->
                    val row = TextView(context).apply {
                        text = label
                        textSize = 16f
                        setTextColor(onSurface)
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        isClickable = true
                        setOnClickListener {
                            itemListener?.onClick(dialog, i)
                            dialog.dismiss()
                        }
                    }
                    TvFocus.makeFocusableItem(row)
                    if (firstFocus == null) firstFocus = row
                    list.addView(row)
                }
                val scroll = ScrollView(context).apply { addView(list) }
                card.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
            fun button(spec: Pair<CharSequence, DialogInterface.OnClickListener?>, which: Int) {
                val btn = TextView(context).apply {
                    text = spec.first
                    textSize = 16f
                    setTextColor(accent)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    isClickable = true
                    setOnClickListener {
                        spec.second?.onClick(dialog, which)
                        dialog.dismiss()
                    }
                }
                TvFocus.makeFocusableItem(btn)
                if (firstFocus == null && view == null) firstFocus = btn
                buttons.addView(btn)
            }
            negative?.let { button(it, DialogInterface.BUTTON_NEGATIVE) }
            positive?.let { button(it, DialogInterface.BUTTON_POSITIVE) }
            if (buttons.childCount > 0) card.addView(buttons, LinearLayout.LayoutParams(-1, -2))

            // One eye is half the screen: size the card against that, not the full width.
            val eyeWidth = dm.widthPixels / 2
            val frame = FrameLayout(context)
            frame.addView(
                card,
                FrameLayout.LayoutParams((eyeWidth * 0.8f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
            val mirror = SbsMirrorLayout(context)
            mirror.addView(frame, ViewGroup.LayoutParams(-1, -1))
            mirror.attachOverlay()
            dialog.setContentView(mirror, ViewGroup.LayoutParams(-1, -1))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(0x99000000.toInt()))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
            dialog.setCanceledOnTouchOutside(true)
            dialog.setOnDismissListener(onDismiss)
            dialog.show()
            firstFocus?.requestFocus()
            return dialog
        }
    }
}
