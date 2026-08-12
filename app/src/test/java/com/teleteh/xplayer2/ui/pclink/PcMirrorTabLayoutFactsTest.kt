package com.teleteh.xplayer2.ui.pclink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The PC-Mirror tab's layout facts, read straight out of the XML.
 *
 * These are the things about the tab that are only wrong by eye — a card with an invisible edge, a
 * header 8dp out of line with the rows under it, an empty state promising something this screen
 * cannot do. Nothing here can be caught by the compiler and the local unit tests have no inflater
 * (no Robolectric), so they are pinned the way `PcLinkRemoteManifestTest` pins the manifest: by
 * parsing the resource file itself. The instrumented [PcMirrorTabLayoutTest] covers inflation.
 */
class PcMirrorTabLayoutFactsTest {

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
        const val APP = "http://schemas.android.com/apk/res-auto"
    }

    private fun resource(path: String): File =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
            ?: error("$path not found from ${File(".").absolutePath}")

    private fun layout(name: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resource("src/main/res/layout/$name"))

    private fun Document.elements(): List<Element> {
        val nodes = getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Document.byId(id: String): Element = elements().firstOrNull {
        it.getAttributeNS(ANDROID, "id") in setOf("@+id/$id", "@id/$id")
    } ?: error("no view with id $id")

    private val tab: Document by lazy { layout("fragment_pc_mirror.xml") }
    private val row: Document by lazy { layout("item_pc_server.xml") }

    /**
     * `strokeWidth` alone buys nothing: MaterialCardView defaults the colour when the attribute is
     * absent, so the 1dp the layout asks for is drawn in a colour nobody chose — and the "a session
     * is running" card is the one place on this tab where a card has to read as a card.
     */
    @Test
    fun `a card that asks for an outline says what colour it is`() {
        val cards = tab.elements().filter { it.tagName.endsWith("MaterialCardView") }
        assertTrue("fragment_pc_mirror has no MaterialCardView", cards.isNotEmpty())
        for (card in cards) {
            if (card.getAttributeNS(APP, "strokeWidth").isEmpty()) continue
            assertTrue(
                "strokeWidth without strokeColor leaves the card's edge to a Material default",
                card.getAttributeNS(APP, "strokeColor").isNotEmpty()
            )
        }
    }

    /**
     * The tab's list is the pairing store. "Start XPlayer Link on your computer to see it here" is
     * true on the connect screen, which really is watching the network, and can never come true
     * here — the only way a row appears is the six-digit ceremony behind "Find your PC".
     */
    @Test
    fun `the empty state does not promise a discovery this list never runs`() {
        val empty = tab.byId("tvEmpty")
        assertEquals("@string/pclink_paired_empty", empty.getAttributeNS(ANDROID, "text"))

        val connect = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resource("src/main/res/layout/activity_pc_connect.xml"))
        val connectEmpty = connect.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.first { it.getAttributeNS(ANDROID, "id") == "@+id/tvEmpty" }
        assertEquals(
            "pclink_empty must stay on the screen where waiting for the PC actually works",
            "@string/pclink_empty",
            connectEmpty.getAttributeNS(ANDROID, "text")
        )

        for (values in listOf("values", "values-ru")) {
            assertTrue(
                "pclink_paired_empty is missing from $values/strings.xml",
                resource("src/main/res/$values/strings.xml").readText()
                    .contains("name=\"pclink_paired_empty\"")
            )
        }
    }

    /** …and it is not drawn at all until the store has been read, or everyone sees it flash. */
    @Test
    fun `the empty line starts hidden, like its twin on the connect screen`() {
        assertEquals("gone", tab.byId("tvEmpty").getAttributeNS(ANDROID, "visibility"))
    }

    /**
     * The idle header is the row's own construction — a 40dp circle and two lines — so any
     * difference in its start padding shows up as one circle out of line above a column of them.
     * The rows sit inside an unpadded RecyclerView, so their own padding is the whole offset.
     */
    @Test
    fun `the idle header and the paired label sit on the same column as the rows`() {
        val rowPadding = row.documentElement.getAttributeNS(ANDROID, "paddingStart")
        assertEquals("12dp", rowPadding)
        assertEquals("", tab.byId("rvPairedPcs").getAttributeNS(ANDROID, "paddingStart"))
        assertEquals(rowPadding, tab.byId("boxIdleHeader").getAttributeNS(ANDROID, "paddingStart"))
        assertEquals(rowPadding, tab.byId("tvPairedLabel").getAttributeNS(ANDROID, "paddingStart"))
        assertFalse(
            "a padding on the list would move the rows out from under the header again",
            tab.byId("rvPairedPcs").getAttributeNS(ANDROID, "padding").isNotEmpty()
        )
    }
}
