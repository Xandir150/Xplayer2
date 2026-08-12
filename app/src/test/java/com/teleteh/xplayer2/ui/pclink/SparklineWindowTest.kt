package com.teleteh.xplayer2.ui.pclink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sparkline's axis rules, which are the whole of what makes it readable — and which have
 * already been got wrong once, on the desktop client (see [SparklineWindow]).
 */
class SparklineWindowTest {

    private val eps = 1e-4f

    // --- the time axis is fixed, not fitted to what is in hand ---------------------------------

    @Test
    fun `the newest sample sits on the right edge`() {
        val w = SparklineWindow(60)
        w.push(1f)
        assertEquals(1f, w.xFraction(0), eps)

        repeat(5) { w.push(it.toFloat()) }
        assertEquals(1f, w.xFraction(w.size - 1), eps)

        repeat(200) { w.push(it.toFloat()) }
        assertEquals(1f, w.xFraction(w.size - 1), eps)
    }

    @Test
    fun `one slot per sample, whatever the window holds`() {
        val step = 1f / 59f

        val young = SparklineWindow(60)
        repeat(3) { young.push(it.toFloat()) }
        assertEquals(step, young.xFraction(2) - young.xFraction(1), eps)

        val full = SparklineWindow(60)
        repeat(60) { full.push(it.toFloat()) }
        assertEquals(step, full.xFraction(59) - full.xFraction(58), eps)
        // The oldest of a full window is the left edge, and only then.
        assertEquals(0f, full.xFraction(0), eps)
    }

    @Test
    fun `a window that is not full draws a stub against the right edge, not a stretched line`() {
        val w = SparklineWindow(60)
        repeat(4) { w.push(60f) }
        // Four seconds of a minute occupy the rightmost ~5% of the width. The bug being pinned
        // here would put this at 0f — the oldest sample nailed to the left edge.
        assertEquals(56f / 59f, w.xFraction(0), eps)
        assertTrue("a 4-sample trace must not span the width", w.xFraction(0) > 0.9f)
    }

    @Test
    fun `a sample that reaches the left edge is dropped and forgotten`() {
        val w = SparklineWindow(60)
        repeat(61) { w.push(it.toFloat()) }
        assertEquals(60, w.size)
        // The first push (0f) has scrolled off; the window starts at the second (1f).
        assertEquals(1f, w.samples().first(), eps)
        assertEquals(60f, w.latest()!!, eps)
    }

    @Test
    fun `an empty window has nothing to place`() {
        val w = SparklineWindow(60)
        assertEquals(0, w.size)
        assertTrue(w.isEmpty)
        assertNull(w.latest())
        assertTrue(w.samples().isEmpty())
    }

    // --- the value axis comes from the visible window only -------------------------------------

    @Test
    fun `an extreme that scrolled off stops setting the scale`() {
        val w = SparklineWindow(60)
        // A spike at the moment of connection — the desktop client's bug left this setting the
        // scale for the whole life of the chart.
        w.push(500f)
        repeat(60) { w.push(60f) }
        val range = w.range()
        assertTrue("the departed spike still sets the top: $range", range.high < 100f)
        // …and what outlived it is drawn at full height again rather than flattened.
        assertEquals(0.5f, w.yFraction(60f, range), eps)
    }

    @Test
    fun `a spike still in the window does set the scale`() {
        val w = SparklineWindow(60)
        repeat(30) { w.push(60f) }
        w.push(500f)
        val range = w.range()
        assertEquals(500f, range.high, eps)
        assertEquals(60f, range.low, eps)
    }

    @Test
    fun `a stream locked at one value is a level line, not a mountain range`() {
        val w = SparklineWindow(60)
        // Sixty seconds of "60 fps" with the hundredths that a real counter produces.
        val jitter = listOf(60f, 60.01f, 59.99f, 60.02f, 59.98f)
        repeat(12) { round -> jitter.forEach { w.push(it) } }
        val range = w.range()
        // The floor is 15% of the value, so the hundredths sit within a thousandth of the height
        // of each other instead of filling it.
        assertEquals(0.15f * 60.02f, range.span, 0.01f)
        val ys = w.samples().map { w.yFraction(it, range) }
        assertTrue("jitter drawn as a mountain range: ${ys.min()}..${ys.max()}", ys.max() - ys.min() < 0.01f)
        assertTrue("the level line should sit around the middle", ys.min() > 0.45f && ys.max() < 0.55f)
    }

    @Test
    fun `a window sitting at zero is a line along the bottom`() {
        val w = SparklineWindow(60)
        repeat(20) { w.push(0f) }
        val range = w.range()
        assertEquals(0f, range.low, eps)
        assertTrue("a zero window still needs a height", range.span > 0f)
        assertEquals(0f, w.yFraction(0f, range), eps)
    }

    @Test
    fun `every visible sample is drawn inside the box`() {
        val w = SparklineWindow(60)
        listOf(3f, 41f, 0f, 12.5f, 7f).forEach { w.push(it) }
        val range = w.range()
        w.samples().forEach {
            val y = w.yFraction(it, range)
            assertTrue("$it fell outside the box at $y", y in 0f..1f)
        }
        assertEquals(1f, w.yFraction(41f, range), eps)
        assertEquals(0f, w.yFraction(0f, range), eps)
    }

    @Test
    fun `clear forgets the window`() {
        val w = SparklineWindow(60)
        repeat(10) { w.push(42f) }
        w.clear()
        assertTrue(w.isEmpty)
        assertEquals(0, w.size)
    }

    @Test
    fun `a shorter axis is still fixed to its own width`() {
        val w = SparklineWindow(10)
        repeat(3) { w.push(1f) }
        assertEquals(7f / 9f, w.xFraction(0), eps)
        assertEquals(1f, w.xFraction(2), eps)
    }
}
