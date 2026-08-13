package com.teleteh.xplayer2.data.network

/**
 * The client half of §2.19: turns a stream of gestures and key presses into `input` messages,
 * coalesced per §2.19.5, and keeps track of what it is holding down so it can let go.
 *
 * Pure and sans-io — a producer (the UI thread) calls the verbs, a consumer (the control writer
 * coroutine) calls [drain] — so every rule below is a unit test rather than a thing to try on a
 * desktop and hope.
 *
 * ## Coalescing, and why motion is the only thing coalesced
 *
 * A finger on glass samples at 120–240 Hz and a mouse reports faster still. Sealing an envelope per
 * sample would be one encryption, one counter step (§2.18.5) and one TCP write for a motion no
 * human can perceive on its own. So relative deltas are **summed** and absolute positions are
 * **latest-wins** into a single pending slot, and the whole interval leaves as one message.
 *
 * Buttons, keys, wheel notches and text are **not** coalesced: their timing is their meaning — a
 * click is a moment, not an average — so each one flushes the pending motion ahead of it (order
 * inside a batch is load-bearing: a move, a button-down and another move mean what they would as
 * three messages) and then asks the writer to go now.
 *
 * ## Letting go
 *
 * §2.19.5 makes the *receiver* release everything on the death of a session, which covers a crash, a
 * dropped link and a clean close. It does not cover the case this class exists for: the session
 * living on while the *sender* stops sending — the user leaving the remote screen, the app going to
 * background, pointer capture ending — with Ctrl still down. Nothing on the PC ends, so nothing on
 * the PC releases, and every later keystroke on that desktop becomes a shortcut. [releaseAll]
 * enqueues the releases explicitly, buttons first and then keys in ascending usage order, which
 * frees a letter before the modifier that was holding it — the same order the reference server uses
 * on its own teardown.
 */
