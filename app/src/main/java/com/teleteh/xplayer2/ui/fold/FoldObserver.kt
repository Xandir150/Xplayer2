package com.teleteh.xplayer2.ui.fold

import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.launch

internal fun observeFold(activity: ComponentActivity, changed: (FoldingFeature?) -> Unit) {
    activity.lifecycleScope.launch {
        activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).collect { info ->
                changed(info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull {
                    it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL
                })
            }
        }
    }
}

internal fun View.foldPanes(fold: FoldingFeature?): FoldGeometry.Panes? {
    fold ?: return null
    val origin = IntArray(2)
    getLocationInWindow(origin)
    val bounds = fold.bounds
    return FoldGeometry.split(
        FoldGeometry.Box(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom),
        FoldGeometry.Box(bounds.left - origin[0], bounds.top - origin[1],
            bounds.right - origin[0], bounds.bottom - origin[1]),
        fold.orientation == FoldingFeature.Orientation.HORIZONTAL,
    )
}
