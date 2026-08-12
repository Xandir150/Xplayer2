package com.teleteh.xplayer2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * That the two tabs which delete a row by swiping it keep the finger to themselves.
 *
 * Recent has had this since it grew swipe-to-delete; PC-Mirror grew a list of paired PCs with the
 * same gesture afterwards and kept paging on, which made forgetting a PC a coin toss against
 * changing tab. Pinned as a policy rather than as a comparison against page 0, because the next
 * list on the next tab will pose the same question.
 */
class TabSwipePolicyTest {

    @Test
    fun `only sources pages by swipe`() {
        assertFalse(
            "Recent forgets a row by swiping it",
            TabSwipePolicy.pagesBySwipe(TabSwipePolicy.PAGE_RECENT)
        )
        assertTrue(TabSwipePolicy.pagesBySwipe(TabSwipePolicy.PAGE_SOURCES))
        assertFalse(
            "PC-Mirror forgets a PC by swiping its row",
            TabSwipePolicy.pagesBySwipe(TabSwipePolicy.PAGE_PC_MIRROR)
        )
    }

    /** The page numbers are the adapter's; if it is reordered this policy points at the wrong tab. */
    @Test
    fun `the page numbers are the ones the pager adapter hands out`() {
        val source = listOf(
            File("src/main/java/com/teleteh/xplayer2/ui/MainPagerAdapter.kt"),
            File("app/src/main/java/com/teleteh/xplayer2/ui/MainPagerAdapter.kt")
        ).first { it.isFile }.readText()
        val order = Regex("""(\d+|else) -> (\w+)Fragment\(\)""").findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("Recent", order[TabSwipePolicy.PAGE_RECENT.toString()])
        assertEquals("Network", order[TabSwipePolicy.PAGE_SOURCES.toString()])
        // The last page is the adapter's `else` branch, and there are three of them.
        assertEquals("PcMirror", order["else"])
        assertTrue(source.contains("getItemCount(): Int = ${TabSwipePolicy.PAGE_PC_MIRROR + 1}"))
    }
}
