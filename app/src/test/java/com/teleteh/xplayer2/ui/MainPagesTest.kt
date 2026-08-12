package com.teleteh.xplayer2.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page numbers are the adapter's; anything that points at a tab by number is wrong the moment
 * the adapter is reordered, and nothing else would notice.
 */
class MainPagesTest {

    @Test
    fun `the page numbers are the ones the pager adapter hands out`() {
        val source = listOf(
            File("src/main/java/com/teleteh/xplayer2/ui/MainPagerAdapter.kt"),
            File("app/src/main/java/com/teleteh/xplayer2/ui/MainPagerAdapter.kt")
        ).first { it.isFile }.readText()
        val order = Regex("""(\d+|else) -> (\w+)Fragment\(\)""").findAll(source)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("Recent", order[MainPages.RECENT.toString()])
        assertEquals("Network", order[MainPages.SOURCES.toString()])
        // The last page is the adapter's `else` branch, and there are three of them.
        assertEquals("PcMirror", order["else"])
        assertTrue(source.contains("getItemCount(): Int = ${MainPages.PC_MIRROR + 1}"))
    }
}
