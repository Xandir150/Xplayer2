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
    /** The whole row is the tap target; the switch inside it is only the affordance. */
    private lateinit var btnAudioRoute: LinearLayout
    private lateinit var ivAudioRoute: ImageView
    private lateinit var tvAudioRoute: TextView
    private lateinit var swAudioRoute: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnRecenter: MaterialButton
    private lateinit var chevron: ImageView
    private lateinit var detailsBox: LinearLayout
    private lateinit var fpsChip: Chip
    private lateinit var bitrateChip: Chip

    /** The detail rows, built once and then only re-texted — one row per line of [detailLines]. */
    private val detailRows = ArrayList<Pair<TextView, TextView>>()
    private var detailsOpen = false

    /** The "control the PC" block: gone entirely until the session says something about input. */
    private lateinit var boxInput: LinearLayout
    private lateinit var btnInputControl: LinearLayout
    private lateinit var swInputControl: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnInputAbsolute: LinearLayout
    private lateinit var swInputAbsolute: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var btnInputKeyboard: MaterialButton
    private lateinit var tvInputHint: TextView
    private lateinit var tvSurfaceHint: TextView

    private lateinit var inputSurface: PcLinkInputSurface
    private lateinit var textInput: PcTextInputView

    /**
     * Whether the user has asked this pad to drive the PC.
     *
     * Deliberately not remembered across sessions. Control is a thing you turn on to do something
     * and it changes what every gesture on this screen means; coming back to a remote that is
     * silently already driving a computer — with the volume drag gone and the pad clicking things —
     * is a surprise nobody asked for.
     */
    private var controlOn = false

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
        ivAudioRoute = findViewById(R.id.ivAudioRoute)
        tvAudioRoute = findViewById(R.id.tvAudioRoute)
        swAudioRoute = findViewById(R.id.swAudioRoute)
        btnRecenter = findViewById(R.id.btnRecenter)
        boxInput = findViewById(R.id.boxInput)
        btnInputControl = findViewById(R.id.btnInputControl)
        swInputControl = findViewById(R.id.swInputControl)
        btnInputAbsolute = findViewById(R.id.btnInputAbsolute)
        swInputAbsolute = findViewById(R.id.swInputAbsolute)
        btnInputKeyboard = findViewById(R.id.btnInputKeyboard)
        tvInputHint = findViewById(R.id.tvInputHint)
        tvSurfaceHint = findViewById(R.id.tvSurfaceHint)
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
        setupInput()

        btnRecenter.setOnClickListener {
            haptics.click()
            PcLinkSession.recenter()
            // The same mark the long-press leaves (see [setupSurface]), because it is the same
            // action. The slot is one 40sp glyph wide; a sentence in it wrapped across the card.
            showFeedback(RECENTER_MARK)
        }

        btnAudioRoute.setOnClickListener {
            haptics.click()
            // Truth is the session, never the widget. The switch inside the row is not clickable
            // and is only ever set from the session's own state, so it cannot get ahead of it —
            // which is exactly how the old checkable button managed to send the value that was
            // already in force and do nothing at all. See [PcLinkRemotePolicy.audioTapCommand].
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
            // The way out of pointer capture, and the only one there is: while the mouse is
            // captured the phone's cursor is gone and nothing on screen can be aimed at, so Back
            // has to mean "give me my phone back" before it can mean "end the cast". The hint
            // under the pad says so while it is held.
            if (findViewById<View>(R.id.surface).hasPointerCapture()) {
                haptics.click()
                setControl(false)
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
            // Driving the PC takes the pad over completely: while control is on, this is a
            // touchpad and nothing else. Sharing it with the volume drag was tried and is worse
            // than either — every gesture becomes ambiguous, and the ambiguity is resolved
            // differently by the two things the user cannot see.
            if (inputSurface.onTouch(ev)) return@setOnTouchListener true
            gestures.onTouchEvent(ev)
            true
        }
    }

    /**
     * The mouse-and-keyboard block (`protocol.md` §2.19).
     *
     * Everything here stays invisible until the PC has actually granted input — see [applyInputState]
     * — because the alternative is a switch that a user flips and nothing happens, on a screen they
     * cannot see while they are using it.
     */
    private fun setupInput() {
        val surface = findViewById<View>(R.id.surface)
        inputSurface = PcLinkInputSurface(
            surface = surface,
            sender = { PcLinkSession.input() },
            contentSize = ::desktopContentSize,
            onTap = { haptics.tick() },
            onDragStart = { haptics.heavy() }
        )

        // The IME's landing place. Built here rather than in the layout because it is a mechanism,
        // not a control: it is never seen and nothing navigates to it.
        //
        // One pixel, not zero. From targetSdk P onwards a laid-out view with no size cannot take
        // focus at all (`View.canTakeFocus`), and a view that cannot take focus never gets an
        // InputConnection — so the keyboard button would open an IME that types into nothing.
        textInput = PcTextInputView(this).apply {
            onText = { s -> PcLinkSession.input()?.text(s) }
            onKey = { event -> inputSurface.onKey(event) }
        }
        (surface as ViewGroup).addView(textInput, 1, 1)

        btnInputControl.setOnClickListener {
            haptics.click()
            setControl(!controlOn)
        }
        btnInputAbsolute.setOnClickListener {
            haptics.click()
            inputSurface.absoluteTaps = !inputSurface.absoluteTaps
            swInputAbsolute.isChecked = inputSurface.absoluteTaps
            applySurfaceHint()
        }
        btnInputKeyboard.setOnClickListener {
            haptics.click()
            showPcKeyboard()
        }
        // Captured pointer events go to whichever view holds focus, and focus moves to [textInput]
        // the moment the user opens the keyboard. Listening on both is two lines and removes the
        // whole class of "the mouse stopped working after I typed something".
        surface.setOnCapturedPointerListener { _, ev -> inputSurface.onCapturedPointer(ev) }
        textInput.setOnCapturedPointerListener { _, ev -> inputSurface.onCapturedPointer(ev) }
    }

    /**
     * Turns driving the PC on or off, and everything that hangs off it.
     *
     * The off path is the one that matters, and it runs on every route out — the switch, the
     * session ending, the PC withdrawing permission, this screen pausing. It releases whatever is
     * held down on the PC first, because §2.19.5 only makes the *server* let go when a session
     * dies, and none of these kills the session.
     */
    private fun setControl(on: Boolean) {
        controlOn = on
        inputSurface.enabled = on
        swInputControl.isChecked = on
        btnInputAbsolute.visibility = if (on) View.VISIBLE else View.GONE
        btnInputKeyboard.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            // A dark screen swallows the first touch of every gesture to wake itself (see
            // [dispatchTouchEvent]), which on a touchpad means every gesture after a pause is
            // eaten. Control and dimming cannot both be on, so control wins while it is on.
            dim.cancel()
            dim.wake()
            syncPointerCapture()
        } else {
            releasePointer()
            hidePcKeyboard()
            inputSurface.releaseHeld()
            inputSurface.absoluteTaps = false
            swInputAbsolute.isChecked = false
            dim.schedule()
        }
        applySurfaceHint()
    }

    /**
     * Takes the phone's physical mouse, if one is attached.
     *
     * Capture is what turns a mouse into the stream of deltas the wire wants, and there is no
     * halfway version of it: while it is held, the phone's own cursor disappears and nothing on
     * this screen reacts to the mouse at all. That is a real cost, so it is taken only while
     * control is on, and given back on Back (see the back callback), on losing window focus, and on
     * every path through [setControl] and [onPause]. The hint under the pad says so, because a
     * vanished cursor with no explanation is indistinguishable from a crash.
     */
    private fun capturePointer() {
        val surface = findViewById<View>(R.id.surface)
        surface.isFocusableInTouchMode = true
        surface.isFocusable = true
        surface.requestFocus()
        surface.requestPointerCapture()
    }

    private fun releasePointer() {
        val surface = findViewById<View>(R.id.surface)
        if (surface.hasPointerCapture()) surface.releasePointerCapture()
    }

    /**
     * Takes the mouse when there is one and gives it back when there is not.
     *
     * Capture is a window mode, not a device binding: it succeeds whether or not a mouse is
     * plugged in. Requesting it unconditionally would put "Mouse captured — Back releases it" under
     * the pad of every phone with no mouse anywhere near it, which is worse than saying nothing.
     *
     * Re-checked on the one-second tick as well as on the switch, so a mouse plugged in *after*
     * control was turned on is picked up. That is the case the bug report was about: the phone sees
     * the mouse — Android enumerates it — and until something asks for its events, nothing happens.
     */
    private fun syncPointerCapture() {
        val surface = findViewById<View>(R.id.surface)
        // Not while the keyboard is up. Capture is requested on a *focused* view, so re-taking it
        // here would pull focus off the text view once a second and shut the IME under the user's
        // fingers. Captured events reach the same handler from either view (see [setupInput]), so
        // there is nothing to win by fighting for focus.
        if (textInput.hasFocus()) return
        val want = controlOn && hasPhysicalMouse()
        if (want && !surface.hasPointerCapture()) {
            capturePointer()
        } else if (!want && surface.hasPointerCapture()) {
            releasePointer()
        }
    }

    /** Whether a real mouse or trackpad is attached to this phone right now. */
    private fun hasPhysicalMouse(): Boolean = android.view.InputDevice.getDeviceIds().any { id ->
        val device = android.view.InputDevice.getDevice(id) ?: return@any false
        if (device.isVirtual) return@any false
        val sources = device.sources
        (sources and android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE ||
            (sources and android.view.InputDevice.SOURCE_MOUSE_RELATIVE) ==
            android.view.InputDevice.SOURCE_MOUSE_RELATIVE
    }

    private fun showPcKeyboard() {
        textInput.requestFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.showSoftInput(textInput, 0)
    }

    private fun hidePcKeyboard() {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(textInput.windowToken, 0)
    }

    /**
     * The desktop's shape as the phone is showing it, one eye wide — what an absolute tap is a
     * fraction of.
     *
     * An `sbs` stream is a double-width frame carrying both eyes, and the viewer sees one of them.
     * Feeding the packed width in here would halve every horizontal position, which looks like a
     * pad that only reaches the left half of the screen.
     */
    private fun desktopContentSize(): Pair<Int, Int>? {
        val stats = PcLinkSession.stats() ?: return null
        if (stats.width <= 0 || stats.height <= 0) return null
        val width = if (stats.stereo == PC_STEREO_SBS) stats.width / 2 else stats.width
        return width to stats.height
    }

    /** What the pad does right now, in the faint line under it. */
    private fun applySurfaceHint() {
        tvSurfaceHint.setText(
            when {
                !controlOn -> R.string.pclink_remote_surface_hint
                findViewById<View>(R.id.surface).hasPointerCapture() ->
                    R.string.pclink_input_hint_captured
                inputSurface.absoluteTaps -> R.string.pclink_input_hint_absolute
                else -> R.string.pclink_input_surface_hint
            }
        )
    }

    /**
     * Shows what this session says about input, and takes control away when it stops saying yes.
     *
     * The two refusals are spelled out rather than merged: one is fixed by re-pairing this phone,
     * the other by a switch on the PC, and a user who is told only "unavailable" has no way to know
     * which — see [PcLinkRemotePolicy.inputRow].
     */
    private fun applyInputState(stats: PcLinkSession.Stats) {
        val row = PcLinkRemotePolicy.inputRow(stats.input)
        boxInput.visibility =
            if (row == PcLinkRemotePolicy.InputRow.HIDDEN) View.GONE else View.VISIBLE
        val ready = row == PcLinkRemotePolicy.InputRow.READY
        btnInputControl.isEnabled = ready
        swInputControl.isEnabled = ready
        btnInputControl.alpha = if (ready) 1f else 0.45f
        tvInputHint.setText(
            when (row) {
                PcLinkRemotePolicy.InputRow.NOT_ENCRYPTED -> R.string.pclink_input_hint_not_encrypted
                PcLinkRemotePolicy.InputRow.OPERATOR_OFF -> R.string.pclink_input_hint_operator_off
                else -> R.string.pclink_input_hint_ready
            }
        )
        // Permission can be withdrawn mid-session, and when it is, whatever this pad was holding
        // has to be let go of on the way down.
        if (!PcLinkRemotePolicy.controlHolds(controlOn, stats.input)) {
            if (controlOn) setControl(false)
            return
        }
        // A mouse plugged in mid-session is picked up on this tick rather than on the next time
        // the user thinks to toggle the switch.
        syncPointerCapture()
        applySurfaceHint()
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
        // A keyboard attached to the phone belongs to the PC while control is on — that is the
        // whole feature. It is consulted before the view tree, and never for Back or for a key with
        // no injectable position behind it, so the way out of this screen and the phone's own
        // volume keys keep working with someone's hand on the keyboard.
        if (controlOn && inputSurface.onKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Losing the window gives the mouse back.
     *
     * The platform already drops pointer capture when the window loses focus, so this is not what
     * frees the mouse — it is what stops this screen from believing it still has it. A hint that
     * says "Mouse captured" over a session that no longer is, is worse than no hint.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Focus can be reported before the views exist on a window that never finished coming up.
        if (!::inputSurface.isInitialized) return
        if (!hasFocus) {
            inputSurface.releaseHeld()
        } else {
            syncPointerCapture()
        }
        applySurfaceHint()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        dim.schedule()
    }

    override fun onPause() {
        super.onPause()
        // Whatever this pad is holding on the PC goes with it. The session survives a pause — the
        // desktop is still on the glasses — so nothing on the PC would otherwise let go, and a Ctrl
        // held at the moment a call came in would turn every later keystroke on that computer into
        // a shortcut. Control itself is switched off too, so coming back is a deliberate act.
        if (controlOn) setControl(false)
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
        applyInputState(stats)
        if (detailsOpen) applyDetails(stats)
    }

    private fun applyAudioState(stats: PcLinkSession.Stats) {
        // Both ends of the choice are on screen at once: the icon is where the sound is going
        // now, the switch is how to send it elsewhere. iOS reads the same way, and a control that
        // only changed a word was the thing that made this one easy to misread.
        swAudioRoute.isChecked = stats.audioToGlasses
        ivAudioRoute.setImageResource(
            if (stats.audioToGlasses) R.drawable.ic_glasses else R.drawable.ic_computer_24
        )
        tvAudioRoute.setText(
            if (stats.audioToGlasses) R.string.pclink_audio_to_glasses
            else R.string.pclink_audio_to_computer
        )
        btnAudioRoute.isEnabled = stats.audioAvailable
        swAudioRoute.isEnabled = stats.audioAvailable
        // The row carries the whole control's state, so a disabled one dims as a unit rather than
        // leaving a bright icon over a dead switch.
        btnAudioRoute.alpha = if (stats.audioAvailable) 1f else 0.45f
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
     * The PC has forgotten this phone (§8.4), and only a fresh six-digit ceremony can answer that.
     *
     * The player found out while stopped behind this screen and a stopped activity may not launch
     * one — so the screen that *is* in front makes the hand-off, and the ceremony stays one tap
     * away instead of a bare "Disconnected" with no route out of it. Returns false when this remote
     * is already leaving, so the caller keeps the request pending rather than dropping it.
     */
    fun startRepair(intent: Intent): Boolean {
        if (leaving || isFinishing) return false
        leaving = true
        handler.removeCallbacks(tick)
        startActivity(intent)
        finish()
        return true
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
        // Before the session goes, not after: once [PcLinkSession.end] has run there is no sender
        // left to carry the releases, and the phone would be relying on the PC noticing the socket
        // die to work out that Ctrl is still down. It does notice — §2.19.5 requires it — but the
        // half of that promise this phone owns is saying so while it still can.
        if (controlOn) setControl(false)
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
            // A dash until a pong has actually come back, for the same reason the two chips above
            // show one until the second reading: "0 ms" to a PC across a LAN is not a measurement,
            // it is the sentinel for never having taken one — and the door is opened precisely in
            // the two states where that is what it means (the first seconds, and after a drop).
            getString(R.string.pclink_stat_latency) to
                (stats.rttMs?.let { getString(R.string.pclink_value_ms, "%.0f".format(it)) } ?: none),
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
        /** `config.stereo` for a packed left|right frame — the case an absolute tap must halve. */
        private const val PC_STEREO_SBS = "sbs"

        private const val PREFS = "pc_mirror"
        private const val PREF_DETAILS_OPEN = "details_open"
    }
}
