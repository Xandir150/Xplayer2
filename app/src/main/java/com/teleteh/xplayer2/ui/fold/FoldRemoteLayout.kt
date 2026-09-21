package com.teleteh.xplayer2.ui.fold

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.window.layout.FoldingFeature

/** Header and touchpad in one pane; scrollable settings in the other. */
class FoldRemoteLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private var fold: FoldingFeature? = null
    private var panes: FoldGeometry.Panes? = null

    fun observe(activity: ComponentActivity) {
        observeFold(activity) { fold = it; requestLayout() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Use the new measured size during window resizing, before layout updates width/height.
        val origin = IntArray(2)
        getLocationInWindow(origin)
        panes = fold?.let { feature ->
            val b = feature.bounds
            FoldGeometry.split(
                FoldGeometry.Box(paddingLeft, paddingTop, measuredWidth - paddingRight, measuredHeight - paddingBottom),
                FoldGeometry.Box(b.left - origin[0], b.top - origin[1], b.right - origin[0], b.bottom - origin[1]),
                feature.orientation == FoldingFeature.Orientation.HORIZONTAL,
            )
        }
        val split = panes ?: return
        if (childCount < 3) return
        val header = getChildAt(0)
        header.measure(MeasureSpec.makeMeasureSpec(split.first.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(split.first.height, MeasureSpec.AT_MOST))
        measurePane(getChildAt(1), split.first.width, split.first.height - header.measuredHeight)
        var available = split.second.height
        for (index in 2 until childCount - 1) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as LayoutParams
            child.measure(MeasureSpec.makeMeasureSpec((split.second.width - lp.leftMargin - lp.rightMargin).coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((available - lp.topMargin - lp.bottomMargin).coerceAtLeast(0), MeasureSpec.AT_MOST))
            available -= child.measuredHeight + lp.topMargin + lp.bottomMargin
        }
        measurePane(getChildAt(childCount - 1), split.second.width, available)
    }

    private fun measurePane(view: View, width: Int, height: Int) {
        val lp = view.layoutParams as LayoutParams
        view.measure(MeasureSpec.makeMeasureSpec((width - lp.leftMargin - lp.rightMargin).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((height - lp.topMargin - lp.bottomMargin).coerceAtLeast(0), MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val split = panes
        if (split == null || childCount < 3) { super.onLayout(changed, l, t, r, b); return }
        val header = getChildAt(0)
        header.layout(split.first.left, split.first.top, split.first.right, split.first.top + header.measuredHeight)
        place(getChildAt(1), split.first.left, split.first.top + header.measuredHeight)
        var top = split.second.top
        for (index in 2 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            place(child, split.second.left, top)
            val lp = child.layoutParams as LayoutParams
            top += child.measuredHeight + lp.topMargin + lp.bottomMargin
        }
    }

    private fun place(view: View, left: Int, top: Int) {
        val lp = view.layoutParams as LayoutParams
        val x = left + lp.leftMargin
        val y = top + lp.topMargin
        view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
    }
}
