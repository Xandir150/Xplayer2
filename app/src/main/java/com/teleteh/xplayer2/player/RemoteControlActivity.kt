package com.teleteh.xplayer2.player

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var tvLazyDebug: TextView

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

    // TEMPORARY: live IMU / parallax telemetry while tuning Lazy 3D head-tracking. Polls at
    // ~8 Hz so head movement is visible in the numbers; hides itself when Lazy 3D is off.
    // Remove this (and the tvLazyDebug view) once the head-tracking feel is dialled in.
    private val lazyDebugRunnable = object : Runnable {
        override fun run() {
            val player = PlayerActivity.currentInstance
            val line = player?.lazy3dDebugLine()
            if (line != null) {
                tvLazyDebug.visibility = View.VISIBLE
                tvLazyDebug.text = line
            } else {
                tvLazyDebug.visibility = View.GONE
            }
            handler.postDelayed(this, 120)
        }
    }
    
    // Screen dimming
    private var isScreenDimmed = false
    private var dimAnimator: ValueAnimator? = null
    private var dimOverlay: View? = null
    private val dimDelayMs = 5000L // 5 seconds before dimming
    private val dimRunnable = Runnable { dimScreen() }

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
        tvLazyDebug = findViewById(R.id.tvLazyDebug)

        // Play/Pause
        btnPlayPause.setOnClickListener {
            PlayerActivity.currentInstance?.togglePlayPause()
            updatePlayPauseButton()
        }

        // Rewind 10s
        findViewById<ImageButton>(R.id.btnRewind).setOnClickListener {
            PlayerActivity.currentInstance?.seekRelative(-10000)
        }

        // Forward 10s
        findViewById<ImageButton>(R.id.btnForward).setOnClickListener {
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
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // SBS toggle
        btnSbs.setOnClickListener {
            PlayerActivity.currentInstance?.toggleStereoSbs()
            updateButtons()
        }

        // Shift toggle
        btnShift.setOnClickListener {
            PlayerActivity.currentInstance?.toggleShift()
            updateButtons()
        }

        // Resize mode cycle (mirrors the overlay button on the goggles side)
        btnResizeMode.setOnClickListener {
            val newLabel = PlayerActivity.currentInstance?.cycleResizeMode()
            if (newLabel != null) btnResizeMode.text = newLabel
        }

        // Stream quality — only relevant for multi-quality sources (VK/OK.ru). The button is
        // hidden in updateButtons() when the source exposes a single quality.
        btnQuality.setOnClickListener {
            showQualityDialog()
        }

        // Audio track
        findViewById<MaterialButton>(R.id.btnAudio).setOnClickListener {
            showAudioTrackDialog()
        }

        // Subtitles — off by default, so this is the way to switch one on (and back off) while
        // the picture is on the goggles and the phone is the remote.
        findViewById<MaterialButton>(R.id.btnSubtitle).setOnClickListener {
            showSubtitleTrackDialog()
        }

        // Volume boost — cycles Off/+6/+12/+18/+24 dB to lift quiet sources. Reachable
        // here so it works while the picture is on the goggles and the phone is the remote.
        btnVolumeBoost.setOnClickListener {
            val player = PlayerActivity.currentInstance ?: return@setOnClickListener
            btnVolumeBoost.text = player.cycleVolumeBoost()
        }

        // Stop button - finish both activities
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
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
    
    private fun setupDimOverlay() {
        // Create a fullscreen black overlay for dimming
        val rootView = findViewById<View>(android.R.id.content) as android.view.ViewGroup
        dimOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            // NOT focusable: on a D-pad/TV device a focusable full-screen overlay would steal
            // focus when it appears and trap navigation. Touch wakes it via the click listener;
            // D-pad/keys wake it via dispatchKeyEvent.
            isFocusable = false
            setOnClickListener { wakeScreen() }
        }
        rootView.addView(dimOverlay, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }
    
    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
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
            duration = 500
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                dimOverlay?.alpha = value
                // Also dim actual screen brightness
                window.attributes = window.attributes.apply {
                    screenBrightness = (1f - value * 0.99f).coerceAtLeast(0.01f)
                }
            }
            start()
        }
    }
    
    private fun wakeScreen() {
        if (!isScreenDimmed) return
        isScreenDimmed = false
        
        dimAnimator?.cancel()
        
        // Animate back to full brightness
        val currentAlpha = dimOverlay?.alpha ?: 1f
        dimAnimator = ValueAnimator.ofFloat(currentAlpha, 0f).apply {
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                dimOverlay?.alpha = value
                window.attributes = window.attributes.apply {
                    screenBrightness = (1f - value * 0.99f).coerceAtLeast(0.01f)
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
        // Any touch resets the dim timer
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            if (isScreenDimmed) {
                wakeScreen()
                return true // Consume the touch
            }
            // Reset dim timer on any interaction
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
        handler.post(lazyDebugRunnable)
        // Start dim timer
        scheduleDim()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
        handler.removeCallbacks(lazyDebugRunnable)
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
        btnVolumeBoost.text = player.getVolumeBoostLabel()

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
        for (m in com.teleteh.xplayer2.data.depth.DepthModelManager.DepthModel.values()) {
            val btn = layoutInflater.inflate(R.layout.item_depth_model_button, container, false)
                    as MaterialButton
            btn.text = m.uiLabel
            btn.isChecked = (m == active)
            btn.setOnClickListener {
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
                val (_, trackIndex) = tracks[which]
                player.selectSubtitleTrack(trackIndex)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }
}
