package com.teleteh.xplayer2.data.glasses

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/** Opens the TCP connection. The default one binds the socket to the glasses' USB network. */
fun interface XrealOneSocketFactory {
    @Throws(IOException::class)
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
}

/**
 * The TCP connection to the glasses' control port. Blocking, one request at a time.
 *
 * A request is "write one frame, read until the frame with the same magic arrives", and every wait
 * is measured against one deadline. [close] may be called from any thread: it unblocks a pending
 * read.
 */
class XrealOneLink : AutoCloseable {
    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    fun open(factory: XrealOneSocketFactory, host: String, port: Int, timeoutMs: Int) {
        close()
        val opened = try {
            factory.connect(host, port, timeoutMs)
        } catch (e: XrealOneError) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw XrealOneError.Timeout()
        } catch (e: IOException) {
            throw XrealOneError.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        try {
            opened.tcpNoDelay = true
            input = opened.getInputStream()
            output = opened.getOutputStream()
        } catch (e: IOException) {
            runCatching { opened.close() }
            throw XrealOneError.Unreachable(e.message ?: e.javaClass.simpleName)
        }
        socket = opened
    }

    override fun close() {
        val s = socket ?: return
        socket = null
        runCatching { s.close() }
    }

    /** Sends one command and returns the protobuf body of its reply. */
    fun exchange(command: XrealOneProtocol.Command, body: ByteArray, timeoutMs: Int): ByteArray {
        if (socket == null) throw XrealOneError.Closed()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        try {
            return exchangeUnguarded(command, body, deadline)
        } catch (e: XrealOneError.Timeout) {
            // A late reply would otherwise be read as the answer to the next request.
            close()
            throw e
        }
    }

    private fun exchangeUnguarded(command: XrealOneProtocol.Command, body: ByteArray, deadline: Long): ByteArray {
        write(XrealOneProtocol.frame(command, body))
        while (true) {
            val header = readExactly(XrealOneProtocol.HEADER_SIZE, deadline)
            var length = 0L
            for (i in 2 until XrealOneProtocol.HEADER_SIZE) length = length shl 8 or (header[i].toLong() and 0xFF)
            if (length > XrealOneProtocol.MAX_PAYLOAD) {
                close()
                throw XrealOneError.ProtocolViolation("frame is too large")
            }
            val payload = readExactly(length.toInt(), deadline)
            // Button presses and other unsolicited frames can arrive between request and reply.
            if (header[0] == command.magic[0] && header[1] == command.magic[1]) {
                return XrealOneProtocol.bodyOfReply(payload)
            }
        }
    }

    private fun write(data: ByteArray) {
        val out = output ?: throw XrealOneError.Closed()
        try {
            out.write(data)
            out.flush()
        } catch (_: IOException) {
            close()
            throw XrealOneError.Closed()
        }
    }

    private fun readExactly(count: Int, deadline: Long): ByteArray {
        val s = socket
        val stream = input
        if (s == null || stream == null) throw XrealOneError.Closed()
        val data = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) throw XrealOneError.Timeout()
            val read = try {
                s.soTimeout = remainingMs.toInt().coerceAtLeast(1)
                stream.read(data, offset, count - offset)
            } catch (_: SocketTimeoutException) {
                throw XrealOneError.Timeout()
            } catch (_: IOException) {
                close()
                throw XrealOneError.Closed()
            }
            if (read < 0) {
                // An orderly shutdown by the glasses. The link is gone.
                close()
                throw XrealOneError.Closed()
            }
            offset += read
        }
        return data
    }
}

/** One glasses connection, usable from any coroutine: calls run one at a time on a private thread. */
class XrealOneSession(
    private val factory: XrealOneSocketFactory,
    private val host: String = XrealOneProtocol.HOST,
    private val port: Int = XrealOneProtocol.CONTROL_PORT,
) {
    private val link = XrealOneLink()

    /** One thread per session, so a request and its reply can never interleave. */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "xreal-one").apply { isDaemon = true } }
    private val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /**
     * Connects and asks for the device id. A reply to that request is what proves these are
     * glasses we can control, so success here is the single gate for showing any controls.
     */
    suspend fun open(): String = run {
        link.open(factory, host, port, CONNECT_TIMEOUT_MS)
        val reply = link.exchange(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY, REQUEST_TIMEOUT_MS)
        XrealOneProtocol.stringFromReply(reply)
    }

    suspend fun displayConfiguration(): XrealOneDisplayConfiguration? = run {
        val reply = link.exchange(
            XrealOneProtocol.GET_DISPLAY_CONFIGURATION, XrealOneProtocol.GET_BODY, REQUEST_TIMEOUT_MS)
        XrealOneDisplayConfiguration.fromValue(XrealOneProtocol.numberFromReply(reply))
    }

    suspend fun setDisplayConfiguration(value: XrealOneDisplayConfiguration) =
        set(XrealOneProtocol.SET_DISPLAY_CONFIGURATION, value.value)

    suspend fun setBrightness(level: Int) =
        set(XrealOneProtocol.SET_BRIGHTNESS, level.coerceIn(XrealOneBrightness.RANGE))

    suspend fun setDimmer(value: XrealOneDimmer) = set(XrealOneProtocol.SET_DIMMER, value.value)

    fun close() {
        link.close()
        executor.shutdown()
    }

    private suspend fun set(command: XrealOneProtocol.Command, value: Int) = run {
        val reply = link.exchange(command, XrealOneProtocol.setBody(value), REQUEST_TIMEOUT_MS)
        XrealOneProtocol.requireAck(reply)
    }

    private suspend fun <T> run(work: () -> T): T = try {
        withContext(dispatcher) { work() }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        throw XrealOneError.Closed()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 3_000
        const val REQUEST_TIMEOUT_MS = 2_000
    }
}
