package com.teleteh.xplayer2.player

import com.teleteh.xplayer2.player.GlassesStage.Use
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glasses are one screen, and the arbitration that keeps exactly one thing on it.
 *
 * The bug this guards: `PlayerActivity` is `singleTop`, which only routes an intent into the
 * running instance when it is the top of the task — and with the glasses attached it usually is
 * not. So a film started while a desktop streams lands in a *second* activity, and `onStop` keeps
 * the first alive on purpose because its picture is on the external panel. Both then run. Every
 * test here is about the registry finding that older instance, not about a boolean.
 *
 * The stand-in occupant is deliberately awkward in the one way the real one is: releasing it
 * unregisters it, because `releaseGlasses()` finishes an activity and `onDestroy` unregisters.
 * A sweep that iterates the live list instead of a snapshot skips occupants or throws, and both
 * failures leave something streaming.
 */
class GlassesStageTest {

    @After
    fun tearDown() {
        GlassesStage.current.forEach { GlassesStage.unregister(it) }
    }

    /** A `PlayerActivity` stripped to what the stage can see, self-unregistering like the real one. */
    private class FakeOccupant(
        private var use: Use,
        private val selfUnregisters: Boolean = true
    ) : GlassesStage.Occupant {
        var releases = 0
            private set

        override val glassesUse: Use get() = use

        override fun releaseGlasses() {
            releases++
            use = Use.NOTHING
            if (selfUnregisters) GlassesStage.unregister(this)
        }
    }

    private fun register(vararg occupants: FakeOccupant) = occupants.onEach { GlassesStage.register(it) }

    // --- the defect ---------------------------------------------------------------------------

    @Test
    fun `a film claims the glasses from a desktop in another activity`() {
        val streamingDesktop = FakeOccupant(Use.PC_LINK)
        val newFilm = FakeOccupant(Use.LOCAL_VIDEO)
        register(streamingDesktop, newFilm)

        val handover = GlassesStage.claim(newFilm)

        assertEquals(1, streamingDesktop.releases)
        assertEquals(0, newFilm.releases)
        assertTrue(handover.endedPcLink)
        assertFalse(handover.endedLocalVideo)
    }

    @Test
    fun `a desktop claims the glasses from a film in another activity`() {
        val playingFilm = FakeOccupant(Use.LOCAL_VIDEO)
        val newDesktop = FakeOccupant(Use.PC_LINK)
        register(playingFilm, newDesktop)

        val handover = GlassesStage.claim(newDesktop)

        assertEquals(1, playingFilm.releases)
        assertEquals(0, newDesktop.releases)
        assertTrue(handover.endedLocalVideo)
        assertFalse(handover.endedPcLink)
    }

    @Test
    fun `two films are as wrong as a film and a desktop`() {
        val old = FakeOccupant(Use.LOCAL_VIDEO)
        val new = FakeOccupant(Use.LOCAL_VIDEO)
        register(old, new)

        assertTrue(GlassesStage.claim(new).endedLocalVideo)
        assertEquals(1, old.releases)
    }

    @Test
    fun `an activity showing nothing is left where it is`() {
        val idle = FakeOccupant(Use.NOTHING)
        val film = FakeOccupant(Use.LOCAL_VIDEO)
        register(idle, film)

        val handover = GlassesStage.claim(film)

        assertEquals(0, idle.releases)
        assertFalse(handover.endedAnything)
    }

    @Test
    fun `the notification's Stop takes the desktop off even after a throwaway player has gone`() {
        // Stop has no claimant of its own — it is "take whatever is on the glasses off them" — and
        // it must not be routed through the last-created-wins static: the notification's own body
        // intent creates a second PlayerActivity every time it is tapped while the remote is in
        // front, and that one reports NOTHING and finishes immediately.
        val streamingDesktop = FakeOccupant(Use.PC_LINK)
        val throwaway = FakeOccupant(Use.NOTHING)
        register(streamingDesktop, throwaway)
        GlassesStage.unregister(throwaway) // it finished inside onCreate

        val handover = GlassesStage.claim(null)

        assertEquals(1, streamingDesktop.releases)
        assertEquals(0, throwaway.releases)
        assertTrue(handover.endedPcLink)
        assertEquals(emptyList<GlassesStage.Occupant>(), GlassesStage.current)
    }

