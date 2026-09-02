package com.teleteh.xplayer2.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coalescing of §2.20.3, one rule per test.
 *
 * A finger on a slider produces a value on every pixel it crosses; the wire takes the latest at no
 * more than ten a second, one field when one slider moved, and a reset as a message of its own that
 * makes whatever was waiting moot. The value under the finger at release is the one that matters,
 * and it must never be the one lost.
 */
class PcLinkDepthSenderTest {

    @Test
    fun `one slider moved means one field on the wire`() {
        val s = PcLinkDepthSender()
        s.divergence(30)
        assertEquals(PcDepthRequest(divergence = 30), s.drain(0L))
        // The other slider was never mentioned, so the request does not carry it: a copy of its
        // value sent back would undo a move the window made a moment earlier.
        s.convergence(-100)
        assertEquals(PcDepthRequest(convergence = -100), s.drain(1_000L))
    }

    @Test
    fun `the latest value wins`() {
        val s = PcLinkDepthSender()
        s.divergence(24)
        s.divergence(29)
        s.divergence(33)
        assertEquals(PcDepthRequest(divergence = 33), s.drain(0L))
        assertNull("nothing of the earlier values survives", s.drain(1_000L))
    }

    @Test
    fun `both sliders moved travel together, each at its latest`() {
        val s = PcLinkDepthSender()
        s.divergence(30)
        s.convergence(-50)
        s.convergence(-100)
        assertEquals(PcDepthRequest(divergence = 30, convergence = -100), s.drain(0L))
    }

    @Test
    fun `no more than ten a second`() {
        val s = PcLinkDepthSender()
        s.divergence(1)
        assertNotNull(s.drain(0L))
        s.divergence(2)
        assertNull("fifty milliseconds after a send, nothing goes", s.drain(50L))
        assertEquals("…and the writer is told how long to wait", 50L, s.dueInMs(50L))
        assertEquals(PcDepthRequest(divergence = 2), s.drain(100L))
    }

    @Test
    fun `the first value of a drag goes out at once`() {
        val s = PcLinkDepthSender()
        s.divergence(1)
        assertNotNull(s.drain(0L))
        // The throttle is measured from the previous send, not from the start of a gesture: a
        // drag that begins five seconds later must not wait a tenth of a second for its first move.
        s.divergence(2)
        assertEquals(0L, s.dueInMs(5_000L))
        assertNotNull(s.drain(5_000L))
    }

    @Test
    fun `the value under the finger at release is never lost`() {
        val s = PcLinkDepthSender()
        s.divergence(20)
        assertEquals(PcDepthRequest(divergence = 20), s.drain(0L))
        s.divergence(24)
        assertNull(s.drain(30L))
        s.divergence(29)
        assertNull(s.drain(60L))
        s.divergence(35) // the finger lifts here
        assertNull(s.drain(90L))
        assertTrue("something is waiting", s.hasPending())
        assertEquals("the release value goes out at the next interval", PcDepthRequest(divergence = 35), s.drain(100L))
        assertFalse(s.hasPending())
    }

    @Test
    fun `a reset drops waiting values and goes alone`() {
        val s = PcLinkDepthSender()
        s.divergence(40)
        s.convergence(200)
        s.reset()
        assertEquals(PcDepthRequest(reset = true), s.drain(0L))
        assertNull("the values the reset made moot are gone", s.drain(1_000L))
    }

    @Test
    fun `a nudge after a reset queues behind it, in order`() {
        val s = PcLinkDepthSender()
        s.reset()
        s.divergence(25)
        assertEquals("reset first", PcDepthRequest(reset = true), s.drain(0L))
        assertNull("the nudge waits out the interval", s.drain(50L))
        assertEquals("then the nudge, so the PC ends where the finger did", PcDepthRequest(divergence = 25), s.drain(100L))
    }

    @Test
    fun `a second reset is one reset`() {
        val s = PcLinkDepthSender()
        s.reset()
        s.reset()
        assertEquals(PcDepthRequest(reset = true), s.drain(0L))
        assertNull(s.drain(1_000L))
    }

    @Test
    fun `nothing waiting lets the writer sleep its full tick`() {
        val s = PcLinkDepthSender()
        assertNull(s.dueInMs(0L))
        assertFalse(s.hasPending())
        assertNull(s.drain(0L))
    }

    @Test
    fun `every verb nudges the writer awake`() {
        var wakes = 0
        val s = PcLinkDepthSender(wake = { wakes++ })
        s.divergence(1)
        s.convergence(2)
        s.reset()
        assertEquals(3, wakes)
    }

    @Test
    fun `discard forgets the values and the throttle`() {
        val s = PcLinkDepthSender()
        s.divergence(1)
        assertNotNull(s.drain(0L))
        s.divergence(2)
        s.discard()
        assertNull("the waiting value is gone", s.drain(0L))
        assertFalse(s.hasPending())
        // A new session's first send must not wait on the old session's clock.
        s.divergence(3)
        assertEquals(0L, s.dueInMs(1L))
        assertEquals(PcDepthRequest(divergence = 3), s.drain(1L))
    }

    @Test
    fun `the interval is the spec's ten a second`() {
        assertEquals(100L, PcLinkDepthSender.MIN_INTERVAL_MS)
    }
}
