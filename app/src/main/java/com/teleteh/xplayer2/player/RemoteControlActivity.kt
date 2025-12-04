package com.teleteh.xplayer2.player

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.media3.common.util.UnstableApi
import com.google.android.material.button.MaterialButton
import com.teleteh.xplayer2.R
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

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
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
        setContentView(R.layout.activity_remote_control)

        tvTitle = findViewById(R.id.tvTitle)
        tvPosition = findViewById(R.id.tvPosition)
        tvDuration = findViewById(R.id.tvDuration)
        seekBar = findViewById(R.id.seekBar)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSbs = findViewById(R.id.btnSbs)
        btnShift = findViewById(R.id.btnShift)

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

        // Audio track
        findViewById<MaterialButton>(R.id.btnAudio).setOnClickListener {
            showAudioTrackDialog()
        }

        // Stop button - finish both activities
        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            PlayerActivity.currentInstance?.finishAndClose()
            finish()
        }
        
        // Setup dim overlay for screen dimming
        setupDimOverlay()
    }
    
    private fun setupDimOverlay() {
        // Create a fullscreen black overlay for dimming
        val rootView = findViewById<View>(android.R.id.content) as android.view.ViewGroup
        dimOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            visibility = View.GONE
            isClickable = true
            isFocusable = true
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

        // SBS button
        val sbsEnabled = player.isStereoSbsEnabled()
        btnSbs.isChecked = sbsEnabled
        applyButtonStyle(btnSbs, sbsEnabled)

        // Shift button
        val shiftEnabled = player.isShiftEnabled()
        btnShift.isChecked = shiftEnabled
        applyButtonStyle(btnShift, shiftEnabled)
    }

    private fun applyButtonStyle(btn: MaterialButton, checked: Boolean) {
        if (checked) {
            btn.backgroundTintList = ColorStateList.valueOf("#2196F3".toColorInt())
            btn.setTextColor(Color.WHITE)
            btn.strokeWidth = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(Color.WHITE)
            btn.strokeColor = ColorStateList.valueOf(Color.WHITE)
            btn.strokeWidth = (2 * resources.displayMetrics.density).toInt()
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
            .setTitle("Audio Track")
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                val (_, trackIndex) = tracks[which]
                player.selectAudioTrack(trackIndex)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
