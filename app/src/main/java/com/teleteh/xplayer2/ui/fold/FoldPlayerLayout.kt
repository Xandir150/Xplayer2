package com.teleteh.xplayer2.ui.fold

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import com.teleteh.xplayer2.R
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.window.layout.FoldingFeature

/** Adapts only the Activity's local video. Never attach this to an external Presentation. */
@UnstableApi
class FoldPlayerLayout(
    private val activity: ComponentActivity,
    private val root: View,
    private val playerView: PlayerView,
    private val glView: View?,
    private val enabled: () -> Boolean,
) {
    private var fold: FoldingFeature? = null
    private var wasSplit = false
    private var normalTimeout = 3000
    private var normalHideOnTouch = true
    private val content = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
    // Keep the original aspect-ratio layout centered inside a bounded pane. PlayerView retains
    // its references and all views stay in its subtree, including the custom control overlay.
    private val videoPane = FrameLayout(activity).also { host ->
        val index = playerView.indexOfChild(content)
        val params = content.layoutParams
        playerView.removeView(content)
        playerView.addView(host, index, FrameLayout.LayoutParams(-1, -1))
        host.addView(content, params)
    }
    private val controls = listOfNotNull(
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller),
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_overlay),
    )
    private val originals = (listOfNotNull(glView, videoPane) + controls).associateWith {
        FrameLayout.LayoutParams(it.layoutParams as FrameLayout.LayoutParams)
    }

    fun start() {
        // In book posture a pane can be narrower than the custom action row. Keep every
        // action reachable without reducing touch targets or clipping the trailing buttons.
        val bar = playerView.findViewById<View>(R.id.controlsTopBar)
        val overlay = bar?.parent as? FrameLayout
        if (overlay != null) {
            val index = overlay.indexOfChild(bar)
            val params = bar.layoutParams
            overlay.removeView(bar)
            val scroll = HorizontalScrollView(activity).apply {
                isFillViewport = true
                isHorizontalScrollBarEnabled = false
            }
            overlay.addView(scroll, index, params)
            scroll.addView(bar, FrameLayout.LayoutParams(-2, -1))
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refresh() }
        observeFold(activity) { fold = it; refresh() }
    }

    fun refresh() {
        val panes = if (enabled()) root.foldPanes(fold) else null
        if (panes == null) {
            if (!wasSplit) return
            originals.forEach { (view, params) -> view.layoutParams = FrameLayout.LayoutParams(params) }
            playerView.controllerShowTimeoutMs = normalTimeout
            playerView.controllerHideOnTouch = normalHideOnTouch
            playerView.showController()
        } else {
            glView?.placeIn(panes.first)
            videoPane.placeIn(panes.first)
            controls.forEach { it.placeIn(panes.second) }
            if (!wasSplit) {
                normalTimeout = playerView.controllerShowTimeoutMs
                normalHideOnTouch = playerView.controllerHideOnTouch
                playerView.controllerShowTimeoutMs = 0
                playerView.controllerHideOnTouch = false
                playerView.showController()
            }
        }
        wasSplit = panes != null
    }

    private fun View.placeIn(box: FoldGeometry.Box) {
        val params = layoutParams as FrameLayout.LayoutParams
        if (params.width == box.width && params.height == box.height &&
            params.leftMargin == box.left && params.topMargin == box.top &&
            params.gravity == (Gravity.TOP or Gravity.LEFT)) return
        layoutParams = FrameLayout.LayoutParams(box.width, box.height, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = box.left
            topMargin = box.top
        }
    }
}
