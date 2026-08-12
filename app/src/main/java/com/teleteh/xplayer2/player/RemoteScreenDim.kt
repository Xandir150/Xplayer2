package com.teleteh.xplayer2.player

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The phone going dark while the picture is on the glasses.
 *
 * Both remotes need it and for the same reason: the user is wearing the goggles, the phone is in a
 * hand under them, and a lit screen down there is a lamp shining up. After five idle seconds it
 * fades — slowly, over five more — to genuinely black: an opaque overlay *and* the backlight driven
 * to `BRIGHTNESS_OVERRIDE_OFF`, which on OLED is no light rather than the 1% glow that
 * `screenBrightness = 0` alone leaves. The digitizer stays alive, so the first touch anywhere wakes
 * it (and is swallowed, so a blind grab of the phone can't press anything).
 *
 * The system bars go with it: our overlay only covers the app's content area, so without hiding
 * them the status-bar clock keeps glowing over an otherwise black screen.
 *
 * Waking is near-instant (150 ms) where dimming is slow. The asymmetry is deliberate — a snap to
 * black reads as "something broke", while a slow wake reads as lag after the user has already asked
 * for the screen.
 *
 * [shouldDim] is the caller's policy, asked each time the timer is armed: the film remote dims only
 * while something is actually playing (paused, the user is very likely reading the remote), and the
 * PC Link remote only while a stream is arriving.
 */
class RemoteScreenDim(
    private val activity: Activity,
    private val handler: Handler,
    private val shouldDim: () -> Boolean,
) {

    /** True from the moment the fade starts — the screen is dark or on its way there. */
    var isDimmed = false
        private set

    private var overlay: View? = null
    private var animator: ValueAnimator? = null
    private val dimRunnable = Runnable { dim() }

    /** Adds the blackout overlay over the activity's content. Call once, from `onCreate`. */
    fun attach() {
        if (overlay != null) return
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val view = View(activity).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            // Purely visual: while dimmed the activity intercepts ALL touches before any view, so
            // this never needs to be clickable. NOT focusable either — on a D-pad/TV device a
            // focusable full-screen overlay would steal focus and trap navigation; keys wake it
            // through the activity's own key dispatch.
            isClickable = false
            isFocusable = false
        }
        root.addView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        overlay = view
    }

    /** (Re)arms the idle timer, unless [shouldDim] says this is not a moment to go dark. */
    fun schedule() {
        handler.removeCallbacks(dimRunnable)
        if (!shouldDim()) return
        handler.postDelayed(dimRunnable, DIM_DELAY_MS)
    }

    /** Disarms the idle timer, leaving the current brightness alone. */
    fun cancel() {
        handler.removeCallbacks(dimRunnable)
    }

    /** Back to full brightness, then re-arms the timer when the wake animation lands. */
    fun wake() {
        if (!isDimmed) return
        isDimmed = false
        animator?.cancel()
        setSystemBarsHidden(false)
        val from = overlay?.alpha ?: 1f
        animator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = WAKE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { applyDimFraction(it.animatedValue as Float) }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    overlay?.visibility = View.GONE
                    restoreBrightness()
                    schedule()
                }

                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationCancel(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            start()
        }
    }

    /**
     * Leaving the screen: stop the timer, drop any animation, and hand the window back as we found
     * it — full brightness and the system bars visible. Whatever comes next must not inherit a
     * blacked-out window.
     */
    fun onPause() {
        cancel()
        animator?.cancel()
        setSystemBarsHidden(false)
        restoreBrightness()
    }

    private fun dim() {
        if (isDimmed) return
        isDimmed = true
        animator?.cancel()
        setSystemBarsHidden(true)
        overlay?.visibility = View.VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DIM_FADE_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { applyDimFraction(it.animatedValue as Float) }
            start()
        }
    }

    /** 0 = fully lit, 1 = fully black. Overlay and backlight move together. */
    private fun applyDimFraction(fraction: Float) {
        overlay?.alpha = fraction
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = (1f - fraction).coerceAtLeast(
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
            )
        }
    }

    private fun restoreBrightness() {
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        if (hidden) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val DIM_DELAY_MS = 5_000L
        const val DIM_FADE_MS = 5_000L
        const val WAKE_MS = 150L
    }
}
