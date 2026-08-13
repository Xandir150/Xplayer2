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

    /** The attributes of one `<LinearLayout>`/`<...Button>` block, by its id. */
    private fun attributesOf(id: String): String {
        val at = layout.indexOf("@+id/$id")
        assertTrue("$id is not in the layout", at >= 0)
        val open = layout.lastIndexOf('<', at)
        val close = layout.indexOf('>', at)
        return layout.substring(open, close)
    }

    /**
     * Nothing about driving the PC is on screen until the PC has said it allows it.
     *
     * §2.19 makes input the exception among this app's features: it is off by default on the PC, it
     * exists only inside an encrypted session, and the phone finds out about it in a `config` that
     * arrives after the picture. A block that started visible would spend the first second of every
     * cast offering a switch that does nothing — and, for the majority of sessions where the
     * operator never turned input on, would offer it for the whole cast.
     */
    @Test
    fun `the control-the-PC block starts hidden`() {
        assertTrue(
            "boxInput must start GONE — the PC has not answered yet",
            attributesOf("boxInput").contains("""android:visibility="gone"""")
        )
    }

    /**
     * The two controls that only make sense while control is on start hidden too.
     *
     * A "pad is the screen" switch with the pad still being a volume slider, or a keyboard button
     * that types into nothing, are both worse than absent: they are affordances that lie.
     */
    @Test
    fun `the absolute switch and the keyboard button start hidden`() {
        assertTrue(attributesOf("btnInputAbsolute").contains("""android:visibility="gone""""))
        assertTrue(attributesOf("btnInputKeyboard").contains("""android:visibility="gone""""))
    }

    /**
     * The switch inside the row is not clickable, the same rule the audio row above it follows.
     *
     * The row is the tap target; a switch that could also be tapped would toggle itself and get
     * ahead of the session's own state, which is the defect `PcLinkRemotePolicy.audioTapCommand`
     * exists to document next door.
     */
    @Test
    fun `the input switch is an affordance, not a second tap target`() {
        assertTrue(attributesOf("swInputControl").contains("""android:clickable="false""""))
        assertTrue(attributesOf("swInputAbsolute").contains("""android:clickable="false""""))
    }
}