class PcLinkInputSender(
    /** What the server said it will act on (`config.input`, §2.19.1). Modes it did not offer are dropped here rather than sent and ignored. */
    private val offer: PcInputOffer,
    /**
     * Nudges the control writer, which sleeps for a fifth of a second when nothing is happening.
     *
     * Called for *every* kind of event, including motion, and that is not a contradiction of the
     * coalescing: the writer ignores nudges while it is already running at frame rate, so this only
     * ever ends an idle sleep. Without it the first sample of a gesture would wait out that sleep
     * and the drag would begin with a jump; with it, the writer wakes on the first sample and the
     * rest of the gesture rides the frame cadence and coalesces normally.
     */
    private val wake: () -> Unit = {}
) {

    /** Pending coalesced motion: relative deltas summed, or one absolute position. */
    private sealed class Pending {
        class Rel(var dx: Int, var dy: Int) : Pending()
        class Abs(var x: Int, var y: Int) : Pending()
    }

    private val lock = Any()
    private val queue = ArrayList<PcInputEvent>(64)
    private var pending: Pending? = null
    private val heldButtons = HashSet<Int>()
    private val heldKeys = HashSet<Int>()

    /**
     * How many events may sit unsent before motion starts being discarded.
     *
     * Only motion is ever dropped, and only when the writer has stalled badly enough that the
     * pointer's history is worthless anyway. A button or key transition is never dropped at any
     * depth: dropping a *release* is exactly how a key gets stuck on someone's desktop, which is
     * the one failure this whole class is arranged to prevent.
     */
    private val queueCap = 4 * PcLinkInputProtocol.EVENT_CAP

    /** Motion events discarded by [queueCap]. Non-zero means the control writer fell behind. */
    @Volatile
    var droppedMotion: Long = 0L
        private set

    /** Relative pointer motion in normalized content units — the primary path (§2.19.3). */
    fun move(dx: Int, dy: Int) {
        if (!offer.hasRelative) return
        if (dx == 0 && dy == 0) return
        synchronized(lock) {
            if (queue.size >= queueCap) {
                droppedMotion++
                return
            }
            when (val p = pending) {
                is Pending.Rel -> {
                    p.dx += dx
                    p.dy += dy
                }
                else -> {
                    flushPendingLocked()
                    pending = Pending.Rel(dx, dy)
                }
            }
        }
        wake()
    }

    /**
     * Absolute pointer position, `0..`[PcLinkInputProtocol.ABS_MAX] across the content — the
     * tap-to-point path. Latest wins within one interval: two taps in 16 ms are one destination.
     *
     * Out-of-range coordinates are refused here rather than sent for the server to drop, so a
     * caller that mapped a touch wrongly gets nothing instead of a cursor in the corner.
     */
    fun moveAbs(x: Int, y: Int) {
        if (!offer.hasAbsolute) return
        if (x !in 0..PcLinkInputProtocol.ABS_MAX || y !in 0..PcLinkInputProtocol.ABS_MAX) return
        synchronized(lock) {
            if (queue.size >= queueCap) {
                droppedMotion++
                return
            }
            when (val p = pending) {
                is Pending.Abs -> {
                    p.x = x
                    p.y = y
                }
                else -> {
                    flushPendingLocked()
                    pending = Pending.Abs(x, y)
                }
            }
        }
        wake()
    }

    /** A button press or release. `0` left, `1` right, `2` middle. */
    fun button(b: Int, down: Boolean) {
        if (b !in PcLinkInputProtocol.BUTTON_LEFT..PcLinkInputProtocol.BUTTON_MIDDLE) return
        synchronized(lock) {
            if (down) heldButtons.add(b) else heldButtons.remove(b)
            enqueueLocked(PcInputEvent.Button(b, down))
        }
        wake()
    }

    /** A wheel movement in [PcLinkInputProtocol.WHEEL_NOTCH] units; `dy` positive scrolls up. */
    fun wheel(dx: Int, dy: Int) {
        if (!offer.wheel) return
        if (dx == 0 && dy == 0) return
        synchronized(lock) { enqueueLocked(PcInputEvent.Wheel(dx, dy)) }
        wake()
    }

    /**
     * A physical key by HID usage. A usage outside the injectable set is refused here — the server
     * would drop it (§2.19.6), and refusing locally keeps [heldKeys] honest about what is actually
     * down on the PC, which is what [releaseAll] depends on.
     */
    fun key(usage: Int, down: Boolean) {
        if (!offer.hasHidKeys) return
        if (!PcLinkHidKeys.isInjectable(usage)) return
        synchronized(lock) {
            if (down) heldKeys.add(usage) else heldKeys.remove(usage)
            enqueueLocked(PcInputEvent.Key(usage, down))
        }
        wake()
    }

    /** A literal string to insert — the IME path. Empty strings are not worth an envelope. */
    fun text(s: String) {
        if (!offer.hasText) return
        if (s.isEmpty()) return
        synchronized(lock) { enqueueLocked(PcInputEvent.Text(s)) }
        wake()
    }

    /**
     * Enqueues a release for everything this sender is holding, newest state first: buttons, then
     * keys in ascending usage order.
     *
     * Idempotent — a second call with nothing held enqueues nothing — so it is safe on every exit
     * path, which is how it ends up on all of them.
     */
    fun releaseAll() {
        var any = false
        synchronized(lock) {
            if (heldButtons.isEmpty() && heldKeys.isEmpty()) return
            flushPendingLocked()
            for (b in heldButtons.sorted()) queue.add(PcInputEvent.Button(b, false))
            for (u in heldKeys.sorted()) queue.add(PcInputEvent.Key(u, false))
            heldButtons.clear()
            heldKeys.clear()
            any = true
        }
        if (any) wake()
    }

    /** What is held down right now, for tests and for a status readout. */
    fun heldCount(): Int = synchronized(lock) { heldButtons.size + heldKeys.size }

    /** Whether anything is waiting to go out, so the writer knows whether to tick fast. */
    fun hasPending(): Boolean = synchronized(lock) { queue.isNotEmpty() || pending != null }

    /**
     * Takes the next batch, or null when there is nothing to send.
     *
     * Never longer than [PcLinkInputProtocol.EVENT_CAP]: a batch over the cap is dropped *whole* by
     * the server, so a burst is split across messages — which preserves order, and order is all the
     * batching promised.
     */
    fun drain(): List<PcInputEvent>? {
        synchronized(lock) {
            flushPendingLocked()
            if (queue.isEmpty()) return null
            val n = minOf(queue.size, PcLinkInputProtocol.EVENT_CAP)
            val batch = ArrayList<PcInputEvent>(n)
            for (i in 0 until n) batch.add(queue[i])
            if (n == queue.size) queue.clear() else queue.subList(0, n).clear()
            return batch
        }
    }

    /**
     * Throws away everything queued *without* sending it, and forgets what was held.
     *
     * For a session that has ended: the receiver releases everything it was holding on its own
     * teardown (§2.19.5), so these releases have nowhere to go and no work left to do. Distinct
     * from [releaseAll], which is for a session that is still alive.
     */
    fun discard() {
        synchronized(lock) {
            queue.clear()
            pending = null
            heldButtons.clear()
            heldKeys.clear()
        }
    }

    private fun enqueueLocked(event: PcInputEvent) {
        flushPendingLocked()
        queue.add(event)
    }

    private fun flushPendingLocked() {
        when (val p = pending) {
            is Pending.Rel -> if (p.dx != 0 || p.dy != 0) queue.add(PcInputEvent.Move(p.dx, p.dy))
            is Pending.Abs -> queue.add(PcInputEvent.MoveAbs(p.x, p.y))
            null -> Unit
        }
        pending = null
    }
}

