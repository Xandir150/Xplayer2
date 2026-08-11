package com.teleteh.xplayer2.data.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the IMU stream from XREAL Air-series glasses over USB HID.
 *
 * This class is the USB plumbing only — endpoint discovery, the start/stop handshake, the factory
 * calibration fetch and the read loop. Everything about the *format* lives in [XrealImuPacket] and
 * [XrealImuCalibration], which have no Android dependencies and are covered by JVM unit tests; the
 * format knowledge itself comes from badicsalex/ar-drivers-rs and TheJackiMonster/nrealAirLinuxDriver
 * (both MIT — see `NOTICE.md` in this directory).
 *
 * What it does, in order:
 *
 *   1. find the interrupt-IN endpoint on a HID interface whose address is 0x84, plus a matching
 *      OUT endpoint for commands
 *   2. stop the stream (msg 0x19 / 0x00) so the endpoint is quiet
 *   3. read the per-unit factory calibration blob (msg 0x14 for the length, 0x15 for each chunk)
 *      and parse out the gyro / accelerometer / magnetometer bias and scale. This is best-effort:
 *      any failure logs and falls back to [XrealImuCalibration.IDENTITY], and playback of the
 *      stream is unaffected
 *   4. start the stream (msg 0x19 / 0x01) and run a reader thread decoding 64-byte reports
 *   5. call back into [Listener] on the reader thread
 *
 * Fusion is [HeadOrientationTracker]'s job, not this class's. Stop the reader via [stop] before the
 * underlying device or connection is closed; the thread joins on stop.
 */
