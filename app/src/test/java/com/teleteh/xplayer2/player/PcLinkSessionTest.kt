package com.teleteh.xplayer2.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registry the PC-Mirror tab reads the running session through. */
class PcLinkSessionTest {

    private val hosts = mutableListOf<FakeHost>()

    @After
    fun tearDown() {
        hosts.forEach { PcLinkSession.unregister(it) }
        hosts.clear()
    }

    private fun host(stats: PcLinkSession.Stats? = null): FakeHost =
        FakeHost(stats).also { hosts.add(it); PcLinkSession.register(it) }

    private fun stats(sessionId: Long = 1L, name: String = "PC") = PcLinkSession.Stats(
        sessionId = sessionId,
        serverName = name,
        link = PcLinkSession.Link.STREAMING,
        framesRendered = 0L,
        videoBytes = 0L,
        droppedFrames = 0L,
        rttMs = 0f,
        codec = "avc",
        width = 1920,
        height = 1080,
        stereo = "mono",
        audioRateHz = 0,
        audioChannels = 0,
        audioBufferedMs = 0,
        audioDropouts = 0L,
        audioSkewMs = null,
        audioToGlasses = true,
        audioAvailable = false
    )

    @Test
    fun `no session, no numbers`() {
        assertNull(PcLinkSession.stats())
        // And the controls are no-ops rather than a crash: the tab draws its connect card and the
        // user can still tap things.
        PcLinkSession.setAudioToGlasses(false)
        PcLinkSession.end()
    }

    @Test
    fun `a player that is not streaming a desktop is not the session`() {
        host(stats = null)
        assertNull(PcLinkSession.stats())
    }

    @Test
    fun `the streaming player is the session, among several registered`() {
        host(stats = null)
        val streaming = host(stats = stats(name = "Desk"))
        host(stats = null)
        assertEquals("Desk", PcLinkSession.stats()?.serverName)

        PcLinkSession.setAudioToGlasses(false)
        assertEquals(false, streaming.audioToGlasses)
        PcLinkSession.end()
        assertTrue(streaming.ended)
    }

    @Test
    fun `commands never reach a player that is not streaming`() {
        val idle = host(stats = null)
        host(stats = stats())
        PcLinkSession.setAudioToGlasses(false)
        PcLinkSession.end()
        assertNull(idle.audioToGlasses)
        assertTrue(!idle.ended)
    }

    @Test
    fun `a host that has gone is not consulted`() {
        val gone = host(stats = stats())
        PcLinkSession.unregister(gone)
        assertNull(PcLinkSession.stats())
    }

    @Test
    fun `registering twice registers once`() {
        val one = host(stats = stats())
        PcLinkSession.register(one)
        PcLinkSession.unregister(one)
        assertNull(PcLinkSession.stats())
    }

    @Test
    fun `session ids never repeat`() {
        val first = PcLinkSession.newSessionId()
        val second = PcLinkSession.newSessionId()
        assertNotEquals(first, second)
        assertTrue(second > first)
    }

    @Test
    fun `a re-centre reaches the live host, and nothing at all when none is streaming`() {
        val parked = host()
        val live = host(stats(sessionId = 1))

        PcLinkSession.recenter()

        assertTrue(live.recentered)
        assertFalse(parked.recentered)

        // With the streaming host gone there is nothing to re-centre, and the parked one must not
        // be handed the gesture as a consolation prize.
        PcLinkSession.unregister(live)
        PcLinkSession.recenter()
        assertFalse(parked.recentered)
    }

    private class FakeHost(private val stats: PcLinkSession.Stats?) : PcLinkSession.Host {
        var audioToGlasses: Boolean? = null
        var ended = false
        var recentered = false
        override fun pcLinkStats(): PcLinkSession.Stats? = stats
        override fun setPcLinkAudioToGlasses(enabled: Boolean) { audioToGlasses = enabled }
        override fun endPcLink() { ended = true }
        override fun recenterPcLink() { recentered = true }
    }
}
