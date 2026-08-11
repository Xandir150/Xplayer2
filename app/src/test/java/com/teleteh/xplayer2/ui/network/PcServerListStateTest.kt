package com.teleteh.xplayer2.ui.network

import com.teleteh.xplayer2.data.network.PcLinkServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plain JVM tests for [PcServerListState] — no Robolectric/Android dependency in this project
 * (see app/build.gradle.kts test deps), so the class under test is deliberately Android-free.
 */
class PcServerListStateTest {

    private lateinit var state: PcServerListState

    @Before
    fun setUp() {
        state = PcServerListState()
    }

    private fun server(
        name: String = "Living Room PC",
        host: String = "192.168.1.10",
        controlPort: Int = 7890,
        videoPort: Int = 7891,
        protocolVersion: Int = 1,
    ) = PcLinkServer(name, host, controlPort, videoPort, protocolVersion)

    // --- addOrUpdate / dedupe -------------------------------------------------------------

    @Test
    fun `addOrUpdate adds a new server and reports a change`() {
        assertTrue(state.isEmpty())
        val changed = state.addOrUpdate(server())
        assertTrue(changed)
        assertFalse(state.isEmpty())
        assertEquals(1, state.snapshot().size)
    }

    @Test
    fun `addOrUpdate is idempotent for an identical repeat announcement`() {
        state.addOrUpdate(server())
        val changed = state.addOrUpdate(server())
        assertFalse(changed)
        assertEquals(1, state.snapshot().size)
    }

    @Test
    fun `addOrUpdate dedupes by host plus control port, keeping one row`() {
        state.addOrUpdate(server(name = "Old Name", host = "192.168.1.10", controlPort = 7890))
        val changed = state.addOrUpdate(server(name = "New Name", host = "192.168.1.10", controlPort = 7890))
        assertTrue(changed) // content changed even though the key didn't
        assertEquals(1, state.snapshot().size)
        assertEquals("New Name", state.snapshot().single().name)
    }

    @Test
    fun `addOrUpdate treats host case-insensitively for the dedupe key`() {
        // Case differs, so the row's content does change (and addOrUpdate correctly reports that),
        // but it must still collapse into a single row rather than appearing twice.
        state.addOrUpdate(server(host = "MyPC.local"))
        state.addOrUpdate(server(host = "mypc.local"))
        assertEquals(1, state.snapshot().size)
    }

    @Test
    fun `different control ports on the same host are distinct servers`() {
        state.addOrUpdate(server(host = "192.168.1.10", controlPort = 7890))
        state.addOrUpdate(server(host = "192.168.1.10", controlPort = 7990))
        assertEquals(2, state.snapshot().size)
    }

    @Test
    fun `clear empties the list`() {
        state.addOrUpdate(server())
        state.clear()
        assertTrue(state.isEmpty())
        assertTrue(state.snapshot().isEmpty())
    }

    // --- snapshot ordering -----------------------------------------------------------------

    @Test
    fun `snapshot is sorted by name case-insensitively, then host`() {
        state.addOrUpdate(server(name = "zebra", host = "10.0.0.3"))
        state.addOrUpdate(server(name = "Alpha", host = "10.0.0.1"))
        state.addOrUpdate(server(name = "alpha", host = "10.0.0.0"))

        val names = state.snapshot().map { it.name to it.host }
        assertEquals(
            listOf("alpha" to "10.0.0.0", "Alpha" to "10.0.0.1", "zebra" to "10.0.0.3"),
            names
        )
    }

    // --- manual-IP validation ----------------------------------------------------------------

    @Test
    fun `validateHost accepts a plain IPv4 address`() {
        assertEquals("192.168.1.10", PcServerListState.validateHost("192.168.1.10"))
    }

    @Test
    fun `validateHost strips an optional port suffix`() {
        assertEquals("192.168.1.10", PcServerListState.validateHost("192.168.1.10:7890"))
    }

    @Test
    fun `validateHost accepts a hostname`() {
        assertEquals("living-room-pc.local", PcServerListState.validateHost("living-room-pc.local"))
    }

    @Test
    fun `validateHost trims surrounding whitespace`() {
        assertEquals("192.168.1.10", PcServerListState.validateHost("  192.168.1.10  "))
    }

    @Test
    fun `validateHost rejects blank input`() {
        assertNull(PcServerListState.validateHost(""))
        assertNull(PcServerListState.validateHost("   "))
    }

    @Test
    fun `validateHost rejects obviously malformed input`() {
        assertNull(PcServerListState.validateHost("not a host!!"))
        assertNull(PcServerListState.validateHost("http://192.168.1.10"))
        assertNull(PcServerListState.validateHost("192.168.1.10/path"))
    }
}
