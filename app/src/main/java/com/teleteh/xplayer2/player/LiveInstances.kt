package com.teleteh.xplayer2.player

/**
 * The live instances of an activity there can be more than one of, newest last.
 *
 * `PlayerActivity` publishes itself in a static so the phone-side remote and the playback
 * notification can find it, and there is routinely more than one of it: it is `singleTop`, which
 * routes an intent into the running instance only while that instance is the top of the task — and
 * with the glasses attached it never is, because the remote sits in front of it (see [GlassesStage],
 * which exists for the same reason). The notification's own body intent is the everyday example: it
 * lands in a *second* activity, which finds no URI on it and finishes inside `onCreate`.
 *
 * Written as "last one to be created wins, and clears the static on the way out", that throwaway
 * took the static with it while the first instance was still streaming — leaving Stop stopping
 * nothing, and the film remote closing itself half a second later because the player it drives had
 * apparently gone. A list gives the title back instead: whoever is newest *and still alive* holds
 * it, which for the throwaway's whole brief life is the throwaway, and the moment it is destroyed
 * is the instance that was there before.
 *
 * Main thread only, like the lifecycle callbacks that drive it. Identity, not equality: two
 * activities are never "the same one" because they compare equal.
 */
class LiveInstances<T : Any> {

    private val instances = ArrayList<T>()

    /** The one a caller means by "the player": newest, and still alive. */
    val newest: T? get() = instances.lastOrNull()

    /** Live instances, oldest first. Test seam and diagnostics — never mutate through it. */
    val all: List<T> get() = instances.toList()

    /** `onCreate`. Idempotent, so a re-registration cannot make one instance count twice. */
    fun add(instance: T) {
        if (instances.none { it === instance }) instances.add(instance)
    }

    /** `onDestroy`. Removes exactly this instance, whatever position it holds. */
    fun remove(instance: T) {
        instances.removeAll { it === instance }
    }
}