/**
 * Turns motion measured in view pixels into the normalized units §2.19.3 carries, carrying the
 * fractional remainder from one sample to the next.
 *
 * The remainder is the whole point. A high-resolution mouse reports fractional pixels and a slow
 * finger drag covers less than a whole unit per sample; truncating each one independently makes a
 * slow drag go nowhere at all, which reads to a user as a dead touchpad rather than as a rounding
 * bug. Held per-axis and per-source (touch and captured mouse each get their own), and reset
 * whenever a gesture ends so a stale remainder cannot nudge the next one.
 */
class PcPointerScaler {

    private var remX = 0f
    private var remY = 0f

    /**
     * Scales one sample. [spanW]/[spanH] is the surface the motion was measured against — the
     * touchpad view for a finger, the phone's display for a captured mouse — so a gesture that
     * crosses it moves the cursor [gain] times across the desktop.
     */
    fun scale(dxPx: Float, dyPx: Float, spanW: Int, spanH: Int, gain: Float): Pair<Int, Int> {
        if (spanW <= 0 || spanH <= 0) return 0 to 0
        val fx = remX + dxPx * gain * PcLinkInputProtocol.ABS_RANGE / spanW
        val fy = remY + dyPx * gain * PcLinkInputProtocol.ABS_RANGE / spanH
        val ix = fx.toInt()
        val iy = fy.toInt()
        remX = fx - ix
        remY = fy - iy
        return ix to iy
    }

    /** Drops the carried remainder — at the end of a gesture, and whenever the source changes. */
    fun reset() {
        remX = 0f
        remY = 0f
    }
}

/**
 * Accumulates a scroll axis into whole wheel notches (§2.19.2's units of `120`).
 *
 * Android reports scroll as a float in "one step of the source's detent", and a touch drag as
 * pixels; both need converting to notches with the remainder kept, for the same reason the pointer
 * keeps its own — a slow two-finger drag that never reaches a whole notch must still eventually
 * scroll.
 */
class PcWheelAccumulator(private val pixelsPerNotch: Float = 48f) {

    private var rem = 0f

    /** From a pointer's scroll axis, already in detents. */
    fun fromDetents(detents: Float): Int = accumulate(detents)

    /** From a finger drag, in view pixels. */
    fun fromPixels(px: Float): Int = accumulate(px / pixelsPerNotch)

    private fun accumulate(notches: Float): Int {
        val f = rem + notches
        val whole = f.toInt()
        rem = f - whole
        return whole * PcLinkInputProtocol.WHEEL_NOTCH
    }

    fun reset() {
        rem = 0f
    }
}