    @Test
    fun `Stop with nothing on the glasses is a no-op`() {
        val idle = FakeOccupant(Use.NOTHING)
        register(idle)

        assertFalse(GlassesStage.claim(null).endedAnything)
        assertEquals(0, idle.releases)
    }

    // --- the awkward parts --------------------------------------------------------------------

    @Test
    fun `every other occupant is evicted even though releasing one unregisters it`() {
        // Three at once is not hypothetical: a film, a desktop and a stale instance can all be in
        // the task. Iterating the live list would visit the first, watch it remove itself, and
        // skip straight past the second.
        val first = FakeOccupant(Use.PC_LINK)
        val second = FakeOccupant(Use.LOCAL_VIDEO)
        val third = FakeOccupant(Use.PC_LINK)
        val claimant = FakeOccupant(Use.LOCAL_VIDEO)
        register(first, second, third, claimant)

        val handover = GlassesStage.claim(claimant)

        assertEquals(1, first.releases)
        assertEquals(1, second.releases)
        assertEquals(1, third.releases)
        assertTrue(handover.endedPcLink)
        assertTrue(handover.endedLocalVideo)
        assertEquals(listOf<GlassesStage.Occupant>(claimant), GlassesStage.current)
    }

    @Test
    fun `an occupant that does not unregister itself is still only evicted once`() {
        // The real one finishes asynchronously, so the unregister may not have landed by the time
        // the next claim runs. Its `glassesUse` has already gone to NOTHING, which is what stops
        // the second sweep — not the list membership.
        val stubborn = FakeOccupant(Use.PC_LINK, selfUnregisters = false)
        val claimant = FakeOccupant(Use.LOCAL_VIDEO)
        register(stubborn, claimant)

        assertTrue(GlassesStage.claim(claimant).endedPcLink)
        assertFalse(GlassesStage.claim(claimant).endedAnything)
        assertEquals(1, stubborn.releases)
    }

    @Test
    fun `claiming with nothing else registered is a no-op`() {
        val only = FakeOccupant(Use.LOCAL_VIDEO)
        register(only)

        assertFalse(GlassesStage.claim(only).endedAnything)
        assertEquals(0, only.releases)
    }

    @Test
    fun `registering twice does not evict twice`() {
        val desktop = FakeOccupant(Use.PC_LINK)
        GlassesStage.register(desktop)
        GlassesStage.register(desktop)
        val claimant = FakeOccupant(Use.LOCAL_VIDEO)
        register(claimant)

        GlassesStage.claim(claimant)

        assertEquals(1, desktop.releases)
    }

    // --- lifecycle ----------------------------------------------------------------------------

    @Test
    fun `a backgrounded occupant is still evictable`() {
        // Registration is scoped to onCreate/onDestroy on purpose. A player whose picture is on the
        // glasses keeps its session through onStop — that is exactly the instance a later claim has
        // to find, so nothing about being stopped may remove it from the stage.
        val backgroundedDesktop = FakeOccupant(Use.PC_LINK)
        register(backgroundedDesktop)
        // Whatever onStop/onStart do, they do not touch the registry.
        assertEquals(listOf<GlassesStage.Occupant>(backgroundedDesktop), GlassesStage.current)

        val film = FakeOccupant(Use.LOCAL_VIDEO)
        register(film)
        assertTrue(GlassesStage.claim(film).endedPcLink)
        assertEquals(1, backgroundedDesktop.releases)
    }

    @Test
    fun `a recreated activity leaves exactly one occupant behind`() {
        // A configuration change the activity does not handle itself destroys the old instance
        // before creating the new one, so the two never overlap on the stage — and the survivor is
        // the live one, which must not then evict itself.
        val beforeRotation = FakeOccupant(Use.PC_LINK)
        register(beforeRotation)
        GlassesStage.unregister(beforeRotation)
        val afterRotation = FakeOccupant(Use.PC_LINK)
        register(afterRotation)

        assertEquals(listOf<GlassesStage.Occupant>(afterRotation), GlassesStage.current)
        assertFalse(GlassesStage.claim(afterRotation).endedAnything)
        assertEquals(0, afterRotation.releases)
    }

    @Test
    fun `unregistering removes only the occupant asked for`() {
        val a = FakeOccupant(Use.PC_LINK)
        val b = FakeOccupant(Use.LOCAL_VIDEO)
        register(a, b)

        GlassesStage.unregister(a)

        assertEquals(listOf<GlassesStage.Occupant>(b), GlassesStage.current)
    }
}
