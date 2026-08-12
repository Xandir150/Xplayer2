package com.teleteh.xplayer2.ui.pclink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The two decisions the PC-Mirror tab makes that are not about drawing: whether a tap is allowed to
 * open a connect screen, and what a paired row says under the PC's name.
 *
 * Both are plain Kotlin on purpose — the fragment around them needs a device, and these are exactly
 * the parts that were wrong: a tap with no guard started two sessions, and a row with only a name
 * and an address could not be told from its own orphan.
 */
class PcMirrorTabTest {

    // --- one connect screen at a time ---------------------------------------------------------

    @Test
    fun `the second tap of a double tap opens nothing`() {
        val latch = ConnectLaunchLatch()
        assertTrue(latch.claim())
        assertFalse("a second connect screen probes the same PC in parallel", latch.claim())
        assertFalse(latch.claim())
    }

    /** Two different rows in quick succession is the same fault: the first tap owns the launch. */
    @Test
    fun `a different row tapped a moment later is refused too`() {
        val latch = ConnectLaunchLatch()
        assertTrue(latch.claim())
        assertFalse(latch.claim())
    }

    /** Coming back to the tab is what makes tapping work again — nothing else clears it. */
    @Test
    fun `the tab resuming makes the rows live again`() {
        val latch = ConnectLaunchLatch()
        assertTrue(latch.claim())
        latch.release()
        assertTrue(latch.claim())
        // Releasing something that was never claimed is a no-op, not a crash: onResume runs first.
        latch.release()
        latch.release()
        assertTrue(ConnectLaunchLatch().claim())
    }

    // --- telling two records for one machine apart --------------------------------------------

    private val fixedNow = Instant.parse("2026-08-12T12:00:00Z").toEpochMilli()

    /** Stands in for DateUtils, which needs a device; the wording is Android's problem, not ours. */
    private val ago: (Long) -> CharSequence = { seen -> "${(fixedNow - seen) / 60_000} minutes ago" }

    @Test
    fun `a regenerated PCs twin row is not the same line of text`() {
        val live = pairedRowSubtitle(
            lastHost = "192.168.1.10",
            lastSeenAt = "2026-08-12T11:58:00Z",
            notSeenLabel = "Address not recorded yet",
            relativeTime = ago
        )
        val orphan = pairedRowSubtitle(
            lastHost = "192.168.1.10",
            lastSeenAt = "2026-07-22T09:00:00Z",
            notSeenLabel = "Address not recorded yet",
            relativeTime = ago
        )
        assertEquals("192.168.1.10 · 2 minutes ago", live)
        assertNotEquals("the dead record must not read exactly like the live one", live, orphan)
        assertTrue(orphan.startsWith("192.168.1.10 · "))
    }

    @Test
    fun `a PC we have no address for still says when we last spoke to it`() {
        assertEquals(
            "Address not recorded yet · 60 minutes ago",
            pairedRowSubtitle(null, "2026-08-12T11:00:00Z", "Address not recorded yet", ago)
        )
    }

    /** A record from another version of the app: say what we know rather than invent a date. */
    @Test
    fun `an unreadable timestamp leaves the address alone`() {
        assertEquals(
            "192.168.1.10",
            pairedRowSubtitle("192.168.1.10", "", "Address not recorded yet", ago)
        )
        assertEquals(
            "192.168.1.10",
            pairedRowSubtitle("192.168.1.10", "last Tuesday", "Address not recorded yet", ago)
        )
    }
}
