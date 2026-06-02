package com.teleteh.xplayer2.data.glasses

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the IMU stream from XREAL Air-series glasses over USB HID. The IMU packet format
 * was reverse-engineered by the nrealAirLinuxDriver / android-nreal projects and is also used
 * in xspace; this class is a focused, no-frills port that only does what XPlayer2's parallax
 * feature needs:
 *
 *   - find the interrupt-IN endpoint on a HID interface whose address is 0x84
 *   - send the "start IMU stream" MCU command (0xAA … cmd=0x19 data=0x01)
 *   - run a reader thread that pulls 64-byte packets and decodes gyro X/Y/Z to deg/sec
 *   - call back into [Listener] on the reader thread
 *
 * No accelerometer / quaternion / fusion is implemented here — [HeadOrientationTracker] does the
 * integration over deltas. Stop the reader via [stop] before the underlying device or
 * connection is closed; the thread joins on stop.
 */
class XrealImuReader(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
) {

    fun interface Listener {
        /**
         * Called on the reader thread with gyro angular velocity in degrees per second
         * (x = pitch rate, y = yaw rate, z = roll rate from XREAL's frame) and the
         * Android-side time-of-receipt nanos.
         */
        fun onSample(gxDegSec: Float, gyDegSec: Float, gzDegSec: Float, tNanos: Long)
    }

    private val running = AtomicBoolean(false)
    private var readerThread: Thread? = null

    /** Returns true if the reader thread is active. */
    fun isRunning(): Boolean = running.get()

    /**
     * Locate the IMU IN endpoint, send the start-stream command, and spawn the reader thread.
     * Returns false if no IMU endpoint was found or the device rejected the start command.
     */
    fun start(listener: Listener): Boolean {
        if (running.get()) return true
        val (intf, endpoint) = findImuEndpoint() ?: run {
            Log.w(TAG, "No IMU IN endpoint (HID + addr 0x84) on device ${device.deviceName}")
            return false
        }
        if (!sendImuStreamControl(enable = true)) {
            Log.w(TAG, "IMU start command rejected by glasses; reader not started")
            return false
        }
        Log.i(TAG, "IMU stream starting on interface ${intf.id} endpoint 0x${endpoint.address.toString(16)}")
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
        val buffer = ByteArray(64)
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
                parseSampleInto(buffer, read, listener)
            } catch (t: Throwable) {
                Log.w(TAG, "IMU read loop error: ${t.message}")
                break
            }
        }
    }

    private fun parseSampleInto(buffer: ByteArray, size: Int, listener: Listener) {
        // Packet shape (from nrealAirLinuxDriver / android-nreal):
        //   signature[2] = 0x01 0x02, temperature[2], timestamp[8],
        //   ang_m[2], ang_d[4], gx/gy/gz [3 × s24],
        //   acc_m[2], acc_d[4], ax/ay/az [3 × s24]
        // Some Android stacks prepend a 1-byte HID Report ID — detect and skip.
        if (size < 64) return
        val hasReportId = buffer[0] != 0x01.toByte() && buffer[1] == 0x01.toByte()
        val base = if (hasReportId) 1 else 0
        if (size < base + 64) return
        if (buffer[base] != 0x01.toByte() || buffer[base + 1] != 0x02.toByte()) return

        val angM = le16(buffer, base + 12)
        val angD = le32(buffer, base + 14)
        val gx = s24(buffer, base + 18)
        val gy = s24(buffer, base + 21)
        val gz = s24(buffer, base + 24)

        val gxDegSec = scale(gx, angM, angD)
        val gyDegSec = scale(gy, angM, angD)
        val gzDegSec = scale(gz, angM, angD)

        listener.onSample(gxDegSec, gyDegSec, gzDegSec, System.nanoTime())
    }

    private fun findImuEndpoint(): Pair<android.hardware.usb.UsbInterface, UsbEndpoint>? {
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

    /**
     * Build and dispatch the small "set IMU stream state" packet. Modelled after the xspace
     * command: 64-byte body with header 0xAA, length 0x04 at offset 5, command 0x19 at
     * offset 7, payload byte at offset 8, and a CRC32 of bytes [5..8] in [1..4].
     */
    private fun sendImuStreamControl(enable: Boolean): Boolean {
        val packet = ByteArray(64)
        packet[0] = 0xAA.toByte()
        packet[5] = 0x04
        packet[6] = 0x00
        packet[7] = 0x19
        packet[8] = if (enable) 0x01 else 0x00
        val crc = GlassesProtocol.calculateCrc32(packet.copyOfRange(5, 9))
        packet[1] = (crc and 0xFF).toByte()
        packet[2] = ((crc shr 8) and 0xFF).toByte()
        packet[3] = ((crc shr 16) and 0xFF).toByte()
        packet[4] = ((crc shr 24) and 0xFF).toByte()

        // The XREAL firmware accepts the IMU control packet either over a HID Set-Report
        // control transfer (usually interface 3, depending on model) or as a bulk write to
        // any interrupt-OUT endpoint on a HID interface. Try both paths until one succeeds.
        var ok = false
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != UsbConstants.USB_CLASS_HID) continue
            val setReportType = (0x02 shl 8) or 0  // Output report, ID 0
            val sent = try {
                connection.controlTransfer(
                    /* requestType */ 0x21, // host->device | class | interface
                    /* request */ 0x09,     // SET_REPORT
                    /* value */ setReportType,
                    /* index */ i,
                    packet,
                    packet.size,
                    1000
                )
            } catch (_: Throwable) { -1 }
            if (sent > 0) ok = true
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

    // --- packet-byte helpers ---

    private fun le16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 3 >= b.size) return 0
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    /** 24-bit little-endian signed integer at the given offset. */
    private fun s24(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 >= b.size) return 0
        val u = (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16)
        return if ((u and 0x800000) != 0) u or 0xFF000000.toInt() else u
    }

    private fun scale(v: Int, m: Int, d: Long): Float {
        if (d == 0L) return 0f
        return v.toFloat() * m.toFloat() / d.toFloat()
    }

    companion object {
        private const val TAG = "XrealImuReader"
    }
}
