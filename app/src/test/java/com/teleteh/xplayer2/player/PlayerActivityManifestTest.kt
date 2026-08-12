package com.teleteh.xplayer2.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest facts the glasses' exclusivity rests on.
 *
 * Both directions of the handover live in one `PlayerActivity` instance: the PC Link session, the
 * ExoPlayer, and the [GlassesStage] registration that lets a later claim find them. What makes
 * them survive a rotation is not any code — it is that the activity declares the configuration
 * changes it handles itself, so the system never recreates it. Drop `orientation` or `screenSize`
 * from that list and a rotation tears the session down mid-stream; the registry would recover (it
 * is scoped to onCreate/onDestroy, which a recreation drives in order) but the desktop stream and
 * the playback position would not.
 *
 * `singleTop` is the other half, and the reason the stage exists at all: it routes an intent into
 * the running instance only when that instance is the top of the task, which with the glasses
 * attached it usually is not. Pinned here so nobody "fixes" the duplicate-instance problem by
 * changing the launch mode without noticing what else that moves.
 */
class PlayerActivityManifestTest {

    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val found = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        found.readText()
    }

    /** The `<activity>` block for PlayerActivity, attributes only. */
    private val playerActivityAttributes: String by lazy {
        val start = manifest.indexOf(".player.PlayerActivity")
        assertTrue("PlayerActivity is not in the manifest", start >= 0)
        val open = manifest.lastIndexOf("<activity", start)
        val close = manifest.indexOf('>', start)
        manifest.substring(open, close)
    }

    @Test
    fun `a rotation does not recreate the player`() {
        val configChanges = Regex("""android:configChanges="([^"]*)"""")
            .find(playerActivityAttributes)?.groupValues?.get(1)
            ?: error("PlayerActivity declares no configChanges")
        val handled = configChanges.split('|').map { it.trim() }.toSet()
        for (change in listOf("orientation", "screenSize", "screenLayout", "smallestScreenSize")) {
            assertTrue(
                "PlayerActivity must handle '$change' itself, or a rotation kills the session",
                change in handled
            )
        }
    }

    @Test
    fun `the player is singleTop, which is why GlassesStage exists`() {
        assertTrue(
            "PlayerActivity's launch mode is what lets a second instance exist — see GlassesStage",
            playerActivityAttributes.contains("""android:launchMode="singleTop"""")
        )
    }
}
