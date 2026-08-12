package com.teleteh.xplayer2.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [PcLinkPhoneResponder]'s pure parts: which datagrams we answer, exactly what the
 * reply says, and which `pair_invite`s are worth prompting about.
 *
 * The socket loop itself isn't exercised here (binding UDP 48630 in a unit test would be flaky and
 * would fight whatever else is on the machine); [PcLinkPairingClientTest] covers the wire format
 * end of things and the responder's own loop is a thin receive/reply around these functions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PcLinkPhoneResponderTest {

    private val clientId = "300c9c9603b92a4b39ed3958bf9240114804db4fd373012c0ca47432d63425ae"

    @Before
    fun setUp() {
        // The responder delivers invites on the main thread; there's no Looper here.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeLease : MulticastLease {
        val acquires = AtomicInteger()
        val releases = AtomicInteger()
        val held: Boolean get() = acquires.get() > releases.get()
        override fun acquire() { acquires.incrementAndGet() }
        override fun release() { releases.incrementAndGet() }
    }

    // ---- multicast lock lifecycle ----------------------------------------------------------------

    /**
     * The PC's probe is a **broadcast**, and many Wi-Fi chipsets stop passing broadcast/multicast
     * frames to the CPU once the device idles — a power optimisation that is invisible until you're
     * the one waiting for a broadcast. Without a `WifiManager.MulticastLock` reverse discovery works
     * on some phones, works on others only while something else keeps the radio busy, and fails
     * intermittently on the rest, which is the worst of the three to diagnose.
     *
     * The lock must also be released the moment we stop listening: holding it defeats that same
     * optimisation and costs battery. "Released on every exit path" is precisely the kind of rule
     * that lives only in control flow, so this drives the real socket loop on an ephemeral port and
     * asserts both halves.
     */
    @Test
    fun holdsTheMulticastLockForExactlyAsLongAsItListens() {
        val lease = FakeLease()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Collected so a bind failure reports *why* rather than as a bare timeout.
        val problems = java.util.concurrent.CopyOnWriteArrayList<String>()
        val responder = PcLinkPhoneResponder(
            context = null, clientName = "Pixel 9 Pro", clientId = clientId,
            nowMs = { 0L }, lease = lease, listenPort = 0,
            logInfo = { message, error -> problems += "$message ($error)" }, onInvite = {}
        )
        try {
            responder.start(scope)
            val port = awaitBoundPort(responder, problems)
            assertTrue("the lock must be held while listening", lease.held)
            assertEquals(1, lease.acquires.get())

            // And it really is serving on that socket: probe it and check the reply on the wire.
            DatagramSocket().use { client ->
                client.soTimeout = 5_000
                val probe = PcLinkPhoneResponder.PHONE_PROBE_MESSAGE.toByteArray(Charsets.US_ASCII)
                client.send(
                    DatagramPacket(probe, probe.size, InetAddress.getLoopbackAddress(), port)
                )
                val buffer = ByteArray(2048)
                val reply = DatagramPacket(buffer, buffer.size)
                client.receive(reply)
                val json = JSONObject(String(reply.data, 0, reply.length, Charsets.UTF_8))
                assertEquals("Pixel 9 Pro", json.getString("name"))
                assertEquals("client", json.getString("deviceType"))
                assertEquals(clientId, json.getString("clientId"))
            }
        } finally {
            responder.stop()
            scope.cancel()
        }

        awaitUntil("the lock must be released once we stop listening") { !lease.held }
        assertEquals("released exactly once", 1, lease.releases.get())
    }

    /**
     * A lock held by a responder that isn't listening is pure battery cost, so the bind has to
     * succeed first. Here the port is already taken, which is the real-world case this guards: a
     * device that somehow already has something on the discovery port.
     */
    @Test
    fun takesNoLockWhenThePortCannotBeBound() {
        val lease = FakeLease()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { squatter ->
            val responder = PcLinkPhoneResponder(
                context = null, clientName = "Pixel 9 Pro", clientId = clientId,
                nowMs = { 0L }, lease = lease, listenPort = squatter.localPort,
                logInfo = { _, _ -> }, onInvite = {}
            )
            try {
                responder.start(scope)
                // Give the (failing) bind time to happen; nothing should ever be acquired.
                awaitUntil("responder should have given up") { responder.boundPort == 0 }
                Thread.sleep(200)
                assertEquals(0, lease.acquires.get())
                assertFalse(lease.held)
            } finally {
                responder.stop()
                scope.cancel()
            }
        }
    }

    private fun awaitBoundPort(responder: PcLinkPhoneResponder, problems: List<String>): Int {
        awaitUntil({ "responder never bound a port; reported: $problems" }) { responder.boundPort != 0 }
        return responder.boundPort
    }

    private fun awaitUntil(message: String, condition: () -> Boolean) =
        awaitUntil({ message }, condition)

    private fun awaitUntil(message: () -> String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message())
    }

    // ---- probe recognition ----------------------------------------------------------------------

    @Test
    fun answersOnlyThePhoneProbe() {
        assertTrue(PcLinkPhoneResponder.isPhoneProbe("XPL2-PHONE-PROBE v1"))
        // §1's CR/LF tolerance, so an ad-hoc probe from netcat/echo still works.
        assertTrue(PcLinkPhoneResponder.isPhoneProbe("XPL2-PHONE-PROBE v1\n"))
        assertTrue(PcLinkPhoneResponder.isPhoneProbe("XPL2-PHONE-PROBE v1\r\n"))
    }

    /**
     * The responder shares UDP 48630 with forward discovery, so it sees every server probe on the
     * LAN — including the ones this very app broadcasts. Answering those would be nonsense.
     */
    @Test
    fun ignoresServerProbesAndEverythingElse() {
        assertFalse(PcLinkPhoneResponder.isPhoneProbe(PcLinkDiscovery.PROBE_MESSAGE))
        assertFalse(PcLinkPhoneResponder.isPhoneProbe("XPL2-PHONE-PROBE v2"))
        assertFalse(PcLinkPhoneResponder.isPhoneProbe("xpl2-phone-probe v1"))
        assertFalse(PcLinkPhoneResponder.isPhoneProbe("XPL2-PHONE-PROBE"))
        assertFalse(PcLinkPhoneResponder.isPhoneProbe(" XPL2-PHONE-PROBE v1"))
        assertFalse(PcLinkPhoneResponder.isPhoneProbe(""))
        assertFalse(PcLinkPhoneResponder.isPhoneProbe("{\"type\":\"pair_invite\"}"))
    }

    @Test
    fun probeStringIsNineteenAsciiBytes() {
        // Pinned because protocol.md §1.1 states the length; the two must not drift apart.
        val bytes = PcLinkPhoneResponder.PHONE_PROBE_MESSAGE.toByteArray(Charsets.US_ASCII)
        assertEquals(19, bytes.size)
        assertEquals("XPL2-PHONE-PROBE v1", String(bytes, Charsets.US_ASCII))
    }

    // ---- reply ---------------------------------------------------------------------------------

    @Test
    fun replyCarriesExactlyTheSpecifiedFields() {
        val reply = JSONObject(PcLinkPhoneResponder.replyJson("Pixel 9 Pro", clientId))
        assertEquals("Pixel 9 Pro", reply.getString("name"))
        assertEquals("client", reply.getString("deviceType"))
        assertEquals(1, reply.getInt("protocolVersion"))
        assertEquals(1, reply.getInt("pairingVersion"))
        assertEquals(clientId, reply.getString("clientId"))
        assertEquals(5, reply.length())
        // One datagram, one line, no framing of its own.
        assertFalse(PcLinkPhoneResponder.replyJson("Pixel 9 Pro", clientId).contains("\n"))
    }

    @Test
    fun replySurvivesAwkwardDeviceNames() {
        val reply = JSONObject(PcLinkPhoneResponder.replyJson("Аня\"s \\ телефон", clientId))
        assertEquals("Аня\"s \\ телефон", reply.getString("name"))
    }

    // ---- invites -------------------------------------------------------------------------------

    @Test
    fun parsesAWellFormedInvite() {
        val invite = PcLinkPhoneResponder.parseInvite(
            """{"type":"pair_invite","name":"Living Room PC","serverId":"f35e5616","controlPort":48631,
               "protocolVersion":1,"pairingVersion":1}""",
            "192.168.1.10"
        )!!
        assertEquals("Living Room PC", invite.serverName)
        assertEquals("f35e5616", invite.serverId)
        assertEquals(48631, invite.controlPort)
        assertEquals(1, invite.protocolVersion)
        assertEquals(1, invite.pairingVersion)
        // The source address wins: an invite carries no address field precisely so it can't
        // redirect us at a third party.
        assertEquals("192.168.1.10", invite.host)
    }

    @Test
    fun serverIdIsOptionalAndUnknownFieldsAreIgnored() {
        val invite = PcLinkPhoneResponder.parseInvite(
            """{"type":"pair_invite","name":"PC","controlPort":48631,"protocolVersion":1,
               "pairingVersion":1,"somethingNew":{"a":1}}""",
            "10.0.0.5"
        )!!
        assertNull(invite.serverId)
        assertEquals("PC", invite.serverName)

        val blankId = PcLinkPhoneResponder.parseInvite(
            """{"type":"pair_invite","name":"PC","serverId":"  ","controlPort":48631,
               "protocolVersion":1,"pairingVersion":1}""",
            "10.0.0.5"
        )!!
        assertNull(blankId.serverId)
    }

    /** `pairingVersion` defaults to 1 — an older PC that predates the field still gets a prompt. */
    @Test
    fun missingPairingVersionDefaultsToOne() {
        val invite = PcLinkPhoneResponder.parseInvite(
            """{"type":"pair_invite","name":"PC","controlPort":48631,"protocolVersion":1}""",
            "10.0.0.5"
        )!!
        assertEquals(1, invite.pairingVersion)
    }

    @Test
    fun rejectsInvitesWeCouldNotActOn() {
        fun parse(json: String) = PcLinkPhoneResponder.parseInvite(json, "10.0.0.5")

        assertNull("not an invite", parse("""{"type":"pair_reject","reason":"declined"}"""))
        assertNull("no type at all", parse("""{"name":"PC","controlPort":48631,"protocolVersion":1}"""))
        assertNull("nameless", parse("""{"type":"pair_invite","name":"","controlPort":48631,"protocolVersion":1}"""))
        assertNull("no port", parse("""{"type":"pair_invite","name":"PC","protocolVersion":1}"""))
        assertNull(
            "port out of range",
            parse("""{"type":"pair_invite","name":"PC","controlPort":70000,"protocolVersion":1}""")
        )
        assertNull(
            "port zero",
            parse("""{"type":"pair_invite","name":"PC","controlPort":0,"protocolVersion":1}""")
        )
        assertNull(
            "protocol we don't speak",
            parse("""{"type":"pair_invite","name":"PC","controlPort":48631,"protocolVersion":2}""")
        )
        // A prompt for a ceremony that could only end in pair_reject{"version"} is worse than none.
        assertNull(
            "pairing method we don't speak",
            parse("""{"type":"pair_invite","name":"PC","controlPort":48631,"protocolVersion":1,"pairingVersion":2}""")
        )
        assertNull("not json", parse("XPL2-PHONE-PROBE v1"))
        assertNull("empty", parse(""))
        assertNull("json array", parse("""[{"type":"pair_invite"}]"""))
    }

    /** Our own discovery reply must never be mistaken for an invite (both are JSON on 48630). */
    @Test
    fun aServerDiscoveryReplyIsNotAnInvite() {
        assertNull(
            PcLinkPhoneResponder.parseInvite(
                """{"name":"Living Room PC","protocolVersion":1,"controlPort":48631,"videoPort":48632}""",
                "192.168.1.10"
            )
        )
    }
}
