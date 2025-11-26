package com.teleteh.xplayer2.player

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.teleteh.xplayer2.R

@UnstableApi
class ExternalPlayerPresentation(
    context: Context,
    display: Display,
    private val surfaceListener: (Surface?) -> Unit
) : Presentation(context, display) {

    private var glView: OuToSbsGlView? = null
    private var playerView: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_player)
        // Make window fullscreen and allow drawing edge-to-edge
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        // Keep only the external screen on while the device may lock the primary display
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes = window?.attributes?.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        glView = findViewById(R.id.presentationGlView)
        playerView = findViewById(R.id.presentationPlayerView)
        glView?.setOnSurfaceReadyListener { surface ->
            surfaceListener(surface)
        }
        // Mirror SBS state defaults: OFF swap, actual SBS set from Activity after show
        glView?.setSwapEyes(false)
        glView?.isFocusableInTouchMode = true
        glView?.requestFocus()
        hideSystemBars()
        // Re-apply immersive if system bars reappear (pre-R)
        @Suppress("DEPRECATION")
        window?.decorView?.setOnSystemUiVisibilityChangeListener {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                hideSystemBars()
            }
        }
        // Re-apply immersive on insets (R+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.decorView?.setOnApplyWindowInsetsListener { v, insets ->
                hideSystemBars()
                insets
            }
        }
    }

    fun setSbsEnabled(enabled: Boolean) {
        glView?.setSbsEnabled(enabled)
    }

    fun setSwapEyes(enabled: Boolean) {
        glView?.setSwapEyes(enabled)
    }

    fun setPlayer(player: Player?) {
        playerView?.player = player
    }

    override fun onStop() {
        super.onStop()
        glView?.onPause()
        surfaceListener(null)
    }

    override fun onStart() {
        super.onStart()
        glView?.onResume()
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val w = window ?: return
        val decor = w.decorView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(w, false)
            val controller = WindowInsetsControllerCompat(w, decor)
            controller.hide(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
