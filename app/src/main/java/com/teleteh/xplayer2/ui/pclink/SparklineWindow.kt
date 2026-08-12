package com.teleteh.xplayer2.ui.pclink

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The last minute of a number, and where each second of it goes on a fixed strip.
 *
 * Pure maths, no `View` — the geometry below is the whole of what makes a sparkline readable, and
 * it is worth being able to state it as tests rather than infer it from pixels.
 *
 * **The time axis is fixed at [capacity] slots, whatever the window currently holds.** The newest
 * sample is pinned to the right edge, each older one sits exactly one slot to its left, and a
 * sample that reaches the left edge is dropped and forgotten. A window that is not full yet
 * therefore draws a short stub against the right edge and grows leftward as the seconds accumulate.
 *
 * The alternative — scaling the trace to the number of samples in hand — is the bug this class
 * exists to make impossible, and it was a real one on the desktop client. It pins the *oldest*
 * sample to the left edge forever, so the first minute never scrolls: the shape merely squeezes a
 * little more with every update, and the spike from the moment of connection sits there setting the
 * scale for as long as the window is open.
 *
 * **The vertical scale comes from the visible window only**, for the second half of the same
 * reason: an extreme that has scrolled off the left edge must stop flattening everything that
 * outlived it. And a floor under the range ([RANGE_FLOOR_FRACTION] of the value) keeps a stream
 * locked at 60 fps a level line instead of a mountain range built out of its own hundredths.
 */
class SparklineWindow(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= 2) { "a sparkline needs at least two slots, got $capacity" }
    }

    private val samples = ArrayDeque<Float>()

    /** How many samples are held; never more than [capacity]. */
    val size: Int get() = samples.size

    val isEmpty: Boolean get() = samples.isEmpty()

    /** Oldest first, newest last. */
    fun samples(): List<Float> = samples.toList()

    /** The newest sample, or null when nothing has been pushed yet. */
    fun latest(): Float? = samples.lastOrNull()

    /** Adds a sample at the right edge, dropping whatever fell off the left. */
    fun push(value: Float) {
        samples.addLast(value)
        while (samples.size > capacity) samples.removeFirst()
    }

    /** Forgets everything — a session ended, or a different one began. */
    fun clear() = samples.clear()

    /**
     * Where sample [index] (0 = the oldest held) sits horizontally, as a 0..1 fraction of the full
     * width. The newest sample is always exactly 1f; the step between neighbours is always
     * `1 / (capacity - 1)`, whether the window holds three samples or sixty.
     */
    fun xFraction(index: Int): Float {
        require(index in 0 until size) { "no sample at $index (size $size)" }
        val slot = capacity - size + index
        return slot.toFloat() / (capacity - 1).toFloat()
    }

    /** The value range the trace is drawn against. Never empty — see [RANGE_FLOOR_FRACTION]. */
    fun range(): Range {
        if (samples.isEmpty()) return Range(0f, ABSOLUTE_FLOOR)
        var low = samples.first()
        var high = low
        for (v in samples) {
            low = min(low, v)
            high = max(high, v)
        }
        // Enough of a range that a flat line stays flat. Proportional to the values themselves —
        // 60 fps jittering by a hundredth is steady, 0.6 Mbps jittering by a hundredth is not —
        // with an absolute floor underneath so an all-zero window still has a height.
        val floor = max(RANGE_FLOOR_FRACTION * max(abs(high), abs(low)), ABSOLUTE_FLOOR)
        if (high - low >= floor) return Range(low, high)
        val mid = (high + low) / 2f
        var lo = mid - floor / 2f
        var hi = mid + floor / 2f
        // These are rates: they are never negative, and a window sitting at zero reads as a line
        // along the bottom rather than one floating up the middle of a range half of which is
        // impossible.
        if (lo < 0f && low >= 0f) {
            lo = 0f
            hi = floor
        }
        return Range(lo, hi)
    }

    /**
     * Where [value] sits vertically, as a 0..1 fraction of the height measured from the bottom of
     * the current [range]. Clamped, so a caller that mixes a stale range with a fresh value still
     * draws inside its box.
     */
    fun yFraction(value: Float, range: Range = range()): Float {
        val span = range.span
        if (span <= 0f) return 0f
        return ((value - range.low) / span).coerceIn(0f, 1f)
    }

    /** The bottom and top of the drawn strip. */
    data class Range(val low: Float, val high: Float) {
        val span: Float get() = high - low
    }

    companion object {
        /** Sixty seconds at one sample a second: a minute, and still legible at chip size. */
        const val DEFAULT_CAPACITY = 60

        /** The smallest range a window is drawn against, as a fraction of its largest value. */
        const val RANGE_FLOOR_FRACTION = 0.15f

        /** …and the smallest range in absolute terms, for a window that is all zeros. */
        const val ABSOLUTE_FLOOR = 0.001f
    }
}
