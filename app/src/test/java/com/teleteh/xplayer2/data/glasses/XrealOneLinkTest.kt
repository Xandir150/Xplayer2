package com.teleteh.xplayer2.data.glasses

import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The link against a fake glasses control port on the loopback interface. */
class XrealOneLinkTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val port = server.localPort
    private val plain = XrealOneSocketFactory { host, p, timeout ->
        Socket().apply { connect(java.net.InetSocketAddress(host, p), timeout) }
    }

    @After
    fun tearDown() = server.close()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun reply(magic: IntArray, body: ByteArray, transaction: Boolean = true): ByteArray {
        val tx = if (transaction) bytes(0x80, 0x00, 0x00, 0x01) else ByteArray(0)
        val length = body.size + tx.size
        return bytes(magic[0], magic[1], 0, 0, length shr 8, length and 0xFF) + tx + body
    }

    private fun InputStream.readN(n: Int) = ByteArray(n).also { buf ->
        var o = 0
        while (o < n) o += read(buf, o, n - o).also { check(it >= 0) }
    }

    /** Accepts one connection and runs [script] on it, returning what the client sent first. */
    private fun fakeGlasses(script: (Socket) -> Unit) = thread(isDaemon = true) {
        server.accept().use(script)
    }

    @Test
    fun `get id round trip skips a button press that arrives before the reply`() {
        fakeGlasses { s ->
            val request = s.getInputStream().readN(12)
            assertArrayEquals(bytes(0x27, 0x29, 0, 0, 0, 6, 0x80, 0, 0, 1, 0x18, 0), request)
            // an unsolicited frame (no transaction id), then the real reply
            s.getOutputStream().write(reply(intArrayOf(0x27, 0x2E), bytes(1, 2, 3), transaction = false))
            s.getOutputStream().write(reply(intArrayOf(0x27, 0x29), bytes(0x22, 0x05, 0x12, 0x03, 0x41, 0x42, 0x43)))
        }
        val session = XrealOneSession(plain, "127.0.0.1", port)
        assertEquals("ABC", runBlocking { session.open() })
        session.close()
    }

    @Test
    fun `set is acknowledged and a set that is not acknowledged is a violation`() {
        fakeGlasses { s ->
            val input = s.getInputStream()
            input.readN(12)
            s.getOutputStream().write(reply(intArrayOf(0x27, 0x29), bytes(0x22, 0x03, 0x12, 0x01, 0x41)))
            // brightness set: ack
            assertArrayEquals(bytes(0x27, 0x1C, 0, 0, 0, 8, 0x80, 0, 0, 1, 0x1A, 2, 8, 7), input.readN(14))
            s.getOutputStream().write(reply(intArrayOf(0x27, 0x1C), bytes(0x22, 0x00)))
            // dimmer set: not an ack
            input.readN(14)
            s.getOutputStream().write(reply(intArrayOf(0x27, 0x27), bytes(0x22, 0x02, 0x10, 0x01)))
        }
        val session = XrealOneSession(plain, "127.0.0.1", port)
        runBlocking {
            session.open()
            session.setBrightness(7)
            try {
                session.setDimmer(XrealOneDimmer.MIDDLE)
                fail("expected a protocol violation")
            } catch (e: XrealOneError.ProtocolViolation) {
                assertTrue(!e.isLinkLoss)
            }
        }
        session.close()
    }

    @Test
    fun `a reply that never comes is a timeout and closes the link`() {
        fakeGlasses { s ->
            s.getInputStream().readN(12)
            Thread.sleep(3_000) // never answers
        }
        val link = XrealOneLink()
        link.open(plain, "127.0.0.1", port, 1_000)
        val started = System.nanoTime()
        try {
            link.exchange(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY, 300)
            fail("expected a timeout")
        } catch (_: XrealOneError.Timeout) {
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        // The socket is closed, so a late reply can never answer the next request.
        try {
            link.exchange(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY, 300)
            fail("expected the link to be closed")
        } catch (_: XrealOneError.Closed) {
        }
    }

    @Test
    fun `a frame longer than one megabyte closes the link`() {
        fakeGlasses { s ->
            s.getInputStream().readN(12)
            s.getOutputStream().write(bytes(0x27, 0x29, 0x00, 0x10, 0x00, 0x01))
            Thread.sleep(500)
        }
        val link = XrealOneLink()
        link.open(plain, "127.0.0.1", port, 1_000)
        try {
            link.exchange(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY, 1_000)
            fail("expected a violation")
        } catch (_: XrealOneError.ProtocolViolation) {
        }
        try {
            link.exchange(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY, 300)
            fail("expected the link to be closed")
        } catch (_: XrealOneError.Closed) {
        }
    }

    @Test
    fun `the glasses closing the connection is a lost link`() {
        fakeGlasses { s -> s.getInputStream().readN(12) }
        val link = XrealOneLink()
        link.open(plain, "127.0.0.1", port, 1_000)
        try {
            link.exchange(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY, 1_000)
            fail("expected a closed link")
        } catch (e: XrealOneError) {
            assertTrue(e.isLinkLoss)
        }
    }

    @Test
    fun `a refused connection is unreachable`() {
        val freePort = ServerSocket(0).use { it.localPort }
        try {
            XrealOneLink().open(plain, "127.0.0.1", freePort, 1_000)
            fail("expected unreachable")
        } catch (_: XrealOneError.Unreachable) {
        }
    }
}
