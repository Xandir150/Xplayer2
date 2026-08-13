package com.teleteh.xplayer2.ui.pclink

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.teleteh.xplayer2.data.network.PcLinkHidKeys
import com.teleteh.xplayer2.data.network.PcLinkInputProtocol
import com.teleteh.xplayer2.data.network.PcLinkInputSender
import com.teleteh.xplayer2.data.network.PcPointerScaler
import com.teleteh.xplayer2.data.network.PcWheelAccumulator
import kotlin.math.abs

/**
 * The remote's pad, while it is driving the PC (`protocol.md` §2.19).
 *
 * Everything that turns a human gesture into an [PcLinkInputSender] call lives here rather than in
 * [PcLinkRemoteActivity]: three separate input sources land on the same sender and each has its own
 * conventions to reconcile, which is enough to be worth its own file.
 *
 * ## A finger is a touchpad, not a tablet
 *
 * Dragging nudges the cursor and lifting repositions the finger — you do **not** teleport the cursor
 * to where you touched. That is not a preference; it is the only thing that works when the desktop
 * is on the glasses and the pad is in your hand, unlooked at. The pad has no picture on it, so
 * there is nothing on it to point *at*.
 *
 * [absoluteTaps] is the exception, and it is a mode the user asks for by name: with it on, the pad
 * *is* the desktop, aspect-corrected, and a tap sends the cursor to the matching spot. This is what
 * makes the letterbox half of the coordinate map real on a phone that shows no video — the pad is
 * almost never the shape of the desktop, so the desktop is fitted inside it with bars, and a tap on
 * a bar is nowhere and sends nothing rather than slamming the cursor into an edge.
 *
 * ## A captured mouse is the same path, already relative
 *
 * A real mouse plugged into the phone reports deltas, which is what the wire wants, so pointer
 * capture is not a special case so much as the honest one. Capture has a cost the user must be told
 * about — the phone's own cursor vanishes, and nothing on screen reacts to the mouse any more — so
 * it is taken only while control mode is on and given back on Back, on losing focus, and on leaving.
 *
 * ## Keys travel by position, characters by value
 *
 * A physical key becomes a HID usage through [PcLinkHidKeys] and lets the PC's layout decide what it
 * types. Anything with no physical position behind it — an IME, an on-screen keyboard — goes down
 * the text path instead, through [PcTextInputView]. Auto-repeat is deliberately not forwarded: the
 * PC repeats a held key on its own, and sending both gives a doubled repeat rate that reads as a
 * stuck keyboard.
 */
