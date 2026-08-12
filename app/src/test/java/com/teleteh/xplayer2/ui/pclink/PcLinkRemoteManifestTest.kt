package com.teleteh.xplayer2.ui.pclink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The manifest facts the PC Link remote's behaviour rests on.
 *
 * `PlayerActivity.showRemoteControlFront()` brings this activity up with
 * `REORDER_TO_FRONT | SINGLE_TOP` every time the player starts — which happens on every return to
 * the foreground, not once per session. Without `singleTop` in the manifest those flags do not
 * collapse onto the running instance: each pass stacks another copy, and Back walks back down the
 * pile one dead remote at a time. That is precisely the "the remote is very buggy" shape, so it is
 * pinned here rather than left to whoever next edits the manifest.
 */
class PcLinkRemoteManifestTest {

    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        )
        val found = candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        found.readText()
    }

    /** The `<activity>` block for the remote, attributes only. */
    private val attributes: String by lazy {
        val start = manifest.indexOf(".ui.pclink.PcLinkRemoteActivity")
        assertTrue("PcLinkRemoteActivity is not in the manifest", start >= 0)
        val open = manifest.lastIndexOf("<activity", start)
        val close = manifest.indexOf('>', start)
        manifest.substring(open, close)
    }

    @Test
    fun `the remote is singleTop, so bringing it to the front never stacks a second one`() {
        assertTrue(
            "PcLinkRemoteActivity must be singleTop — see this class's docs",
            attributes.contains("""android:launchMode="singleTop"""")
        )
    }

    @Test
    fun `the remote is portrait, like the film remote it is a sibling of`() {
        assertTrue(
            attributes.contains("""android:screenOrientation="portrait"""")
        )
    }

    @Test
    fun `nothing outside the app can open a remote for a session it did not start`() {
        assertTrue(attributes.contains("""android:exported="false""""))
    }

    @Test
    fun `the fragment-hosting remote it replaced is gone, not merely unused`() {
        // Two entrances to the same screen is how the tab and the remote drifted apart before.
        assertFalse(
            "PcMirrorRemoteActivity was replaced by PcLinkRemoteActivity",
            manifest.contains("PcMirrorRemoteActivity")
        )
    }
}