class XrealImuReader(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {

    fun interface Listener {
        /**
         * Called on the reader thread with gyro angular velocity (deg/s; x=pitch, y=yaw, z=roll
         * rate from XREAL's frame), linear acceleration (ax/ay/az in g), the raw IMU temperature
         * word, and the Android-side time-of-receipt nanos.
         */
        fun onSample(
            gxDegSec: Float, gyDegSec: Float, gzDegSec: Float,
            ax: Float, ay: Float, az: Float, tempRaw: Float, tNanos: Long,
        )

        /**
         * Called on the reader thread with the fully decoded report — the same gyro and accel as
         * [onSample] plus magnetometer, temperature in °C, the device's own clock and whether
         * factory calibration was applied.
         *
         * The default fans out to [onSample], so existing lambda listeners keep working untouched.
         * Override it to get the extra channels; [sample] is **reused between reports**, so copy
         * anything you intend to keep past the call.
         */
        fun onSampleFull(sample: XrealImuSample) {
            onSample(
                sample.gxDegSec, sample.gyDegSec, sample.gzDegSec,
                sample.axG, sample.ayG, sample.azG, sample.tempRaw, sample.hostNanos,
            )
        }
    }

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null
    private var commandEndpoint: UsbEndpoint? = null
    private var commandInterfaceIndex: Int = -1

    /**
     * Factory calibration read off the glasses at [start]. [XrealImuCalibration.IDENTITY] until a
     * blob has been read successfully, and permanently so if the read fails.
     */
    @Volatile
    var calibration: XrealImuCalibration = XrealImuCalibration.IDENTITY
        private set

    /** Returns true if the reader thread is active. */
    fun isRunning(): Boolean = running.get()

    /**
     * Locate the IMU endpoints, read calibration, send the start-stream command, and spawn the
     * reader thread. Returns false if no IMU endpoint was found or the device rejected the start
     * command. Blocking USB I/O — call off the main thread.
     */
    fun start(listener: Listener): Boolean {
        if (running.get()) return true
        val (intf, endpoint) = findImuEndpoint() ?: run {
            Log.w(TAG, "No IMU IN endpoint (HID + addr 0x84) on device ${device.deviceName}")
            return false
        }
        // Prefer talking to the interface the IMU actually lives on; sendImuCommand falls back to
        // sweeping every HID interface if this one doesn't answer.
        commandInterfaceIndex = indexOfInterface(intf)
        commandEndpoint = findOutEndpoint(intf)

        // Quiesce the stream, grab the calibration blob, then turn the stream back on — the order
        // both reference drivers use, because command replies and sensor reports share one endpoint.
        sendImuStreamControl(enable = false)
        calibration = readCalibration(endpoint) ?: XrealImuCalibration.IDENTITY

        if (!sendImuStreamControl(enable = true)) {
            Log.w(TAG, "IMU start command rejected by glasses; reader not started")
            return false
        }
        Log.i(
            TAG,
            "IMU stream starting on interface ${intf.id} endpoint 0x${endpoint.address.toString(16)}, " +
                "calibration=$calibration",
        )
        running.set(true)
        readerThread = Thread({ runReadLoop(endpoint, listener) }, "XrealImuReader").also { it.start() }
        return true
    }

    /**
     * Stop the reader and tell the glasses to halt the IMU stream. Safe to call multiple times.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { readerThread?.join(500) } catch (_: InterruptedException) { }
        readerThread = null
        // Politely tell the glasses to stop pushing packets. If the command fails (e.g. cable
        // already unplugged) we don't care — the reader thread is already shut down.
        try { sendImuStreamControl(enable = false) } catch (_: Throwable) { }
        Log.i(TAG, "IMU stream stopped")
    }

    private fun runReadLoop(endpoint: UsbEndpoint, listener: Listener) {
        val buffer = ByteArray(readBufferSize(endpoint))
        val sample = XrealImuSample()
        val cal = calibration
        while (running.get()) {
            try {
                // 200ms timeout matches android-nreal — IMU pushes packets periodically so a
                // short timeout just spins the CPU, while a longer one delays stop().
                val read = connection.bulkTransfer(endpoint, buffer, buffer.size, 200)
                if (read < 0) {
                    // Endpoint went away (usually device disconnect). Bail out.
                    break
                }
                if (read == 0) continue
                if (!XrealImuPacket.parseInto(buffer, read, System.nanoTime(), sample)) continue
                cal.applyTo(sample)
                listener.onSampleFull(sample)
            } catch (t: Throwable) {
                Log.w(TAG, "IMU read loop error: ${t.message}")
                break
            }
        }
    }

    /**
     * Fetch and parse the factory calibration blob, or null if anything at all goes wrong — a
     * model that doesn't implement the command, a truncated transfer, JSON we don't recognise.
     * Never throws; the IMU stream is far more important than the calibration.
     *
     * The whole thing is under a wall-clock budget: the per-read timeouts alone could otherwise add
     * up to tens of seconds on an unresponsive device, and the user is waiting on head tracking.
     */
    private fun readCalibration(endpoint: UsbEndpoint): XrealImuCalibration? {
        val deadline = System.nanoTime() + CALIBRATION_BUDGET_MS * 1_000_000L
        try {
            val lengthBytes = runCommand(endpoint, XrealImuPacket.MSG_GET_CAL_DATA_LENGTH, deadline)
            if (lengthBytes == null || lengthBytes.size < 4) {
                Log.i(TAG, "No factory IMU calibration (length query unanswered); using identity")
                return null
            }
            val length = (lengthBytes[0].toLong() and 0xFF) or
                ((lengthBytes[1].toLong() and 0xFF) shl 8) or
                ((lengthBytes[2].toLong() and 0xFF) shl 16) or
                ((lengthBytes[3].toLong() and 0xFF) shl 24)
            if (length <= 0L || length > MAX_CALIBRATION_BYTES) {
                Log.w(TAG, "Implausible IMU calibration length $length; using identity")
                return null
            }

            val blob = StringBuilder(length.toInt())
            var received = 0
            while (received < length) {
                if (System.nanoTime() > deadline) {
                    Log.w(TAG, "IMU calibration read timed out at $received/$length bytes; using identity")
                    return null
                }
                val chunk = runCommand(endpoint, XrealImuPacket.MSG_CAL_DATA_GET_NEXT_SEGMENT, deadline)
                if (chunk == null || chunk.isEmpty()) {
                    Log.w(TAG, "IMU calibration stalled at $received/$length bytes; using identity")
                    return null
                }
                val take = minOf(chunk.size.toLong(), length - received).toInt()
                blob.append(String(chunk, 0, take, Charsets.UTF_8))
                received += take
            }

            val parsed = XrealImuCalibration.parse(blob.toString())
            if (parsed == null) {
                Log.w(TAG, "IMU calibration blob unparseable ($received bytes); using identity")
                return null
            }
            Log.i(TAG, "IMU factory calibration loaded: $parsed")
            return parsed
        } catch (t: Throwable) {
            Log.w(TAG, "IMU calibration read failed (${t.message}); using identity")
            return null
        }
    }

    /**
     * Send a command and wait for the reply carrying the same message ID, skipping any sensor
     * reports that were already in flight. Returns the reply payload, or null on timeout.
     */
    private fun runCommand(endpoint: UsbEndpoint, msgId: Int, deadline: Long): ByteArray? {
        if (!sendImuCommand(XrealImuPacket.buildCommand(msgId, size = commandSize(endpoint)))) return null
        val buffer = ByteArray(readBufferSize(endpoint))
        var attempts = 0
        while (attempts++ < MAX_RESPONSE_READS && System.nanoTime() <= deadline) {
            val read = connection.bulkTransfer(endpoint, buffer, buffer.size, COMMAND_TIMEOUT_MS)
            if (read < 0) return null
            if (read == 0) continue
            XrealImuPacket.parseResponse(buffer, read, msgId)?.let { return it }
        }
        return null
    }

    /** Build and dispatch the "set IMU stream state" command (msg 0x19, payload 0x01 / 0x00). */
    private fun sendImuStreamControl(enable: Boolean): Boolean {
        val size = commandEndpoint?.let { commandSize(it) } ?: XrealImuPacket.REPORT_SIZE
        val packet = XrealImuPacket.buildCommand(
            XrealImuPacket.MSG_START_IMU_DATA,
            byteArrayOf(if (enable) 0x01 else 0x00),
            size,
        )
        return sendImuCommand(packet)
    }

    /**
     * Push a command frame at the glasses. The XREAL firmware accepts it either over a HID
     * Set-Report control transfer or as a write to an interrupt-OUT endpoint on a HID interface,
     * and which one works varies by model — so try the IMU interface's own OUT endpoint first and
     * then sweep every HID interface, exactly as this class has always done.
     */
    private fun sendImuCommand(packet: ByteArray): Boolean {
        var ok = false
        commandEndpoint?.let { ep ->
            val n = try { connection.bulkTransfer(ep, packet, packet.size, 1000) } catch (_: Throwable) { -1 }
            if (n == packet.size) ok = true
        }
        if (commandInterfaceIndex >= 0 && sendSetReport(commandInterfaceIndex, packet)) ok = true
        if (ok) return true

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            if (sendSetReport(i, packet)) ok = true
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_OUT &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT
                ) {
                    val n = try { connection.bulkTransfer(ep, packet, packet.size, 1000) } catch (_: Throwable) { -1 }
                    if (n == packet.size) ok = true
                }
            }
        }
        return ok
    }

    private fun sendSetReport(interfaceIndex: Int, packet: ByteArray): Boolean {
        val setReportType = (0x02 shl 8) or 0  // Output report, ID 0
        val sent = try {
            connection.controlTransfer(
                /* requestType */ 0x21, // host->device | class | interface
                /* request */ 0x09,     // SET_REPORT
                /* value */ setReportType,
                /* index */ interfaceIndex,
                packet,
                packet.size,
                1000,
            )
        } catch (_: Throwable) { -1 }
        return sent > 0
    }

    private fun findImuEndpoint(): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            for (j in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN &&
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                    ep.address == 0x84
                ) {
                    return intf to ep
                }
            }
        }
        return null
    }

    private fun findOutEndpoint(intf: UsbInterface): UsbEndpoint? {
        for (j in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(j)
            if (ep.direction == UsbConstants.USB_DIR_OUT && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) {
                return ep
            }
        }
        return null
    }

    private fun indexOfInterface(intf: UsbInterface): Int {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i) == intf) return i
        }
        return -1
    }

    /**
     * Command frames are sent at the interface's max payload — 64 bytes on Air / Air 2 / Air 2 Pro,
     * 512 on Air 2 Ultra. The descriptor is the honest source for that; the constants in the
     * reference drivers are just a per-PID table of the same thing, and reading the descriptor also
     * covers the XREAL One models neither driver knows about.
     */
    private fun commandSize(endpoint: UsbEndpoint): Int =
        endpoint.maxPacketSize.coerceIn(XrealImuPacket.REPORT_SIZE, MAX_PAYLOAD_SIZE)

    /** Read buffer: at least one sensor report, up to the endpoint's max packet. */
    private fun readBufferSize(endpoint: UsbEndpoint): Int = commandSize(endpoint)

    companion object {
        private const val TAG = "XrealImuReader"

        /** Air 2 Ultra's IMU interface; the rest of the family is 64. */
        private const val MAX_PAYLOAD_SIZE = 512

        /** Per-read timeout while waiting for a command reply. */
        private const val COMMAND_TIMEOUT_MS = 250

        /** Reply reads per command before giving up — matches both reference drivers. */
        private const val MAX_RESPONSE_READS = 64

        /** Total wall-clock budget for the whole calibration fetch. Head tracking waits on this. */
        private const val CALIBRATION_BUDGET_MS = 2000L

        /** The real blob is a few kB of JSON; anything past this is a misread length word. */
        private const val MAX_CALIBRATION_BYTES = 256L * 1024L
    }
}