class PcLinkInputSurface(
    private val surface: View,
    /** The live sender, or null the instant the session or the PC's permission goes away. */
    private val sender: () -> PcLinkInputSender?,
    /** The desktop's shape (one eye wide), for [absoluteTaps]. Null when it isn't known yet. */
    private val contentSize: () -> Pair<Int, Int>?,
    private val onTap: () -> Unit = {},
    private val onDragStart: () -> Unit = {}
) {

    /** Whether gestures drive the PC at all. False puts the pad back to volume and re-centre. */
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            cancelLongPress()
            resetGesture()
        }

    /** Whether a tap points at the desktop (absolute) instead of clicking where the cursor is. */
    var absoluteTaps = false

    private val touchSlop = ViewConfiguration.get(surface.context).scaledTouchSlop
    private val tapTimeoutMs = ViewConfiguration.getTapTimeout().toLong()
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()

    private val touchScaler = PcPointerScaler()
    private val mouseScaler = PcPointerScaler()
    private val touchWheel = PcWheelAccumulator(
        pixelsPerNotch = surface.resources.displayMetrics.density * 48f
    )
    private val mouseWheel = PcWheelAccumulator(pixelsPerNotch = 1f)

    private var downAtMs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedBeyondSlop = false
    private var maxPointers = 0
    private var holdingLeft = false

    private val longPress = Runnable {
        val s = sender() ?: return@Runnable
        // A long press starts a drag rather than clicking: press and hold, and the release comes
        // with the finger lifting. This is the one gesture that leaves a button down between
        // events, which is exactly why the sender tracks what it is holding.
        holdingLeft = true
        s.button(PcLinkInputProtocol.BUTTON_LEFT, true)
        onDragStart()
    }

    // ---------------------------------------------------------------------------------------
    // Finger
    // ---------------------------------------------------------------------------------------

    /** Returns true when the gesture was consumed as PC input, false to leave it to the remote. */
    fun onTouch(event: MotionEvent): Boolean {
        if (!enabled) return false
        val s = sender() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAtMs = event.eventTime
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                movedBeyondSlop = false
                maxPointers = 1
                holdingLeft = false
                touchScaler.reset()
                touchWheel.reset()
                surface.postDelayed(longPress, longPressTimeoutMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                // Two fingers is a scroll or a right click, never a drag.
                cancelLongPress()
                releaseHeldLeft(s)
                lastX = centroidX(event)
                lastY = centroidY(event)
                touchWheel.reset()
            }

            MotionEvent.ACTION_MOVE -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                val x = centroidX(event)
                val y = centroidY(event)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                if (!movedBeyondSlop &&
                    (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop)
                ) {
                    movedBeyondSlop = true
                    if (!holdingLeft) cancelLongPress()
                }
                if (!movedBeyondSlop) return true
                if (event.pointerCount >= 2) {
                    // Finger up scrolls the page down, the same way it does everywhere else on the
                    // phone, and the wire's sign convention already agrees.
                    val notches = touchWheel.fromPixels(dy)
                    if (notches != 0) s.wheel(0, notches)
                } else if (maxPointers < 2) {
                    val (nx, ny) = touchScaler.scale(
                        dx, dy, surface.width, surface.height, POINTER_GAIN
                    )
                    s.move(nx, ny)
                }
                // A gesture that ever had two fingers on it stays a scroll to the end. Letting it
                // become a drag when one finger lifts sends the cursor flying: the reference point
                // was the pair's centroid, and the remaining finger is half a hand away from it.
            }

            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (holdingLeft) {
                    releaseHeldLeft(s)
                } else if (!movedBeyondSlop && event.eventTime - downAtMs <= tapTimeoutMs) {
                    tap(s, if (maxPointers >= 2) PcLinkInputProtocol.BUTTON_RIGHT else PcLinkInputProtocol.BUTTON_LEFT)
                }
                resetGesture()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                releaseHeldLeft(s)
                resetGesture()
            }
        }
        return true
    }

    private fun tap(s: PcLinkInputSender, button: Int) {
        // Point first, then click — in that order, in one batch, so the click lands where the tap
        // was rather than where the cursor happened to be.
        if (absoluteTaps && button == PcLinkInputProtocol.BUTTON_LEFT) {
            val content = contentSize()
            if (content != null) {
                val norm = PcLinkInputProtocol.viewToContent(
                    downX.toInt(),
                    downY.toInt(),
                    surface.width,
                    surface.height,
                    content.first,
                    content.second
                )
                // Null is a tap on a letterbox bar: there is no desktop under it, so the whole tap
                // is dropped rather than clicking wherever the cursor last was.
                    ?: return
                s.moveAbs(norm.first, norm.second)
            }
        }
        s.button(button, true)
        s.button(button, false)
        onTap()
    }

    private fun centroidX(event: MotionEvent): Float {
        if (event.pointerCount < 2) return event.x
        return (event.getX(0) + event.getX(1)) / 2f
    }

    private fun centroidY(event: MotionEvent): Float {
        if (event.pointerCount < 2) return event.y
        return (event.getY(0) + event.getY(1)) / 2f
    }

    // ---------------------------------------------------------------------------------------
    // Captured mouse
    // ---------------------------------------------------------------------------------------

    /**
     * A captured pointer event: already relative, which is the whole reason capture is worth its
     * cost. Deltas are measured against the phone's own screen, so a sweep that would have crossed
     * the phone crosses the desktop.
     */
    fun onCapturedPointer(event: MotionEvent): Boolean {
        if (!enabled) return false
        val s = sender() ?: return false
        val metrics = surface.resources.displayMetrics
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val (nx, ny) = mouseScaler.scale(
                    event.x, event.y, metrics.widthPixels, metrics.heightPixels, MOUSE_GAIN
                )
                s.move(nx, ny)
            }

            MotionEvent.ACTION_BUTTON_PRESS -> buttonOf(event.actionButton)?.let { s.button(it, true) }
            MotionEvent.ACTION_BUTTON_RELEASE -> buttonOf(event.actionButton)?.let { s.button(it, false) }

            MotionEvent.ACTION_SCROLL -> {
                val v = mouseWheel.fromDetents(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val hn = if (h == 0f) 0 else (h * PcLinkInputProtocol.WHEEL_NOTCH).toInt()
                if (v != 0 || hn != 0) s.wheel(hn, v)
            }
        }
        return true
    }

    private fun buttonOf(actionButton: Int): Int? = when (actionButton) {
        MotionEvent.BUTTON_PRIMARY -> PcLinkInputProtocol.BUTTON_LEFT
        MotionEvent.BUTTON_SECONDARY -> PcLinkInputProtocol.BUTTON_RIGHT
        MotionEvent.BUTTON_TERTIARY -> PcLinkInputProtocol.BUTTON_MIDDLE
        // A fourth or fifth button is not guessed at: the protocol has three, and a back/forward
        // button injected as "middle" would be worse than one that does nothing.
        else -> null
    }

    // ---------------------------------------------------------------------------------------
    // Keys
    // ---------------------------------------------------------------------------------------

    /**
     * Forwards one key event to the PC by its physical position, or leaves it alone.
     *
     * Returning false is the important half: Back is never forwarded (it is the way out of pointer
     * capture and out of this screen), and neither is any key with no injectable usage behind it —
     * so the phone's own volume keys keep working while control is on.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false
        val s = sender() ?: return false
        val usage = PcLinkHidKeys.usageOf(event.scanCode, event.keyCode) ?: return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // The PC repeats a held key itself; forwarding Android's repeats as well doubles
                // the rate and fills a text field with a held arrow key.
                if (event.repeatCount == 0) s.key(usage, true)
            }

            KeyEvent.ACTION_UP -> s.key(usage, false)
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Letting go
    // ---------------------------------------------------------------------------------------

    /**
     * Releases everything this pad is holding on the PC.
     *
     * Called on every way out — control switched off, the screen pausing, the session ending, the
     * user leaving. §2.19.5 has the *server* release on the death of a session, which covers none
     * of these: the session is alive in all of them, and a Ctrl left down on someone's desktop
     * turns their every later keystroke into a shortcut.
     */
    fun releaseHeld() {
        cancelLongPress()
        holdingLeft = false
        resetGesture()
        sender()?.releaseAll()
    }

    private fun releaseHeldLeft(s: PcLinkInputSender) {
        if (!holdingLeft) return
        holdingLeft = false
        s.button(PcLinkInputProtocol.BUTTON_LEFT, false)
    }

    private fun cancelLongPress() {
        surface.removeCallbacks(longPress)
    }

    private fun resetGesture() {
        movedBeyondSlop = false
        maxPointers = 0
        touchScaler.reset()
        mouseScaler.reset()
        touchWheel.reset()
        mouseWheel.reset()
    }

    companion object {
        /**
         * How far the cursor travels for a full sweep of the pad: a little over one desktop width,
         * so the whole screen is reachable in one gesture without the pad feeling twitchy. The
         * finger has far less room than a mouse, hence the difference from [MOUSE_GAIN].
         */
        private const val POINTER_GAIN = 1.6f

        /** A captured mouse crossing the phone's screen crosses the desktop, one for one. */
        private const val MOUSE_GAIN = 1.0f
    }
}

