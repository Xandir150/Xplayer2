package com.teleteh.xplayer2.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [PcLinkDiscovery]: the reply-JSON parsing (extracted into the pure
 * [PcLinkDiscovery.parseServerResponse] so it needs no socket), the per-pass dedupe rule, and the
 * coroutine behaviour of the listen loop / manual probe.
 *
 * The instances built here pass `context = null` (so no ConnectivityManager binding is attempted)
 * plus a JVM time source and an explicit target list, since `android.os.SystemClock` isn't
 * available in a local unit test and no test should spray real broadcasts across the LAN.
 */
@OptIn(ExperimentalCoroutinesApi::class) // Dispatchers.setMain/resetMain + UnconfinedTestDispatcher
class PcLinkDiscoveryTest {

    @Before
    fun setUp() {
        // PcLinkDiscovery reports results on Dispatchers.Main; there's no Looper here.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun discovery(
        targets: List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
    ) = PcLinkDiscovery(
        context = null,
        nowMs = { System.nanoTime() / 1_000_000L },
        broadcastTargets = { targets }
    )

    // --- reply parsing --------------------------------------------------------------------

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
    fun `ignores unknown fields like the reference decoder`() {
        val json =
            """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":48632,"gpu":"RTX"}"""

        assertEquals("Alex-PC", PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")?.name)
    }

    @Test
    fun `rejects a reply without a name`() {
        // `name` is a required String in the reference decoder (Rust xpl_proto DiscoveryReply) —
        // no silent "XPlayer Link" fallback here either.
        val json = """{"protocolVersion":1,"controlPort":48631,"videoPort":48632}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
    }

    @Test
    fun `rejects a reply with a blank name`() {
        val json = """{"name":"   ","protocolVersion":1,"controlPort":48631,"videoPort":48632}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
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
        val zero = """{"name":"Alex-PC","protocolVersion":1,"controlPort":0,"videoPort":48632}"""
        val negative = """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":-1}"""

        assertNull(PcLinkDiscovery.parseServerResponse(zero, "192.168.1.42"))
        assertNull(PcLinkDiscovery.parseServerResponse(negative, "192.168.1.42"))
    }

    @Test
    fun `rejects ports above the 16 bit range`() {
        // The reference decoder types both ports as u16; 99999999 used to sail through into the
        // intent extras and only blow up later inside Socket().
        val control = """{"name":"Alex-PC","protocolVersion":1,"controlPort":99999999,"videoPort":48632}"""
        val video = """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":65536}"""

        assertNull(PcLinkDiscovery.parseServerResponse(control, "192.168.1.42"))
        assertNull(PcLinkDiscovery.parseServerResponse(video, "192.168.1.42"))
    }

    @Test
    fun `accepts the extremes of the port range`() {
        val json = """{"name":"Alex-PC","protocolVersion":1,"controlPort":1,"videoPort":65535}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals(1, server?.controlPort)
        assertEquals(65535, server?.videoPort)
    }

    @Test
    fun `rejects reply with missing protocol version`() {
        val json = """{"name":"Alex-PC","controlPort":48631,"videoPort":48632}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
    }

    @Test
    fun `rejects a protocol version this client does not speak`() {
        val json = """{"name":"Alex-PC","protocolVersion":2,"controlPort":48631,"videoPort":48632}"""

        assertNull(PcLinkDiscovery.parseServerResponse(json, "192.168.1.42"))
    }

    @Test
    fun `rejects the probe message itself echoed back`() {
        assertNull(PcLinkDiscovery.parseServerResponse(PcLinkDiscovery.PROBE_MESSAGE, "192.168.1.42"))
    }

    // --- serverId (design doc §9.1: additive, optional identity fingerprint) --------------

    @Test
    fun `parses a reply carrying a serverId`() {
        val id = "f35e5616160a30bf3c6e79fa73c576d40205e8fc3ba4e1c6dcf93e6b98e857b"
        val json =
            """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":48632,"serverId":"$id"}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals(id, server?.serverId)
    }

    @Test
    fun `a reply with no serverId still parses, with it null`() {
        // A server built before pairing existed must stay discoverable — serverId is additive.
        val json = """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":48632}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertEquals("Alex-PC", server?.name)
        assertNull(server?.serverId)
    }

    @Test
    fun `a blank serverId is treated as absent`() {
        val json =
            """{"name":"Alex-PC","protocolVersion":1,"controlPort":48631,"videoPort":48632,"serverId":"   "}"""

        val server = PcLinkDiscovery.parseServerResponse(json, "192.168.1.42")

        assertNull(server?.serverId)
    }

    // --- per-pass dedupe ------------------------------------------------------------------

    private fun server(
        name: String = "Alex-PC",
        host: String = "192.168.1.42",
        controlPort: Int = 48631,
        videoPort: Int = 48632
    ) = PcLinkServer(name, host, controlPort, videoPort, 1)

    @Test
    fun `dedupe emits a repeat announcement only once`() {
        val dedupe = PcLinkDiscovery.PassDedupe()

        assertTrue(dedupe.shouldEmit("192.168.1.42", server()))
        assertFalse(dedupe.shouldEmit("192.168.1.42", server()))
        assertFalse(dedupe.shouldEmit("192.168.1.42", server()))
    }

    @Test
    fun `dedupe re-emits when a known host changed its name or ports`() {
        val dedupe = PcLinkDiscovery.PassDedupe()
        dedupe.shouldEmit("192.168.1.42", server())

        assertTrue(dedupe.shouldEmit("192.168.1.42", server(name = "Alex-PC (office)")))
        assertTrue(dedupe.shouldEmit("192.168.1.42", server(name = "Alex-PC (office)", controlPort = 49000)))
        assertFalse(dedupe.shouldEmit("192.168.1.42", server(name = "Alex-PC (office)", controlPort = 49000)))
    }

    @Test
    fun `dedupe keeps different hosts apart`() {
        val dedupe = PcLinkDiscovery.PassDedupe()

        assertTrue(dedupe.shouldEmit("192.168.1.42", server(host = "192.168.1.42")))
        assertTrue(dedupe.shouldEmit("192.168.1.77", server(host = "192.168.1.77")))
    }

    // --- discovery loop / probe coroutines --------------------------------------------------

    @Test
    fun `discover stops promptly when cancelled instead of running out its window`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            // A window far longer than the test: the only way join() returns quickly is if the loop
            // honours cancellation (it used to keep broadcasting for the whole window).
            val job = discovery().discover(scope, listenDurationMs = 60_000L) { }
            delay(300)
            assertTrue("discovery pass should still be running", job.isActive)

            job.cancel()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `discover ends on its own once the listen window elapses`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val job = discovery().discover(scope, listenDurationMs = 300L) { }
            withTimeout(5_000) { job.join() }
            assertFalse(job.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `probeHost reports null exactly once when the host never answers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val results = CopyOnWriteArrayList<PcLinkServer?>()
            val reported = CountDownLatch(1)
            // TEST-NET-1 (RFC 5737): reserved for documentation, so nothing ever answers.
            discovery().probeHost(scope, "192.0.2.1", timeoutMs = 400L) {
                results += it
                reported.countDown()
            }

            // The caller learns about the failure at all — the old API only ever called back on
            // success, which is what forced the UI into a racing wall-clock timer of its own.
            assertTrue("probeHost never reported", reported.await(5, TimeUnit.SECONDS))
            delay(600) // long enough for a second, contract-breaking callback to show up
            assertEquals(1, results.size)
            assertNull(results.single())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `probeHost reports null for a host that cannot be resolved`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val results = CopyOnWriteArrayList<PcLinkServer?>()
            val reported = CountDownLatch(1)
            // Waiting on the callback, not on the job: a DNS lookup that outlives the timeout is
            // abandoned (its thread finishes later) — exactly the case the timeout exists for.
            discovery().probeHost(scope, "no-such-host.invalid", timeoutMs = 700L) {
                results += it
                reported.countDown()
            }

            assertTrue("probeHost never reported", reported.await(10, TimeUnit.SECONDS))
            assertEquals(1, results.size)
            assertNull(results.single())
        } finally {
            scope.cancel()
        }
    }
}
