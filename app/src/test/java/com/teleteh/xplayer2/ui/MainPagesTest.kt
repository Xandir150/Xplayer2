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
        assertEquals("GlassesControl", order[MainPages.GLASSES.toString()])
        // PC-Mirror is the adapter's `else` branch, and the Glasses page comes after it.
        assertEquals("PcMirror", order["else"])
        assertEquals(MainPages.PC_MIRROR + 1, MainPages.GLASSES)
        assertTrue(
            source.contains(
                "getItemCount(): Int = if (hasGlassesPage) MainPages.GLASSES + 1 else MainPages.PC_MIRROR + 1"
            )
        )
    }
}
