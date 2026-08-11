package com.teleteh.xplayer2.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [PcLinkDiscovery]'s reply-JSON parsing, extracted into the testable
 * [PcLinkDiscovery.parseServerResponse] so it can be exercised without a real socket.
 */
class PcLinkDiscoveryTest {

    @Test
    fun `parses a well formed reply`() {
        val json = """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":48632}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals(
            PcLinkServer(
                name = "Alex-PC",
                host = "192.168.1.42",
                controlPort = 48631,
                videoPort = 48632,
                protocolVersion = 1
            ),
            server
        )
    }

    @Test
    fun `field order does not matter`() {
        val json = """{"videoPort":48632,"controlPort":48631,"protocolVersion":1,"name":"Alex-PC"}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals("Alex-PC", server?.name)
        assertEquals(48631, server?.controlPort)
        assertEquals(48632, server?.videoPort)
    }

    @Test
    fun `falls back to default name when name is missing`() {
        val json = """{"protocolVersion":1,"controlPort":48631,"videoPort":48632}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals("XPlayer Link", server?.name)
    }

    @Test
    fun `falls back to default name when name is blank`() {
        val json = """{"name":"   ","protocolVersion":1,"controlPort":48631,"videoPort":48632}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals("XPlayer Link", server?.name)
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(PcLinkDiscovery.parseServerResponse("not json at all", "192.168.1.42"))
    }

    @Test
    fun `rejects empty string`() {
        assertNull(PcLinkDiscovery.parseServerResponse("", "192.168.1.42"))
    }

    @Test
    fun `rejects reply missing required ports`() {
        val json = """{"name":"Alex-PC","protocolVersion":1}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
    }

    @Test
    fun `rejects reply with zero or negative ports`() {
        val json = """{"name":"Alex-PC","protocolVersion":1,"controlPort":0,"videoPort":48632}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
    }

    @Test
    fun `rejects reply with missing protocol version`() {
        val json = """{"name":"Alex-PC","controlPort":48631,"videoPort":48632}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
    }

    @Test
    fun `rejects the probe message itself echoed back`() {
        assertNull(PcLinkDiscovery.parseServerResponse(PcLinkDiscovery.PROBE_MESSAGE, "192.168.1.42"))
    }
}
