package com.teleteh.xplayer2.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent

/**
 * Mirrors its content side-by-side across a wide (e.g., 32:9) screen for SBS UI.
 * - Draws the child twice: left and right halves.
 * - Remaps touch input from the right half into the left-half coordinates.
 * - Activates only when stereo_sbs preference is true and aspect ratio is ultra-wide.
 *
 * Assumes a single child (like FrameLayout).
 */
class SbsMirrorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private var isUltraWide = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var overlay: MirrorOverlayView? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        if (overlay == null) {
            overlay = MirrorOverlayView(context)
            addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        isUltraWide = w > 0 && h > 0 && w.toFloat() / h.toFloat() >= 3.2f
        val child = contentChild()
        if (child != null) {
            if (shouldMirror(w, h)) {
                val halfSpec = MeasureSpec.makeMeasureSpec(w / 2, MeasureSpec.EXACTLY)
                measureChildWithMargins(child, halfSpec, 0, heightMeasureSpec, 0)
            } else {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            }
        }
        // Overlay always matches parent
        overlay?.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = contentChild() ?: return
        val w = r - l
        val h = b - t
        if (shouldMirror(w, h)) {
            val left = paddingLeft
            val top = paddingTop
            val right = left + (w / 2) - paddingRight
            val bottom = h - paddingBottom
            child.layout(left, top, right, bottom)
        } else {
            child.layout(paddingLeft, paddingTop, w - paddingRight, h - paddingBottom)
        }
        overlay?.layout(0, 0, w, h)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Default drawing: content child + overlay child handles mirroring
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        // When mirroring, any change on the left needs a redraw on the right half too
        if (shouldMirror(width, height)) {
            val half = width / 2
            invalidate(half, 0, width, height)
            overlay?.invalidate()
        }
    }

    override fun invalidateChildInParent(location: IntArray?, dirty: Rect?): ViewParent? {
        val parent = super.invalidateChildInParent(location, dirty)
        if (dirty != null && shouldMirror(width, height)) {
            // Mirror the dirty rect to the right side
            val half = width / 2
            val mirrored = Rect(dirty)
            mirrored.offset(half, 0)
            invalidate(mirrored)
        }
        return parent
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val w = width
        val h = height
        if (!shouldMirror(w, h)) return super.dispatchTouchEvent(ev)
        val half = w / 2
        val inRight = ev.x >= half
        val child = contentChild() ?: return super.dispatchTouchEvent(ev)
        return if (!inRight) {
            // Left half: pass through as-is
            super.dispatchTouchEvent(ev)
        } else {
            // Right half: remap X coords by subtracting half width for all pointers
            val pointerCount = ev.pointerCount
            val props = arrayOfNulls<MotionEvent.PointerProperties>(pointerCount)
            val coords = arrayOfNulls<MotionEvent.PointerCoords>(pointerCount)
            for (i in 0 until pointerCount) {
                val p = MotionEvent.PointerProperties()
                ev.getPointerProperties(i, p)
                props[i] = p
                val c = MotionEvent.PointerCoords()
                ev.getPointerCoords(i, c)
                c.x -= half
                coords[i] = c
            }
            val remapped = MotionEvent.obtain(
                ev.downTime,
                ev.eventTime,
                ev.action,
                pointerCount,
                props.requireNoNulls(),
                coords.requireNoNulls(),
                ev.metaState,
                ev.buttonState,
                ev.xPrecision,
                ev.yPrecision,
                ev.deviceId,
                ev.edgeFlags,
                ev.source,
                ev.flags
            )
            val handled = super.dispatchTouchEvent(remapped)
            remapped.recycle()
            handled
        }
    }

    private fun shouldMirror(w: Int, h: Int): Boolean {
        // Mirror UI only on ultra-wide screens, independent of SBS toggle
        if (w <= 0 || h <= 0) return false
        isUltraWide = w.toFloat() / h.toFloat() >= 3.2f
        return isUltraWide
    }

    private fun contentChild(): View? {
        val n = childCount
        for (i in 0 until n) {
            val c = getChildAt(i)
            if (c !== overlay) return c
        }
        return null
    }

    private inner class MirrorOverlayView(context: Context) : View(context) {
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)

        init {
            // Minimal non-transparent background so window doesn't treat area as transparent
            setBackgroundColor(0x01000000)
        }

        override fun onDraw(canvas: Canvas) {
            val parent = this@SbsMirrorLayout
            val w = parent.width
            val h = parent.height
            if (!parent.shouldMirror(w, h)) return
            val content = parent.contentChild() ?: return
            val half = w / 2
            // Draw the content directly onto the window canvas on the right half.
            // This keeps rendering on the hardware canvas and avoids SW bitmap usage.
            val save = canvas.save()
            canvas.clipRect(half, 0, w, h)
            canvas.translate(half - content.left.toFloat(), -content.top.toFloat())
            content.draw(canvas)
            canvas.restoreToCount(save)
        }

        override fun gatherTransparentRegion(region: Region?): Boolean =
            super.gatherTransparentRegion(region)
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams {
        return MarginLayoutParams(context, attrs)
    }

    override fun generateDefaultLayoutParams(): LayoutParams {
        return MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    override fun checkLayoutParams(p: LayoutParams?): Boolean {
        return p is MarginLayoutParams
    }
}
