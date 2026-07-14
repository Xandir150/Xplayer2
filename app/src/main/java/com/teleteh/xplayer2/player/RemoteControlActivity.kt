package com.teleteh.xplayer2.player

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import kotlin.math.abs
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.media3.common.util.UnstableApi
import com.google.android.material.button.MaterialButton
import com.teleteh.xplayer2.MainActivity
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.ui.util.DisplayUtils
import java.util.concurrent.TimeUnit

/**
 * Remote control activity for controlling video playback on external display.
 * This activity runs on the phone screen while PlayerActivity runs on glasses/external display.
 */
@UnstableApi
class RemoteControlActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSbs: MaterialButton
    private lateinit var btnShift: MaterialButton
    private lateinit var btnResizeMode: MaterialButton
    private lateinit var btnVolumeBoost: MaterialButton
    private lateinit var btnQuality: MaterialButton
    private lateinit var tvTouchFeedback: TextView
    private lateinit var remoteFlipper: ViewFlipper
    private lateinit var iconButtonsPage: android.widget.ImageView
    private lateinit var iconTouchpadPage: android.widget.ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            // Refresh the title too: for online sources (VK/OK) it's resolved asynchronously
            // after extraction/metadata, so a one-shot title in onResume would stay "video-…".
            updateTitle()
            handler.postDelayed(this, 500)
        }
    }

    // Touchpad state — the eyes-free gesture surface at the top of the remote.
    private lateinit var gestureDetector: GestureDetector
    private var audioManager: AudioManager? = null
    private var vibrator: Vibrator? = null
    private enum class SwipeAxis { NONE, HORIZONTAL, VERTICAL }
    private var swipeAxis = SwipeAxis.NONE
    private var volumeAccumPx = 0f
    private var feedbackFade: ValueAnimator? = null


    // Screen dimming
    private var isScreenDimmed = false
    private var dimAnimator: ValueAnimator? = null
    private var dimOverlay: View? = null
    private val dimDelayMs = 5000L // 5 seconds before dimming
    private val dimRunnable = Runnable { dimScreen() }
    // Tracks play/pause transitions so we only act (arm/disarm the dim timer, wake the screen)
    // on a change, not on every 500 ms poll tick — see updatePlayPauseButton().
    private var lastKnownPlaying: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this
        setContentView(R.layout.activity_remote_control)
        // Keep the *device* awake for the whole session. The glasses are a DisplayPort output, so
        // if the phone times out and sleeps, the goggles lose signal and playback dies. This stops
        // the OS screen-timeout while the remote is up; the phone can still dim to black (below)
        // and keep feeding the glasses. (Pressing the hardware power button still sleeps the whole
        // device — that's an OS limitation we can't override.)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvTitle = findViewById(R.id.tvTitle)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSbs = findViewById(R.id.btnSbs)
        btnShift = findViewById(R.id.btnShift)
        btnResizeMode = findViewById(R.id.btnResizeMode)
        btnVolumeBoost = findViewById(R.id.btnVolumeBoost)
        btnQuality = findViewById(R.id.btnQuality)
        tvTouchFeedback = findViewById(R.id.tvTouchFeedback)
        remoteFlipper = findViewById(R.id.remoteFlipper)
        iconButtonsPage = findViewById(R.id.iconButtonsPage)
        iconTouchpadPage = findViewById(R.id.iconTouchpadPage)

        audioManager = getSystemService(AudioManager::class.java)
        vibrator = getSystemService(Vibrator::class.java)
        setupTouchpad()
        setupButtonPageGestures()
        setupPageHandle()

        // Play/Pause
        btnPlayPause.setOnClickListener {
            hapticClick()
            PlayerActivity.currentInstance?.togglePlayPause()
            updatePlayPauseButton()
        }

        // Rewind 10s
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            hapticSeekBack()
            PlayerActivity.currentInstance?.seekRelative(-10000)
        }

        // Forward 10s
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
            hapticSeekForward()
            PlayerActivity.currentInstance?.seekRelative(10000)
        }

        // SeekBar
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    PlayerActivity.currentInstance?.seekTo(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                hapticClick()
            }
        })

        // SBS toggle
        btnSbs.setOnClickListener {
            hapticClick()
            PlayerActivity.currentInstance?.toggleStereoSbs()
            updateButtons()
        }

        // Shift toggle
        btnShift.setOnClickListener {
            hapticClick()
            PlayerActivity.currentInstance?.toggleShift()
            updateButtons()
        }

        // Resize mode cycle (mirrors the overlay button on the goggles side)
        btnResizeMode.setOnClickListener {
            hapticClick()
            val newLabel = PlayerActivity.currentInstance?.cycleResizeMode()
            if (newLabel != null) btnResizeMode.text = newLabel
        }

        // Stream quality — only relevant for multi-quality sources (VK/OK.ru). The button is
        // hidden in updateButtons() when the source exposes a single quality.
        btnQuality.setOnClickListener {
            hapticTick()
            showQualityDialog()
        }

        // Audio track
        findViewById<MaterialButton>(R.id.btnAudio).setOnClickListener {
            hapticTick()
            showAudioTrackDialog()
        }

        // Subtitles — off by default, so this is the way to switch one on (and back off) while
        // the picture is on the goggles and the phone is the remote.
        findViewById<MaterialButton>(R.id.btnSubtitle).setOnClickListener {
            hapticTick()
            showSubtitleTrackDialog()
        }

        // Volume boost — cycles Off/+6/+12/+18/+24 dB to lift quiet sources. Reachable
        // here so it works while the picture is on the goggles and the phone is the remote.
        btnVolumeBoost.setOnClickListener {
            hapticClick()
            val player = PlayerActivity.currentInstance ?: return@setOnClickListener
            player.cycleVolumeBoost()
            btnVolumeBoost.text = player.getVolumeBoostShortLabel()
        }

        // Stop button - finish both activities
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            hapticHeavy()
            PlayerActivity.currentInstance?.finishAndClose()
            finish()
        }
        
        // Setup dim overlay for screen dimming
        setupDimOverlay()

        // Make D-pad / TV focus obvious on every button (the default highlight is too subtle).
        applyTvFocusHighlight(findViewById(android.R.id.content))

        // Give D-pad navigation a definite starting point — without this, on a TV/box without a
        // touchscreen the first key press has nothing focused to react to.
        btnPlayPause.isFocusableInTouchMode = false
        btnPlayPause.isFocusable = true
        btnPlayPause.post { btnPlayPause.requestFocus() }

        // Back press: jump straight to MainActivity instead of revealing PlayerActivity behind us.
        // Without this, swipe-back from the remote pops the back stack to PlayerActivity, which
        // then redraws its full player UI on the phone even though the glasses still own the
        // video — confusing for users and looks like a second concurrent player.
        onBackPressedDispatcher.addCallback(this) {
            val intent = Intent(this@RemoteControlActivity, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            DisplayUtils.startOnPrimaryDisplay(this@RemoteControlActivity, intent)
            finish()
        }
    }
    
    /**
     * The eyes-free control surface: while the picture is on the goggles, the user can't see the
     * phone, so precise button taps are hopeless. The touchpad turns the top half of the remote
     * into coarse gestures, each confirmed by a DISTINCT vibration so the hand knows what
     * happened without looking:
     *   tap            -> play/pause          (single click, instant)
     *   fling right    -> +10 s               (one tick)
     *   fling left     -> −10 s               (two ticks — direction is feelable)
     *   drag up/down   -> media volume        (light tick per step)
     *   long-press     -> flash OSD on glasses (heavy click)
     *
     * The touchpad only works while the screen is lit. After 5 s idle the screen slowly fades
     * (5 s) to true black; while dark, ANY touch anywhere just wakes it (and is consumed, so
     * nothing underneath can be mis-tapped) — see dispatchTouchEvent.
     */
    private fun setupTouchpad() {
        val touchpad = findViewById<View>(R.id.touchpad)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val volumeStepPx = resources.displayMetrics.density * 48f  // ~one step per 48 dp of drag

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeAxis = SwipeAxis.NONE
                volumeAccumPx = 0f
                return true
            }

            fun tapPlayPause() {
                val player = PlayerActivity.currentInstance ?: return
                player.togglePlayPause()
                hapticClick()
                // togglePlayPause is synchronous on ExoPlayer, so the flipped state is readable
                // immediately — show what the tap DID, not what was before.
                showTouchFeedback(if (player.isPlaying()) "▶" else "⏸")
                updatePlayPauseButton()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tapPlayPause()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                val startX = e1?.x ?: return false
                val startY = e1.y
                // Lock the gesture to one axis once movement is unambiguous, so a sloppy
                // horizontal seek-swipe can't also nudge the volume.
                if (swipeAxis == SwipeAxis.NONE) {
                    val totalDx = abs(e2.x - startX)
                    val totalDy = abs(e2.y - startY)
                    if (totalDx > touchSlop * 2 || totalDy > touchSlop * 2) {
                        swipeAxis = if (totalDx > totalDy) SwipeAxis.HORIZONTAL else SwipeAxis.VERTICAL
                    }
                }
                if (swipeAxis == SwipeAxis.VERTICAL) {
                    // distanceY is positive when the finger moves UP -> volume up.
                    volumeAccumPx += distanceY
                    while (volumeAccumPx >= volumeStepPx) {
                        volumeAccumPx -= volumeStepPx
                        adjustVolume(up = true)
                    }
                    while (volumeAccumPx <= -volumeStepPx) {
                        volumeAccumPx += volumeStepPx
                        adjustVolume(up = false)
                    }
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                if (swipeAxis != SwipeAxis.HORIZONTAL || abs(velocityX) < 600) return false
                val player = PlayerActivity.currentInstance ?: return true
                if (velocityX > 0) {
                    player.seekRelative(10_000)
                    hapticSeekForward()
                    showTouchFeedback("⏩ +10s")
                } else {
                    player.seekRelative(-10_000)
                    hapticSeekBack()
                    showTouchFeedback("⏪ −10s")
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // Placeholder until the glasses OSD menu lands: long-press just flashes the
                // transport OSD in the goggles so the user can check position without acting.
                hapticHeavy()
                PlayerActivity.currentInstance?.flashGlassesOsd()
                showTouchFeedback("ⓘ")
            }
        })

        touchpad.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            gestureDetector.onTouchEvent(ev)
            true
        }
    }

    /**
     * The button page has plenty of dead space around/between the three transport buttons, so it
     * gets the same YouTube-style shortcuts as the touchpad, minus volume (that stays touchpad-
     * only): tap empty space = play/pause, swipe or double-tap left/right = ±10 s. This overlay
     * sits BEHIND the buttons in z-order (added first in the XML), so a touch that lands on an
     * actual button is consumed by the button itself and never reaches here — only touches that
     * miss every button fall through to this layer.
     *
     * Unlike the touchpad (which has no double-tap, so a single tap can fire instantly via
     * onSingleTapUp), play/pause here waits for onSingleTapConfirmed — the ~300 ms double-tap
     * timeout — so a double-tap-to-seek doesn't also toggle playback. Same trade-off YouTube's
     * own app makes.
     *
     * Double-tap only seeks in the outer thirds of the area, like YouTube's own gesture zones —
     * the middle third is a dead zone so a double-tap near the play/pause button can't also seek.
     * Feedback lives ABOVE the buttons (not behind/under them) so it's never obscured.
     */
    private fun setupButtonPageGestures() {
        val area = findViewById<View>(R.id.buttonPageGestureArea)
        val tvFeedback = findViewById<TextView>(R.id.tvButtonPageFeedback)

        fun seek(forward: Boolean) {
            val player = PlayerActivity.currentInstance ?: return
            if (forward) {
                player.seekRelative(10_000)
                hapticSeekForward()
                showFeedback(tvFeedback, "⏩ +10s")
            } else {
                player.seekRelative(-10_000)
                hapticSeekBack()
                showFeedback(tvFeedback, "⏪ −10s")
            }
        }

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val player = PlayerActivity.currentInstance ?: return true
                player.togglePlayPause()
                hapticClick()
                showFeedback(tvFeedback, if (player.isPlaying()) "▶" else "⏸")
                updatePlayPauseButton()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val w = area.width
                if (e.x < w / 3f) seek(forward = false)
                else if (e.x > w * 2f / 3f) seek(forward = true)
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent,
                velocityX: Float, velocityY: Float
            ): Boolean {
                val startX = e1?.x ?: return false
                val startY = e1.y
                if (abs(velocityX) < 600 || abs(e2.x - startX) < abs(e2.y - startY)) return false
                seek(forward = velocityX > 0)
                return true
            }
        })

        area.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            detector.onTouchEvent(ev)
            true
        }
    }

    /**
     * The block above the seekbar shows one of two pages — button transport (default, page 0,
     * works with a D-pad on a TV box with no touch at all) or the touchpad (page 1, eyes-free,
     * phone-only). Only this block slides; nothing else on the remote moves. Switching is a plain
     * tap on the handle below the block (a fling/swipe gesture there used to ALSO trigger the
     * forced performClick() on ACTION_UP, double-toggling and flipping straight back — looked
     * like a glitch). Never a gesture over the pages themselves either way — the touchpad's own
     * swipes already mean seek/volume.
     */
    private fun setupPageHandle() {
        val handle = findViewById<View>(R.id.pageHandle)
        updatePageIcons()
        handle.setOnClickListener { switchPage() }
    }

    private fun switchPage() {
        val goingToTouchpad = remoteFlipper.displayedChild == 0
        remoteFlipper.inAnimation = AnimationUtils.loadAnimation(
            this, if (goingToTouchpad) R.anim.slide_in_from_right else R.anim.slide_in_from_left
        )
        remoteFlipper.outAnimation = AnimationUtils.loadAnimation(
            this, if (goingToTouchpad) R.anim.slide_out_to_left else R.anim.slide_out_to_right
        )
        remoteFlipper.displayedChild = if (goingToTouchpad) 1 else 0
        hapticTick()
        updatePageIcons()
    }

    private fun updatePageIcons() {
        val onTouchpad = remoteFlipper.displayedChild == 1
        val activeColor = themeColor(androidx.appcompat.R.attr.colorPrimary)
        // rc_outline is a near-black card-stroke colour — invisible here. rc_secondary is the
        // muted-but-legible grey used for secondary text, reads correctly as "inactive".
        val inactiveColor = getColor(R.color.rc_secondary)
        iconButtonsPage.imageTintList = ColorStateList.valueOf(if (onTouchpad) inactiveColor else activeColor)
        iconTouchpadPage.imageTintList = ColorStateList.valueOf(if (onTouchpad) activeColor else inactiveColor)
    }

    private fun adjustVolume(up: Boolean) {
        val am = audioManager ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0 // no system volume UI — our own feedback text + haptic tick instead
        )
        hapticTick()
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        showTouchFeedback((if (up) "🔊 " else "🔉 ") + (cur * 100 / max) + "%")
    }

    /** Big center text on the touchpad showing the last action, fading out on its own. */
    private fun showTouchFeedback(text: String) = showFeedback(tvTouchFeedback, text)

    /** Same feedback flash, generalized so the button page's own gesture layer can use it too. */
    private fun showFeedback(tv: TextView, text: String) {
        feedbackFade?.cancel()
        tv.text = text
        tv.alpha = 1f
        feedbackFade = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 500
            duration = 500
            addUpdateListener { tv.alpha = it.animatedValue as Float }
            start()
        }
    }

    // --- Haptic vocabulary -------------------------------------------------------------------
    private fun vibrate(effect: VibrationEffect) {
        try { vibrator?.vibrate(effect) } catch (_: Throwable) { /* some boxes have no vibrator */ }
    }
    private fun hapticClick() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
    private fun hapticTick() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    private fun hapticHeavy() = vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
    private fun hapticSeekForward() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30), -1))
    private fun hapticSeekBack() = vibrate(VibrationEffect.createWaveform(longArrayOf(0, 25, 90, 25), -1))

    private fun setupDimOverlay() {
        // Create a fullscreen black overlay for dimming
        val rootView = findViewById<View>(android.R.id.content) as android.view.ViewGroup
        dimOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            // Purely visual: while dimmed, dispatchTouchEvent intercepts ALL touches before any
            // view (routing them into the touchpad gestures), so this overlay never needs to be
            // clickable. NOT focusable either — on a D-pad/TV device a focusable full-screen
            // overlay would steal focus and trap navigation; keys wake via dispatchKeyEvent.
            isClickable = false
            isFocusable = false
        }
        rootView.addView(dimOverlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }
    
    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        // Dimming exists so the phone doesn't glow at the user while the picture is on the
        // goggles — only a concern while video is actually playing. Paused, keep the remote lit:
        // the user is very likely looking at it to decide what to do next.
        if (PlayerActivity.currentInstance?.isPlaying() != true) return
        handler.postDelayed(dimRunnable, dimDelayMs)
    }
    
    private fun cancelDim() {
        handler.removeCallbacks(dimRunnable)
    }
    
    private fun dimScreen() {
        if (isScreenDimmed) return
        isScreenDimmed = true
        
        dimAnimator?.cancel()
        dimOverlay?.visibility = View.VISIBLE
        
        // Animate overlay alpha and screen brightness
        dimAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            // Slow, gentle fade-out (5 s): the user is settling into the goggles — a snap to
            // black reads as "something broke"; a long dusk reads as intentional.
            duration = 5000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                dimOverlay?.alpha = value
                // Dim the actual backlight all the way to BRIGHTNESS_OVERRIDE_OFF (0.0):
                // backlight fully off, touch digitizer stays alive — combined with the opaque
                // black overlay this is genuinely zero light on OLED, not a 1% glow.
                window.attributes = window.attributes.apply {
                    screenBrightness = (1f - value).coerceAtLeast(
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    )
                }
            }
            start()
        }
    }
    
    private fun wakeScreen() {
        if (!isScreenDimmed) return
        isScreenDimmed = false
        
        dimAnimator?.cancel()
        
        // Waking is near-instant (unlike the long dim fade): the user actively asked for the
        // screen, don't make them watch it ramp.
        val currentAlpha = dimOverlay?.alpha ?: 1f
        dimAnimator = ValueAnimator.ofFloat(currentAlpha, 0f).apply {
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                dimOverlay?.alpha = value
                window.attributes = window.attributes.apply {
                    screenBrightness = (1f - value).coerceAtLeast(
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
                    )
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dimOverlay?.visibility = View.GONE
                    // Reset brightness to auto
                    window.attributes = window.attributes.apply {
                        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    // Schedule next dim
                    scheduleDim()
                }
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
            start()
        }
    }
    
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        if (isScreenDimmed) {
            // Dark (or fading) screen: any touch just wakes it, and is consumed — nothing ever
            // reaches the controls underneath, so a blind grab of the phone can't mis-tap
            // anything. All actual control happens on the lit remote.
            if (ev.action == MotionEvent.ACTION_DOWN) {
                hapticTick() // confirm the wake landed even before the eyes find the screen
                wakeScreen()
            }
            return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN) {
            // Reset dim timer on any interaction while lit
            scheduleDim()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // D-pad / remote-key equivalent of the touch handler above: keep the dim timer from
        // firing while the user is navigating, and wake on the first key when already dimmed.
        // Without this, on a TV/box (no touchscreen) the screen dims after 5 s and navigation
        // appears frozen.
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isScreenDimmed) {
                wakeScreen()
                return true
            }
            scheduleDim()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        // Check if PlayerActivity is still running
        if (PlayerActivity.currentInstance == null) {
            finish()
            return
        }
        updateTitle()
        updateButtons()
        updateProgress()
        handler.post(updateRunnable)
        // Start dim timer
        scheduleDim()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        cancelDim()
        dimAnimator?.cancel()
        // Reset brightness when leaving
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance === this) currentInstance = null
    }

    /** Refresh the control labels/states from the player. Called by PlayerActivity when the
     *  render mode changes (e.g. stereo auto-detected after the remote already drew its buttons),
     *  so the SBS button doesn't keep showing a stale "2D". */
    fun syncControls() {
        if (PlayerActivity.currentInstance == null) return
        updateButtons()
    }

    /** Push-path from the player's onIsPlayingChanged: refresh the transport UI immediately
     *  instead of waiting out the 500 ms poll (which read as lag after every remote tap). */
    fun onTransportChanged() {
        if (PlayerActivity.currentInstance == null) return
        updateProgress()
    }

    companion object {
        /** Live remote-control instance, so PlayerActivity can dismiss it when the goggles come
         *  off and playback moves back to the phone screen. */
        @Volatile
        var currentInstance: RemoteControlActivity? = null
    }

    // Back press handled by default - just finishes this activity without affecting PlayerActivity

    private fun updateTitle() {
        val title = PlayerActivity.currentInstance?.getCurrentTitle() ?: "No video"
        tvTitle.text = title
    }

    private fun updateProgress() {
        val player = PlayerActivity.currentInstance
        if (player == null) {
            finish()
            return
        }
        
        val position = player.getCurrentPosition()
        val duration = player.getDuration()

        tvPosition.text = formatTime(position)
        tvDuration.text = formatTime(duration)

        if (duration > 0) {
            seekBar.max = duration.toInt()
            seekBar.progress = position.toInt()
        }

        updatePlayPauseButton()
    }

    private fun updatePlayPauseButton() {
        val isPlaying = PlayerActivity.currentInstance?.isPlaying() ?: false
        btnPlayPause.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        // React only on a play<->pause transition (this runs every 500 ms poll tick too, and
        // scheduleDim() would just keep resetting its own timer otherwise). Pausing wakes the
        // screen immediately and disarms the timer; resuming re-arms it.
        if (lastKnownPlaying != isPlaying) {
            lastKnownPlaying = isPlaying
            if (isPlaying) {
                scheduleDim()
            } else {
                cancelDim()
                if (isScreenDimmed) wakeScreen()
            }
        }
    }

    private fun updateButtons() {
        val player = PlayerActivity.currentInstance ?: return

        // Mode button — one 4-state cycle: 2D / Lazy 3D / OU→SBS / SBS. Label reflects the current
        // mode; filled (active) for anything but plain 2D (Lazy 3D counts as active).
        val active = player.isLazy3dEnabled() || player.isStereoSbsEnabled()
        btnSbs.text = player.getStereoModeLabel()
        // Same busy-lock as the phone overlay's button: a tap during the model download/warm-up
        // would cancel the fetch and jump the cycle — lock it here too (label shows the %).
        btnSbs.isEnabled = !player.isLazy3dBusy()
        btnSbs.isChecked = active
        applyButtonStyle(btnSbs, active)

        // Shift button — only shown in OU→SBS mode (vertical shift is meaningless otherwise).
        btnShift.visibility = if (player.isOuSbsMode()) View.VISIBLE else View.GONE
        val shiftEnabled = player.isShiftEnabled()
        btnShift.isChecked = shiftEnabled
        applyButtonStyle(btnShift, shiftEnabled)

        // Resize mode (Auto/16:9/...). Label only — no checked state.
        btnResizeMode.text = player.getResizeModeLabel()

        // Volume boost label reflects the persisted value when the remote opens.
        btnVolumeBoost.text = player.getVolumeBoostShortLabel()

        // Quality picker — only for sources with ≥2 qualities. Label shows the current quality.
        if (player.hasMultipleQualities()) {
            btnQuality.visibility = View.VISIBLE
            val current = player.getQualityVariants().getOrNull(player.getSelectedQualityIndex())
            // Short label (just the resolution) since the button now shares a row with Aspect.
            btnQuality.text = current ?: getString(R.string.quality)
        } else {
            btnQuality.visibility = View.GONE
        }
    }

    /** Draw a bright focus ring over every button so D-pad / TV selection is visible. */
    private fun applyTvFocusHighlight(v: View) {
        if (v is MaterialButton || v is ImageButton) {
            v.foreground = ContextCompat.getDrawable(this, R.drawable.tv_focus_ring)
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) applyTvFocusHighlight(v.getChildAt(i))
        }
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun applyButtonStyle(btn: MaterialButton, checked: Boolean) {
        // Active = filled with the theme accent (Material You on Android 12+, else brand purple);
        // inactive = the same tonal row colour as the non-toggle buttons, for a consistent card.
        btn.strokeWidth = 0
        if (checked) {
            btn.backgroundTintList = ColorStateList.valueOf(themeColor(androidx.appcompat.R.attr.colorPrimary))
            btn.setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimary))
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.rc_row))
            btn.setTextColor(getColor(R.color.rc_on_surface))
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
    
    /**
     * Mirrors PlayerActivity's depth-model picker, shown HERE instead: when the picture is on the
     * glasses, this activity (not PlayerActivity, which sits behind it) is what's actually in
     * front on the phone, so a Dialog needs this activity's context to show/be interactable.
     * See PlayerActivity.offerDepthModelPicker.
     */
    fun showDepthModelDialog() {
        val player = PlayerActivity.currentInstance ?: return
        val view = layoutInflater.inflate(R.layout.dialog_depth_model, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.modelContainer)
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val active = com.teleteh.xplayer2.data.depth.DepthModelManager.activeModel(this)
        for (m in com.teleteh.xplayer2.data.depth.DepthModelManager.DepthModel.values().filter { it.selectable }) {
            val btn = layoutInflater.inflate(R.layout.item_depth_model_button, container, false)
                    as MaterialButton
            btn.text = m.uiLabel
            btn.isChecked = (m == active)
            btn.setOnClickListener {
                hapticClick()
                dialog.dismiss()
                player.applyChosenDepthModel(m)
                updateButtons()
            }
            container.addView(btn)
        }
        dialog.show()
    }

    private fun showQualityDialog() {
        val player = PlayerActivity.currentInstance ?: return
        val labels = player.getQualityVariants()
        if (labels.size <= 1) return
        val checkedItem = player.getSelectedQualityIndex().coerceIn(0, labels.size - 1)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.quality)
            .setSingleChoiceItems(labels.toTypedArray(), checkedItem) { dialog, which ->
                hapticClick()
                player.selectQuality(which)
                updateButtons()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showAudioTrackDialog() {
        val player = PlayerActivity.currentInstance ?: return
        val tracks = player.getAudioTracks()
        if (tracks.isEmpty()) return
        
        val labels = tracks.map { it.first }.toTypedArray()
        val selectedIndex = player.getSelectedAudioTrackIndex()
        
        // Find which item in our list corresponds to selected index
        var checkedItem = 0 // Default to Auto
        tracks.forEachIndexed { i, (_, idx) ->
            if (idx == selectedIndex) checkedItem = i
        }
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.dialog_audio_track_title)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                hapticClick()
                val (_, trackIndex) = tracks[which]
                player.selectAudioTrack(trackIndex)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showSubtitleTrackDialog() {
        val player = PlayerActivity.currentInstance ?: return
        val tracks = player.getSubtitleTracks()
        // getSubtitleTracks() always returns at least "Off"; size <= 1 means the clip has no
        // selectable subtitle tracks, so a chooser would only offer "Off" — say so instead.
        if (tracks.size <= 1) {
            Toast.makeText(this, R.string.subtitle_none, Toast.LENGTH_SHORT).show()
            return
        }

        val labels = tracks.map { it.first }.toTypedArray()
        val selectedIndex = player.getSelectedSubtitleTrackIndex()

        // Find which item in our list corresponds to the selected index (default to "Off").
        var checkedItem = 0
        tracks.forEachIndexed { i, (_, idx) ->
            if (idx == selectedIndex) checkedItem = i
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.select_subtitle)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                hapticClick()
                val (_, trackIndex) = tracks[which]
                player.selectSubtitleTrack(trackIndex)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
