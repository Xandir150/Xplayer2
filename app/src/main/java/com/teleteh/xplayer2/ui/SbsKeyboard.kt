package com.teleteh.xplayer2.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.teleteh.xplayer2.ui.util.TvFocus

/**
 * An on-screen keyboard that lives inside the view tree.
 *
 * The system keyboard is a window of its own, so [SbsMirrorLayout] cannot draw it once per eye: on
 * a 32:9 panel it spreads over both. This one is ordinary views, so the mirror copies it, and every
 * key is focusable, so a remote or the head-gesture D-pad can press it. Keys write into the last
 * [EditText] that had focus, which is why pressing a key (focus moves to it) does not lose the field.
 */
class SbsKeyboard(context: Context) : LinearLayout(context) {

    private enum class Page(val rows: List<String>) {
        EN(listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm")),
        RU(listOf("1234567890", "йцукенгшщзх", "фывапролджэ", "ячсмитьбю")),
        SYM(listOf("1234567890", "@#$%&*-+=/", "()[]{}<>_\\", ".,:;!?'\"~"));
    }

    private var page = Page.EN
    private var shift = false
    private var target: EditText? = null
    private val dm = context.resources.displayMetrics

    init {
        orientation = VERTICAL
        render()
    }

    /** Route typing to the fields under [root] and keep the system keyboard from opening for them. */
    fun attachTo(root: View) {
        fun walk(v: View) {
            if (v is EditText) {
                v.showSoftInputOnFocus = false
                v.setOnFocusChangeListener { _, has -> if (has) target = v }
                if (target == null) target = v
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
    }

    private fun dp(v: Int) = (v * dm.density).toInt()

    private fun render() {
        removeAllViews()
        page.rows.forEachIndexed { i, row ->
            val line = row()
            row.forEach { c -> line.addView(key(label(c.toString())) { type(label(c.toString())) }, keyLp(1f)) }
            if (i == page.rows.lastIndex) line.addView(key("⌫") { backspace() }, keyLp(1.5f))
            addView(line)
        }
        val bottom = row()
        bottom.addView(key(if (shift) "⇧•" else "⇧") { shift = !shift; render() }, keyLp(1.5f))
        bottom.addView(
            key(if (page == Page.SYM) "ABC" else "?#+") {
                page = if (page == Page.SYM) Page.EN else Page.SYM; render()
            }, keyLp(1.5f)
        )
        bottom.addView(
            key(if (page == Page.RU) "EN" else "RU") {
                page = if (page == Page.RU) Page.EN else Page.RU; render()
            }, keyLp(1.5f)
        )
        bottom.addView(key("␣") { type(" ") }, keyLp(5f))
        bottom.addView(key(".") { type(".") }, keyLp(1f))
        addView(bottom)
    }

    private fun label(c: String) = if (shift && page != Page.SYM) c.uppercase() else c

    private fun row() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun keyLp(weight: Float) = LayoutParams(0, dp(44), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun key(text: String, onPress: () -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            setColor(0xFF3A3A40.toInt())
            cornerRadius = dp(6).toFloat()
        }
        isClickable = true
        setOnClickListener { onPress() }
        TvFocus.makeFocusableItem(this)
    }

    private fun type(s: String) {
        val e = target ?: return
        val start = e.selectionStart.coerceAtLeast(0)
        val end = e.selectionEnd.coerceAtLeast(0)
        e.text.replace(minOf(start, end), maxOf(start, end), s)
        // One capital per press, like a phone keyboard.
        if (shift) { shift = false; render() }
    }

    private fun backspace() {
        val e = target ?: return
        val start = e.selectionStart.coerceAtLeast(0)
        val end = e.selectionEnd.coerceAtLeast(0)
        if (start != end) e.text.delete(minOf(start, end), maxOf(start, end))
        else if (start > 0) e.text.delete(start - 1, start)
    }
}
