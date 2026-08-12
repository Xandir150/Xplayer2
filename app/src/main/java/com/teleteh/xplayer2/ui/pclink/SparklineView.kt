package com.teleteh.xplayer2.ui.pclink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * A minute of a number, under the number.
 *
 * Deliberately small, unlabelled and unscaled: the chip above it already says what the value is,
 * and this is here to say "steady" or "it just dipped". No axes, no ticks, nothing to read off.
 *
 * Hand-drawn — one [Path] in [onDraw] — rather than a charting dependency: the whole of the
 * geometry is [SparklineWindow], which is a hundred lines and testable on the JVM, and a chart
 * library would bring a theme of its own to argue with.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var window: SparklineWindow? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = dp(1.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val linePath = Path()
    private val fillPath = Path()
    private val corner = dp(4f)
    private val dotRadius = dp(2f)

    init {
        // colorPrimary is appcompat's attribute (Material re-themes it but doesn't declare it).
        val accent = MaterialColors.getColor(
            this, androidx.appcompat.R.attr.colorPrimary, Color.GRAY
        )
        linePaint.color = accent
        dotPaint.color = accent
        fillPaint.color = withAlpha(accent, 0.28f)
        // A track, so an empty or flat chart still reads as a place where a line goes rather than
        // as a gap in the layout.
        trackPaint.color = withAlpha(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.GRAY),
            0.10f
        )
        // The chip states the number in words; a second, wordier reading of the same data would
        // only make TalkBack slower.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Overrides what the track is drawn against.
     *
     * The default reads `colorOnSurface` off the theme, which is right inside an ordinary screen
     * that follows the system's light/dark setting. The remote is not one of those: it paints its
     * own near-black card whatever the phone is set to, and in light mode a track tinted for a
     * white surface disappears into it. So the host that knows its own surface says so.
     */
    fun setSurfaceColor(color: Int) {
        trackPaint.color = withAlpha(color, 0.10f)
        invalidate()
    }

    /** Points this view at a window and redraws. The window goes on being filled by its owner. */
    fun setWindow(window: SparklineWindow) {
        this.window = window
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRoundRect(0f, 0f, w, h, corner, corner, trackPaint)

        val window = window ?: return
        val samples = window.samples()
        if (samples.isEmpty()) return

        // Inset by half the stroke so a value at the very top or bottom isn't clipped in half, and
        // by the same at the sides so the newest sample's round cap stays inside the box.
        val inset = linePaint.strokeWidth / 2f
        val top = inset
        val bottom = h - inset
        val range = window.range()

        fun xOf(index: Int) = inset + window.xFraction(index) * (w - 2f * inset)
        fun yOf(value: Float) = bottom - window.yFraction(value, range) * (bottom - top)

        if (samples.size == 1) {
            // One sample is not a line. A dot against the right edge is honest about that, and
            // about which way the next one will grow.
            canvas.drawCircle(xOf(0) - dotRadius, yOf(samples[0]), dotRadius, dotPaint)
            return
        }

        linePath.rewind()
        fillPath.rewind()
        for (i in samples.indices) {
            val x = xOf(i)
            val y = yOf(samples[i])
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(xOf(samples.lastIndex), h)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun withAlpha(color: Int, fraction: Float): Int =
        Color.argb((255 * fraction).toInt(), Color.red(color), Color.green(color), Color.blue(color))
}