/**
 * The IME's landing place: an invisible, focusable view whose only job is to catch what a soft
 * keyboard produces and hand it to the PC as text (§2.19.4's `"s"` path).
 *
 * This exists because the position path cannot carry a `ё`, a `字` or an emoji: there is no HID
 * usage for a character, only for a *place on a keyboard*, and the phone's on-screen keyboard has no
 * places. The two paths split exactly there — control the PC by position, type text by character.
 *
 * The input type is deliberately `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`: it is the setting that
 * makes IMEs stop composing and autocorrecting, so each character arrives as its own
 * [InputConnection.commitText] instead of a word appearing at the end of the sentence. Composition
 * cannot be forwarded — the PC has no way to un-insert a half-typed word — so the alternative is
 * either a lag of one word or a stream of corrections; not composing at all is neither.
 */
class PcTextInputView(context: Context) : View(context) {

    /** A committed string to insert on the PC. */
    var onText: (String) -> Unit = {}

    /** A key the IME sends as a key event rather than as text (Enter, Backspace, the arrows). */
    var onKey: (KeyEvent) -> Boolean = { false }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) onText(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Nothing to show and nothing to send: with composition disabled above this should
                // not arrive, and an IME that insists is answered with "accepted, ignored" rather
                // than with text the PC would then have to be told to delete.
                return true
            }

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // The IME's own backspace. There is no buffer here to delete from, so it becomes
                // the physical key it stands for — which is what the PC needs anyway.
                repeat(beforeLength.coerceIn(0, MAX_DELETE)) {
                    onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event == null) return false
                // A key the PC has no position for is handed back to the platform rather than
                // swallowed here: this view is invisible, so a swallowed key vanishes with no
                // sign of where it went.
                if (onKey(event)) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    private companion object {
        /** A sane bound on one delete request, so a confused IME cannot hold Backspace forever. */
        const val MAX_DELETE = 64
    }
}
