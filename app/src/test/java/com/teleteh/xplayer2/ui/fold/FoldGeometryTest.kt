package com.teleteh.xplayer2.ui.fold

import com.teleteh.xplayer2.ui.fold.FoldGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldGeometryTest {
    @Test fun `tabletop excludes hinge and respects system padding`() {
        val panes = FoldGeometry.split(Box(0, 24, 800, 960), Box(0, 480, 800, 500), true)!!
        assertEquals(Box(0, 24, 800, 480), panes.first)
        assertEquals(Box(0, 500, 800, 960), panes.second)
    }

    @Test fun `zero width vertical fold is a valid split`() {
        val panes = FoldGeometry.split(Box(0, 0, 1200, 800), Box(600, 0, 600, 800), false)!!
        assertEquals(600, panes.first.width)
        assertEquals(600, panes.second.width)
    }

    @Test fun `fold outside resized window does not split`() {
        assertNull(FoldGeometry.split(Box(0, 0, 500, 400), Box(0, 600, 1000, 600), true))
        assertNull(FoldGeometry.split(Box(0, 0, 500, 400), Box(0, 0, 0, 400), false))
    }

    @Test fun `partial feature does not separate the content`() {
        assertNull(FoldGeometry.split(Box(0, 0, 800, 1000), Box(40, 500, 800, 500), true))
    }

    @Test fun `inset origin and unequal panes are preserved`() {
        val panes = FoldGeometry.split(Box(12, 30, 900, 1000), Box(-8, 390, 920, 410), true)!!
        assertEquals(360, panes.first.height)
        assertEquals(590, panes.second.height)
    }
}
