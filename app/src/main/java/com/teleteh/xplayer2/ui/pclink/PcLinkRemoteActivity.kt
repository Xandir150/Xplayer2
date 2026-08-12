package com.teleteh.xplayer2.ui.pclink

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.teleteh.xplayer2.MainActivity
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.player.PcLinkSession
import com.teleteh.xplayer2.player.RemoteHaptics
import com.teleteh.xplayer2.player.RemoteScreenDim
import com.teleteh.xplayer2.player.RemoteStyling
import com.teleteh.xplayer2.ui.util.DisplayUtils
import kotlin.math.abs

/**
 * **PC Link's remote** — the thing you hold while your computer is on the glasses.
 *
 * A sibling of `RemoteControlActivity`, deliberately and structurally: the player brings it to the
 * front in the same place and the same way, it keeps the device awake for the same reason, it dims
 * itself to black on the same clock, it answers Back the same way, and it is built out of the same
 * cards, rows and colours (see `RemoteScreenDim`, `RemoteHaptics`, `RemoteStyling`, all shared with
 * it). What differs is only what a desktop *is*: there is no transport and no seekbar, because
 * there is no timeline; in their place are the two numbers that say whether the stream is healthy,
 * and a re-centre.
 *
 * **Its own screen, not the PC-Mirror tab in an activity frame.** The tab's job is finding a PC and
 * starting a session; this one's job is holding a session that is already running. They want
 * opposite things from the same pixels — the tab needs a list and a way in, the remote needs big
 * eyes-free targets, a window that can go black, a Back key of its own — and the version that tried
 * to be both was a settings form in a room where nobody could see it.
 *
 * **Leaving here ends the cast.** Back, Disconnect and the session-ended-elsewhere path all run
 * through [leave]: the session gets its proper goodbye (which hands the PC its own speakers back)
 * and the user lands on the PC-Mirror tab. The remote and the session have exactly one lifetime
 * between them, so there is no state in which a desktop is streaming to the glasses with no visible
 * remote — and no way to come out of here onto the player's empty grey window, which is the fault
 * this screen was rebuilt to remove.
 */
class PcLinkRemoteActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val history = PcLinkStatsHistory()

    private lateinit var haptics: RemoteHaptics
    private lateinit var dim: RemoteScreenDim
    private var audioManager: AudioManager? = null

    private lateinit var tvTitle: TextView
    private lateinit var tvLinkState: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var tvAudioHint: TextView
    private lateinit var btnAudioRoute: MaterialButton
    private lateinit var btnRecenter: MaterialButton
    private lateinit var chevron: ImageView
    private lateinit var detailsBox: LinearLayout
    private lateinit var fpsChip: Chip
    private lateinit var bitrateChip: Chip

    /** The detail rows, built once and then only re-texted — one row per line of [detailLines]. */
    private val detailRows = ArrayList<Pair<TextView, TextView>>()
    private var detailsOpen = false

    private lateinit var gestures: GestureDetector
    private enum class SwipeAxis { NONE, HORIZONTAL, VERTICAL }
    private var swipeAxis = SwipeAxis.NONE
    private var volumeAccumPx = 0f
    private var feedbackFade: ValueAnimator? = null

    /** True once [leave] has run, so a late tick can't start a second exit. */
    private var leaving = false

    /**
     * The link state the dim policy last acted on — see [PcLinkRemotePolicy.dimAction]. Only a
     * transition may touch the timer, exactly as on the film remote's play/pause.
     */
    private var lastLink: PcLinkSession.Link? = null

    private val tick = object : Runnable {
        override fun run() {
            sample()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this
        setContentView(R.layout.activity_pc_link_remote)
        // Same reason as the film remote: the glasses are a DisplayPort output, so a phone that
        // sleeps takes the picture down with it. The screen can still go black (below) and keep
        // feeding them.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvTitle = findViewById(R.id.tvTitle)
        tvLinkState = findViewById(R.id.tvLinkState)
        tvFeedback = findViewById(R.id.tvSurfaceFeedback)
        tvAudioHint = findViewById(R.id.tvAudioHint)
        btnAudioRoute = findViewById(R.id.btnAudioRoute)
        btnRecenter = findViewById(R.id.btnRecenter)
        chevron = findViewById(R.id.ivDetailsChevron)
        detailsBox = findViewById(R.id.boxDetails)
        fpsChip = Chip(findViewById(R.id.chipFps), getString(R.string.pclink_stat_fps), history.fps)
        bitrateChip =
            Chip(findViewById(R.id.chipBitrate), getString(R.string.pclink_stat_bitrate), history.mbps)

        audioManager = getSystemService(AudioManager::class.java)
        haptics = RemoteHaptics(this)
        // Only go dark while frames are actually arriving: mid-reconnect, or with the link down,
        // the user is looking at this screen to find out what happened.
        dim = RemoteScreenDim(this, handler) {
            PcLinkSession.stats()?.link == PcLinkSession.Link.STREAMING
        }
        dim.attach()
        setupSurface()

        btnRecenter.setOnClickListener {
            haptics.click()
            PcLinkSession.recenter()
            // The same mark the long-press leaves (see [setupSurface]), because it is the same
            // action. The slot is one 40sp glyph wide; a sentence in it wrapped across the card.
            showFeedback(RECENTER_MARK)
        }

        btnAudioRoute.setOnClickListener {
            haptics.click()
            // Truth is the session, never the widget: this button is `checkable`, and
            // MaterialButton.performClick() flips isChecked BEFORE it dispatches this listener —
            // see [PcLinkRemotePolicy.audioTapCommand].
            val wanted = PcLinkRemotePolicy.audioTapCommand(PcLinkSession.stats()?.audioToGlasses)
                ?: return@setOnClickListener
            PcLinkSession.setAudioToGlasses(wanted)
            // Don't wait out the next tick to show what the tap did.
            sample()
        }

        detailsOpen = prefs().getBoolean(PREF_DETAILS_OPEN, false)
        applyDetailsOpen(animate = false)
        findViewById<View>(R.id.rowDetails).setOnClickListener {
            haptics.tick()
            detailsOpen = !detailsOpen
            prefs().edit().putBoolean(PREF_DETAILS_OPEN, detailsOpen).apply()
            applyDetailsOpen(animate = true)
        }

        findViewById<MaterialButton>(R.id.btnStop).setOnClickListener {
            haptics.heavy()
            leave()
        }

        RemoteStyling.applyTvFocusHighlight(this)
        // A definite starting point for D-pad navigation: on a TV box with no touchscreen the
        // first key press needs something focused to act on.
        btnRecenter.isFocusableInTouchMode = false
        btnRecenter.isFocusable = true
        btnRecenter.post { btnRecenter.requestFocus() }

        // Back never reveals the player behind us — for a cast that window is a grey rectangle,
        // since PC Link decodes into the glasses' presentation and not into it.
        onBackPressedDispatcher.addCallback(this) {
            // A black screen swallows every other input and turns it into a wake (see
            // [dispatchTouchEvent] and [dispatchKeyEvent]); Back must not be the exception that
            // tears the cast down instead. It cannot reuse those guards: at targetSdk 35+ the
            // platform intercepts Back before the view tree and routes it straight here, so
            // dispatchKeyEvent is never consulted for it.
            if (dim.isDimmed) {
                haptics.tick()
                dim.wake()
                return@addCallback
            }
            leave()
        }
    }

    /**
     * The card above the controls is also the eyes-free surface, for the same reason the film
     * remote has a touchpad: while the desktop is on the glasses the phone can't be looked at.
     *
     *   drag up/down -> media volume        (a light tick per step)
     *   long-press   -> re-centre the screen (heavy click — it took effect on the glasses)
     *
     * No tap action. There is nothing here a stray tap should be able to do, and taps already do
     * the one thing that matters in the dark: wake the screen (see [dispatchTouchEvent]).
     */
    private fun setupSurface() {
        val surface = findViewById<View>(R.id.surface)
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val volumeStepPx = resources.displayMetrics.density * 48f // ~one step per 48 dp of drag

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                swipeAxis = SwipeAxis.NONE
                volumeAccumPx = 0f
                return true
            }

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                distanceX: Float, distanceY: Float
            ): Boolean {
                val startX = e1?.x ?: return false
                val startY = e1.y
                // Lock to one axis once the movement is unambiguous, so a sloppy sideways drag
                // can't nudge the volume.
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

            override fun onLongPress(e: MotionEvent) {
                haptics.heavy()
                PcLinkSession.recenter()
                showFeedback(RECENTER_MARK)
            }
        })

        surface.setOnTouchListener { v, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            gestures.onTouchEvent(ev)
            true
        }
    }

    private fun adjustVolume(up: Boolean) {
        val am = audioManager ?: return
        // The phone's media stream is what the user hears only while the PC's sound is being played
        // here. Handed back to the computer (or never offered), a tick and a percentage would
        // confirm a change nobody can hear — and would quietly move the level it comes back at.
        val stats = PcLinkSession.stats()
        if (stats == null ||
            !PcLinkRemotePolicy.localVolumeIsHeard(stats.audioAvailable, stats.audioToGlasses)
        ) {
            // A glyph rather than a sentence (the slot is one 40sp mark wide) and deliberately no
            // haptic: on an eyes-free surface, silence under the finger IS the distinction. The
            // hint row under the routing switch already names where the sound is.
            showFeedback(SOUND_ON_PC_MARK)
            return
        }
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0 // no system volume UI — our own feedback text and haptic tick instead
        )
        haptics.tick()
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        showFeedback((if (up) "🔊 " else "🔉 ") + (current * 100 / max) + "%")
    }

    /** Big centre text showing the last action, fading out on its own. */
    private fun showFeedback(text: String) {
        feedbackFade?.cancel()
        tvFeedback.text = text
        tvFeedback.alpha = 1f
        feedbackFade = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 500
            duration = 500
            addUpdateListener { tvFeedback.alpha = it.animatedValue as Float }
            start()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        if (dim.isDimmed) {
            // Dark screen: any touch wakes it and is consumed, so a blind grab of the phone can't
            // mis-tap anything underneath.
            if (ev.action == MotionEvent.ACTION_DOWN) {
                haptics.tick()
                dim.wake()
            }
            return true
        }
        if (ev.action == MotionEvent.ACTION_DOWN) dim.schedule()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The D-pad equivalent: without this, a TV box with no touchscreen would dim after 5 s and
        // navigation would look frozen.
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (dim.isDimmed) {
                dim.wake()
                return true
            }
            dim.schedule()
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        dim.schedule()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        // What was collected describes seconds that were being watched; splicing across the gap
        // would draw a minute that never happened.
        history.reset()
        // Same reasoning for the dim policy: onResume arms from live state, and the first tick back
        // re-decides from scratch rather than against a state remembered from before the gap.
        lastLink = null
        dim.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance === this) currentInstance = null
    }

    /**
     * One reading, on our own clock — deliberately not driven by the session announcing a change: a
     * desktop nobody is touching stops changing, and "0 fps" is the answer then.
     */
    private fun sample() {
        val stats = PcLinkSession.stats()
        history.sample(stats, SystemClock.elapsedRealtime())
        if (stats == null) {
            // The session ended somewhere else (the glasses came off, a film claimed them, the PC
            // went away). There is nothing left for this screen to hold.
            leave()
            return
        }
        applyStats(stats)
    }

    private fun applyStats(stats: PcLinkSession.Stats) {
        tvTitle.text = stats.serverName
        tvLinkState.setText(
            when (stats.link) {
                PcLinkSession.Link.CONNECTING -> R.string.pclink_state_connecting
                PcLinkSession.Link.STREAMING -> R.string.pclink_state_streaming
                PcLinkSession.Link.RECONNECTING -> R.string.pclink_state_reconnecting
                PcLinkSession.Link.FAILED -> R.string.pclink_state_failed
            }
        )
        // The dim policy is only consulted when the timer is armed, and every arming point on this
        // screen is a user action or onResume — which lands while the link is still CONNECTING. So
        // the tick that already runs once a second is what tells the timer the answer changed.
        when (PcLinkRemotePolicy.dimAction(stats.link, lastLink)) {
            PcLinkRemotePolicy.Dim.LEAVE_ALONE -> Unit
            PcLinkRemotePolicy.Dim.ARM -> dim.schedule()
            PcLinkRemotePolicy.Dim.DISARM_AND_WAKE -> {
                dim.cancel()
                dim.wake()
            }
        }
        lastLink = stats.link
        // A dash until the second reading: one counter is not a rate, and a made-up zero in the
        // first second of a session would be the one lie this screen exists to avoid.
        val fps = history.fps.latest()
        val mbps = history.mbps.latest()
        fpsChip.render(
            if (fps == null) getString(R.string.pclink_value_none)
            else getString(R.string.pclink_value_fps, "%.0f".format(fps))
        )
        bitrateChip.render(
            if (mbps == null) getString(R.string.pclink_value_none)
            else getString(R.string.pclink_value_mbps, "%.1f".format(mbps))
        )
        applyAudioState(stats)
        if (detailsOpen) applyDetails(stats)
    }

    private fun applyAudioState(stats: PcLinkSession.Stats) {
        btnAudioRoute.isChecked = stats.audioToGlasses
        RemoteStyling.applyToggleStyle(btnAudioRoute, stats.audioToGlasses)
        btnAudioRoute.isEnabled = stats.audioAvailable
        // The button names the destination; the line under it names the other one, so neither
        // state has to be guessed at.
        tvAudioHint.setText(
            when {
                !stats.audioAvailable -> R.string.pclink_audio_none
                stats.audioToGlasses -> R.string.pclink_audio_hint_glasses
                else -> R.string.pclink_audio_hint_computer
            }
        )
    }

    /**
     * The player ended the session on its side — the glasses came off, a film claimed them, the
     * user picked something to watch. There is nothing left here to hold, so get out of the way.
     *
     * Deliberately just a `finish()`, where the user's own exit below navigates. Whoever ended the
     * session is already deciding what should be on screen next, and every one of those paths has
     * an answer: the player finishes too (so the user lands on the main screen), or it is being
     * reused for the film that displaced the cast (so the user lands on the film). Sending them to
     * the PC-Mirror tab from here would override that — and, worse, the [leave] route's CLEAR_TOP
     * would finish the very film that had just started.
     */
    fun onSessionEnded() {
        if (leaving) return
        leaving = true
        handler.removeCallbacks(tick)
        finish()
    }

    /**
     * The user's way out: ends the cast and goes home to the PC-Mirror tab.
     *
     * Back and Disconnect both land here, so the cast and its remote have one lifetime — there is
     * no state in which a desktop streams to the glasses with no visible remote. CLEAR_TOP is what
     * makes the destination stick: without it the player stays in the back stack underneath, and
     * the next Back press uncovers its empty window, which is the fault this screen was rebuilt to
     * remove.
     *
     * [PcLinkSession.end] is a no-op when nothing is running — the case when a tick discovered the
     * session had already gone without anyone telling us.
     */
    private fun leave() {
        if (leaving) return
        leaving = true
        handler.removeCallbacks(tick)
        PcLinkSession.end()
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_PC_MIRROR)
            addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        DisplayUtils.startOnPrimaryDisplay(this, intent)
        finish()
    }

    // --- the door ------------------------------------------------------------------------------

    private fun applyDetailsOpen(animate: Boolean) {
        detailsBox.visibility = if (detailsOpen) View.VISIBLE else View.GONE
        val rotation = if (detailsOpen) 180f else 0f
        if (animate) chevron.animate().rotation(rotation).setDuration(150).start()
        else chevron.rotation = rotation
        // The visible label stays "Details"; a screen reader is told which way the door goes, since
        // the chevron it would otherwise read is decorative.
        findViewById<View>(R.id.rowDetails).contentDescription = getString(
            if (detailsOpen) R.string.pclink_details_hide else R.string.pclink_details_show
        )
        if (detailsOpen) PcLinkSession.stats()?.let { applyDetails(it) }
    }

    /** Everything that answers "why isn't it working" — the seven rows behind the door. */
    private fun applyDetails(stats: PcLinkSession.Stats) {
        val lines = detailLines(stats)
        while (detailRows.size < lines.size) detailRows.add(addDetailRow())
        detailRows.forEachIndexed { index, (label, value) ->
            val line = lines.getOrNull(index)
            val row = label.parent as View
            if (line == null) {
                row.visibility = View.GONE
            } else {
                row.visibility = View.VISIBLE
                label.text = line.first
                value.text = line.second
            }
        }
    }

    private fun detailLines(stats: PcLinkSession.Stats): List<Pair<String, String>> {
        val none = getString(R.string.pclink_value_none)
        val format = if (stats.codec != null && stats.width > 0) {
            getString(
                R.string.pclink_value_format,
                stats.codec, stats.width, stats.height, stats.stereo.orEmpty()
            )
        } else {
            none
        }
        val audioOut = if (stats.audioRateHz > 0) {
            getString(R.string.pclink_value_audio_format, stats.audioRateHz / 1000, stats.audioChannels)
        } else {
            none
        }
        return listOf(
            getString(R.string.pclink_stat_latency) to
                getString(R.string.pclink_value_ms, "%.0f".format(stats.rttMs)),
            getString(R.string.pclink_stat_format) to format,
            getString(R.string.pclink_stat_dropped) to stats.droppedFrames.toString(),
            getString(R.string.pclink_stat_audio_output) to audioOut,
            getString(R.string.pclink_stat_audio_buffer) to
                getString(R.string.pclink_value_ms, stats.audioBufferedMs.toString()),
            getString(R.string.pclink_stat_skew) to
                (stats.audioSkewMs?.let { getString(R.string.pclink_value_ms, it.toString()) } ?: none),
            getString(R.string.pclink_stat_audio_dropouts) to stats.audioDropouts.toString()
        )
    }

    private fun addDetailRow(): Pair<TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * resources.displayMetrics.density).toInt() }
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setTextColor(getColor(R.color.rc_secondary))
        }
        val value = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 13f
            maxLines = 1
            setTextColor(getColor(R.color.rc_on_surface))
        }
        row.addView(label)
        row.addView(value)
        detailsBox.addView(row)
        return label to value
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** One chip: the title, the number, and the minute of itself under it. */
    private class Chip(private val root: View, private val title: String, window: SparklineWindow) {
        private val value: TextView = root.findViewById(R.id.tvChipValue)
        private val sparkline: SparklineView = root.findViewById(R.id.sparkline)

        init {
            root.findViewById<TextView>(R.id.tvChipTitle).text = title
            // The remote paints its own dark card whatever the system theme is doing, so the chart
            // is told what it is drawing on rather than reading a theme that may be in light mode.
            sparkline.setSurfaceColor(root.context.getColor(R.color.rc_on_surface))
            sparkline.setWindow(window)
        }

        fun render(text: String) {
            value.text = text
            // The label and the number are one fact to a screen reader, and the chart is that same
            // fact drawn again (which is why the chart itself is hidden from it).
            root.contentDescription = "$title: $text"
            sparkline.invalidate()
        }
    }

    companion object {
        /** Live remote, so the player can take it down the instant a session ends on its side. */
        @Volatile
        var currentInstance: PcLinkRemoteActivity? = null

        private const val SAMPLE_INTERVAL_MS = 1_000L

        /**
         * The feedback slot is one big glyph wide (40sp, centred, single-line), so everything that
         * lands in it is a mark rather than a sentence — a word here wraps across the card and over
         * the sparklines, and it cannot be read from under the goggles anyway.
         */
        private const val RECENTER_MARK = "⌖"
        private const val SOUND_ON_PC_MARK = "🖥"

        // Same store and key the PC-Mirror tab used for this door, so a user who opened it there
        // finds it open here.
        private const val PREFS = "pc_mirror"
        private const val PREF_DETAILS_OPEN = "details_open"
    }
}
