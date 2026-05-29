package com.teleteh.xplayer2.player

import android.app.Presentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.ui.DvdScreensaverView

/**
 * Full-screen idle screensaver shown on the external (glasses) display while no film is playing.
 * MainActivity puts this up so the goggles show the retro bouncing-DVD logo instead of mirroring
 * the phone UI; it's dismissed when a film starts (PlayerActivity takes over the display).
 */
class ScreensaverPresentation(
    context: Context,
    display: Display,
    /** Supplies head orientation [yaw,pitch,roll]° for the world-anchored platform, or null. */
    private val orientationProvider: (() -> FloatArray?)? = null,
) : Presentation(context, display) {

    private var saver: DvdScreensaverView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.presentation_screensaver)
        window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.attributes = window?.attributes?.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        saver = findViewById(R.id.screensaverView)
        saver?.orientationProvider = orientationProvider
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        saver?.start()
        hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        saver?.stop()
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
}
