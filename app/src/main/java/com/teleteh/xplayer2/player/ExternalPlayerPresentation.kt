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
    /**
     * PC Link world-fixed mode: host a [VirtualDesktopGlView] (head-tracked virtual monitor)
     * instead of the [OuToSbsGlView] the file player uses. Fixed for the lifetime of the
     * presentation — PlayerActivity recreates the presentation when the mode changes.
     */
    val isWorldFixedDesktop: Boolean = false,
    private val surfaceListener: (Surface?) -> Unit
) : Presentation(context, display) {

    private var glView: OuToSbsGlView? = null
    private var playerView: PlayerView? = null
    private var desktopGlView: VirtualDesktopGlView? = null

    /**
     * The GL view that actually renders video on the external display. PlayerActivity routes
     * all render-state (SBS / source-layout / resize / parallax / depth) to this view when a
     * presentation is active, because the decoded frames go to *its* surface, not the
     * activity's local glView. Null in world-fixed desktop mode ([desktopView] renders instead).
     */
    val renderView: OuToSbsGlView? get() = if (isWorldFixedDesktop) null else glView

    /** The world-fixed desktop view, when this presentation was created in that mode. */
    val desktopView: VirtualDesktopGlView? get() = desktopGlView

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
        // Keep the on-glasses transport flash short: it's feedback for a remote action the user
        // just made, not a control surface they interact with (there's no touch on the glasses).
        playerView?.controllerShowTimeoutMs = 2000
        if (isWorldFixedDesktop) {
            // PC Link world-fixed: swap the flat OuToSbsGlView out for the head-tracked virtual
            // desktop. Same slot in the tree (index 0, under the mirror overlay), same surface
            // contract — PcStreamDecoder renders into whichever surface arrives via the listener.
            // The hidden OuToSbsGlView never creates its GL surface (GONE = no SurfaceHolder), so
            // exactly one GL pipeline runs.
            glView?.visibility = View.GONE
            // No ExoPlayer in PC Link mode; a PlayerView with no player can shutter opaque black
            // over the GL view, so stand it down entirely.
            playerView?.visibility = View.GONE
            val desktop = VirtualDesktopGlView(context)
            desktopGlView = desktop
            (findViewById<View>(R.id.presentationGlView).parent as? android.view.ViewGroup)
                ?.addView(
                    desktop, 0,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            desktop.setOnSurfaceReadyListener { surface ->
                surfaceListener(surface)
            }
        } else {
            glView?.setOnSurfaceReadyListener { surface ->
                surfaceListener(surface)
            }
            // Mirror SBS state defaults: OFF swap, actual SBS set from Activity after show
            glView?.setSwapEyes(false)
        }
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

    /**
     * Briefly show the transport OSD (play state, seekbar, position) on the glasses. Called by
     * PlayerActivity when the user acts on the phone remote mid-playback — media3 only auto-shows
     * the controller on pause, so seeks while playing were otherwise invisible in the goggles.
     */
    fun flashOsd() {
        playerView?.showController()
    }

    override fun onStop() {
        super.onStop()
        glView?.onPause()
        desktopGlView?.onPause()
        surfaceListener(null)
    }

    override fun onStart() {
        super.onStart()
        glView?.onResume()
        desktopGlView?.onResume()
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
