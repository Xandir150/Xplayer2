package com.teleteh.xplayer2.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which `PlayerActivity` the statics point at, and the throwaway that used to take them away.
 *
 * The defect this guards: the playback notification's body intent is a bare launch intent aimed at
 * `PlayerActivity`, and with the glasses attached the remote is always in front — so `singleTop`
 * cannot route it into the running instance and it creates a second one, which finds no URI and
 * finishes inside `onCreate`. Under "last created wins, and clears the static on the way out" that
 * throwaway nulled the handle while the first instance was still streaming: the notification's own
 * Stop button then stopped nothing, and the film remote, which polls the same handle twice a
 * second, closed itself over a player that was still playing.
 */
class LiveInstancesTest {

    /** Stands in for an activity: identity is all this holds, which is all it is asked for. */
    private class Instance(private val name: String) {
        override fun toString() = name
    }

    // --- the defect ----------------------------------------------------------------------------

    @Test
    fun `a throwaway second player hands the title back when it goes`() {
        val instances = LiveInstances<Instance>()
        val streaming = Instance("streaming")
        instances.add(streaming)

        // The notification's own body intent: created, finds nothing to play, finishes.
        val throwaway = Instance("throwaway")
        instances.add(throwaway)
        instances.remove(throwaway)

        assertSame(
            "the live session must still be findable after a throwaway has come and gone",
            streaming, instances.newest
        )
    }

    @Test
    fun `several throwaways in a row change nothing`() {
        val instances = LiveInstances<Instance>()
        val streaming = Instance("streaming")
        instances.add(streaming)
        repeat(3) {
            val throwaway = Instance("throwaway $it")
            instances.add(throwaway)
            instances.remove(throwaway)
        }
        assertSame(streaming, instances.newest)
        assertEquals(1, instances.all.size)
    }

    // --- the ordinary cases --------------------------------------------------------------------

    @Test
    fun `nothing alive, nothing to point at`() {
        val instances = LiveInstances<Instance>()
        assertNull(instances.newest)
        // Removing something that was never added is a no-op, not a crash: onDestroy runs for
        // instances that never finished being set up.
        instances.remove(Instance("never added"))
        assertNull(instances.newest)
    }

    @Test
    fun `the newest live instance is the one meant`() {
        val instances = LiveInstances<Instance>()
        val first = Instance("first")
        val second = Instance("second")
        instances.add(first)
        instances.add(second)
        assertSame(second, instances.newest)
    }

    @Test
    fun `a recreation leaves exactly one behind`() {
        // A configuration change the activity does not handle destroys the old instance before
        // creating the new one, so the two never overlap.
        val instances = LiveInstances<Instance>()
        val before = Instance("before")
        instances.add(before)
        instances.remove(before)
        val after = Instance("after")
        instances.add(after)

        assertSame(after, instances.newest)
        assertEquals(1, instances.all.size)
    }

    @Test
    fun `adding twice adds once, so one destroy really removes it`() {
        val instances = LiveInstances<Instance>()
        val only = Instance("only")
        instances.add(only)
        instances.add(only)
        instances.remove(only)
        assertNull(instances.newest)
    }

    @Test
    fun `an older instance destroyed out of order does not disturb the newest`() {
        val instances = LiveInstances<Instance>()
        val old = Instance("old")
        val new = Instance("new")
        instances.add(old)
        instances.add(new)

        instances.remove(old)

        assertSame(new, instances.newest)
        assertEquals(listOf(new), instances.all)
    }
}
