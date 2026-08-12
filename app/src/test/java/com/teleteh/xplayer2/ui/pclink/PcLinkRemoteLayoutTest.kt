package com.teleteh.xplayer2.ui.pclink

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The one layout fact the remote's eyes-free feedback rests on.
 *
 * `tvSurfaceFeedback` is a 40sp bold display slot in the middle of the surface card, and it was
 * declared `wrap_content` with no `maxLines` and no `gravity`. That is fine for what it was built
 * for — "⌖", "🔊 45%" — and wrong the moment anything longer lands in it: at 40sp bold the
 * re-centre *sentence* is wider than the card on every phone in Russian and on the 360dp class in
 * English, so it wrapped into a ragged two-line slab across the card and, on a short phone, over
 * the fps/bitrate sparklines.
 *
 * The caller was fixed (the button now leaves the same mark the long-press does), but the slot is
 * what makes the next caller safe, so the shape is pinned here rather than left as a convention
 * nobody can see. Checked against the file because there is no Robolectric in this module — the
 * same approach `PcLinkRemoteManifestTest` takes for the manifest.
 */
class PcLinkRemoteLayoutTest {

    private val layout: String by lazy {
        val candidates = listOf(
            File("src/main/res/layout/activity_pc_link_remote.xml"),
            File("app/src/main/res/layout/activity_pc_link_remote.xml")
        )
        val found = candidates.firstOrNull { it.isFile }
            ?: error("activity_pc_link_remote.xml not found from ${File(".").absolutePath}")
        found.readText()
    }

    /** The `<TextView>` block declaring the feedback slot, attributes only. */
    private val feedbackAttributes: String by lazy {
        val id = layout.indexOf("@+id/tvSurfaceFeedback")
        assertTrue("tvSurfaceFeedback is not in the layout", id >= 0)
        val open = layout.lastIndexOf("<TextView", id)
        val close = layout.indexOf("/>", id)
        layout.substring(open, close)
    }

    @Test
    fun `the feedback slot is one line, so nothing put in it can wrap across the card`() {
        assertTrue(
            "tvSurfaceFeedback must be single-line — see this class's docs",
            feedbackAttributes.contains("""android:maxLines="1"""")
        )
    }

    @Test
    fun `it is centred across the card rather than sized to its text`() {
        // wrap_content + no gravity is what made the wrapped text land START-aligned in a box of
        // its own width; full width plus centre keeps a mark in the middle and a long token honest.
        assertTrue(feedbackAttributes.contains("""android:layout_width="match_parent""""))
        assertTrue(feedbackAttributes.contains("""android:gravity="center""""))
    }
}
