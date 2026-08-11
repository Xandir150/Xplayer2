package com.teleteh.xplayer2.data.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-level tests for the XREAL IMU wire format.
 *
 * Every fixture is hand-built from the layout table in [XrealImuPacket], which is itself taken from
 * `nrealAirLinuxDriver`'s `device_imu_packet_t` and cross-checked against `ar-drivers-rs`'s
 * `ImuDevice::parse_report` (see `NOTICE.md`). The point of building the bytes by hand rather than
 * replaying a capture is that a wrong offset fails here with the field name attached, instead of
 * showing up as a slowly tilting horizon on someone's face.
 *
 * The multiplier/divisor values below are chosen for readable arithmetic, not copied from a real
 * unit — the firmware sends them in-band precisely because they vary. What is verified is the
 * layout and the scaling *rule*.
 */
class XrealImuPacketTest {

    // --- sensor reports ---

    @Test
    fun `decodes gyro accel and mag from a canonical report`() {
        val bytes = sensorReport(
            gx = 25_000, gy = -12_500, gz = 90_000, gyroMul = 1, gyroDiv = 1_000,
            ax = 0, ay = 0, az = 1_000_000, accelMul = 1, accelDiv = 1_000_000,
            mx = 1_200, my = -800, mz = 300, magMul = 1, magDiv = 1_000,
            tempRaw = 1_325, deviceNanos = 1_234_567_890_123L,
        )
        val out = XrealImuSample()
        assertTrue(XrealImuPacket.parseInto(bytes, bytes.size, hostNanos = 42L, out = out))

        assertEquals(25.0f, out.gxDegSec, 1e-4f)
        assertEquals(-12.5f, out.gyDegSec, 1e-4f)
        assertEquals(90.0f, out.gzDegSec, 1e-4f)

        // Level and motionless: gravity shows up entirely on the yaw axis, at +1 g.
        assertEquals(0f, out.axG, 1e-6f)
        assertEquals(0f, out.ayG, 1e-6f)
        assertEquals(1.0f, out.azG, 1e-6f)

        assertEquals(1.2f, out.mx, 1e-4f)
        assertEquals(-0.8f, out.my, 1e-4f)
        assertEquals(0.3f, out.mz, 1e-4f)
        assertTrue(out.magValid)

        assertEquals(1_234_567_890_123L, out.deviceNanos)
        assertEquals(42L, out.hostNanos)
        assertFalse("calibration is applied by the reader, not the parser", out.calibrated)
    }

    @Test
    fun `temperature uses the ICM-42688-P scale`() {
        val out = XrealImuSample()
        // 25 degC offset, 132.48 LSB/degC.
        val cold = sensorReport(tempRaw = 0)
        XrealImuPacket.parseInto(cold, cold.size, 0L, out)
        assertEquals(0f, out.tempRaw, 1e-6f)
        assertEquals(25.0f, out.temperatureC, 1e-4f)

        val warm = sensorReport(tempRaw = 1_325)
        XrealImuPacket.parseInto(warm, warm.size, 0L, out)
        assertEquals(1_325f, out.tempRaw, 1e-6f)
        assertEquals(35.0f, out.temperatureC, 0.01f)

        val negative = sensorReport(tempRaw = -1_325)
        XrealImuPacket.parseInto(negative, negative.size, 0L, out)
        assertEquals(15.0f, out.temperatureC, 0.01f)
    }

    @Test
    fun `sign-extends the 24-bit gyro and accel fields`() {
        val bytes = sensorReport(
            gx = -1, gy = -8_388_608, gz = 8_388_607, gyroMul = 1, gyroDiv = 1,
            ax = -500_000, ay = -1_000_000, az = -1, accelMul = 1, accelDiv = 1_000_000,
        )
        val out = XrealImuSample()
        assertTrue(XrealImuPacket.parseInto(bytes, bytes.size, 0L, out))
        assertEquals(-1f, out.gxDegSec, 1e-3f)
        assertEquals(-8_388_608f, out.gyDegSec, 1f)
        assertEquals(8_388_607f, out.gzDegSec, 1f)
        assertEquals(-0.5f, out.axG, 1e-6f)
        assertEquals(-1.0f, out.ayG, 1e-6f)
        assertEquals(-1e-6f, out.azG, 1e-9f)
    }

    /**
     * The magnetometer block is the odd one out: big-endian multiplier and divisor, and each 16-bit
     * axis has its high byte XOR-ed with 0x80 before being read as signed
     * (`pack16bit_signed_bizarre` in `nrealAirLinuxDriver`). Getting either wrong still produces
     * plausible-looking numbers, hence an explicit test.
     */
    @Test
    fun `mag block is big-endian scaled with the high-byte flip`() {
        val bytes = sensorReport(mx = 0, my = 32_767, mz = -32_768, magMul = 3, magDiv = 2)
        val out = XrealImuSample()
        assertTrue(XrealImuPacket.parseInto(bytes, bytes.size, 0L, out))
        assertEquals(0f, out.mx, 1e-6f)
        assertEquals(32_767f * 3f / 2f, out.my, 1f)
        assertEquals(-32_768f * 3f / 2f, out.mz, 1f)

        // Scale words really are byte-swapped relative to gyro/accel: writing them little-endian
        // must NOT decode to the same value.
        val littleEndianMag = sensorReport(mx = 100, magMul = 1, magDiv = 1_000).also {
            it[42] = 0x01; it[43] = 0x00               // multiplier 1 as LE
            it[44] = 0xE8.toByte(); it[45] = 0x03      // divisor 1000 as LE
            it[46] = 0x00; it[47] = 0x00
        }
        XrealImuPacket.parseInto(littleEndianMag, littleEndianMag.size, 0L, out)
        assertTrue("LE-written mag scale must not decode as 100/1000", out.mx != 0.1f)
    }

    @Test
    fun `mag is flagged invalid when the divisor is zero or every axis reads zero`() {
        val out = XrealImuSample()
        val noDivisor = sensorReport(mx = 10, my = 20, mz = 30, magDiv = 0)
        assertTrue(XrealImuPacket.parseInto(noDivisor, noDivisor.size, 0L, out))
        assertFalse(out.magValid)
        assertEquals(0f, out.mx, 1e-9f)

        val allZero = sensorReport(mx = 0, my = 0, mz = 0, magDiv = 1_000)
        assertTrue(XrealImuPacket.parseInto(allZero, allZero.size, 0L, out))
        assertFalse(out.magValid)
    }

    @Test
    fun `a zero divisor yields zero rather than a NaN`() {
        val bytes = sensorReport(gx = 1_000, gyroDiv = 0, ax = 1_000, accelDiv = 0)
        val out = XrealImuSample()
        assertTrue(XrealImuPacket.parseInto(bytes, bytes.size, 0L, out))
        assertEquals(0f, out.gxDegSec, 0f)
        assertEquals(0f, out.axG, 0f)
        assertFalse(out.gxDegSec.isNaN())
    }

    @Test
    fun `skips a leading HID report id`() {
        val plain = sensorReport(gx = 25_000, gyroMul = 1, gyroDiv = 1_000)
        val prefixed = sensorReport(gx = 25_000, gyroMul = 1, gyroDiv = 1_000, reportIdPrefix = true)
        assertEquals(65, prefixed.size)

        val a = XrealImuSample()
        val b = XrealImuSample()
        assertTrue(XrealImuPacket.parseInto(plain, plain.size, 0L, a))
        assertTrue(XrealImuPacket.parseInto(prefixed, prefixed.size, 0L, b))
        assertEquals(a.gxDegSec, b.gxDegSec, 0f)
    }

    @Test
    fun `rejects anything that is not a sensor report`() {
        val out = XrealImuSample()

        val short = sensorReport().copyOfRange(0, 63)
        assertFalse(XrealImuPacket.parseInto(short, short.size, 0L, out))

        // The device's "IMU is up" notification shares the endpoint but is not a sample.
        val init = sensorReport().also { it[0] = 0xAA.toByte(); it[1] = 0x53 }
        assertFalse(XrealImuPacket.parseInto(init, init.size, 0L, out))

        // A 0xAA command reply, likewise.
        val reply = XrealImuPacket.buildCommand(XrealImuPacket.MSG_GET_CAL_DATA_LENGTH, byteArrayOf(1, 2, 3, 4))
        assertFalse(XrealImuPacket.parseInto(reply, reply.size, 0L, out))

        val wrongSecondByte = sensorReport().also { it[1] = 0x03 }
        assertFalse(XrealImuPacket.parseInto(wrongSecondByte, wrongSecondByte.size, 0L, out))
    }

    @Test
    fun `a rejected report leaves the previous sample untouched`() {
        val out = XrealImuSample()
        val good = sensorReport(gx = 25_000, gyroMul = 1, gyroDiv = 1_000)
        XrealImuPacket.parseInto(good, good.size, 7L, out)

        val bad = sensorReport().also { it[1] = 0x03 }
        assertFalse(XrealImuPacket.parseInto(bad, bad.size, 99L, out))
        assertEquals(25.0f, out.gxDegSec, 1e-4f)
        assertEquals(7L, out.hostNanos)
    }

    // --- 0xAA command framing ---

    /**
     * The stream-control frame is the one piece of this file that was already shipping, built by
     * hand inside the reader. Byte-for-byte equality with that construction is asserted so the
     * refactor cannot have changed what goes out on the wire.
     */
    @Test
    fun `stream control frame is byte-identical to the shipped hand-built one`() {
        val legacy = ByteArray(64).also { p ->
            p[0] = 0xAA.toByte()
            p[5] = 0x04
            p[6] = 0x00
            p[7] = 0x19
            p[8] = 0x01
            val crc = GlassesProtocol.calculateCrc32(p.copyOfRange(5, 9))
            p[1] = (crc and 0xFF).toByte()
            p[2] = ((crc shr 8) and 0xFF).toByte()
            p[3] = ((crc shr 16) and 0xFF).toByte()
            p[4] = ((crc shr 24) and 0xFF).toByte()
        }
        val built = XrealImuPacket.buildCommand(XrealImuPacket.MSG_START_IMU_DATA, byteArrayOf(0x01))
        assertArrayEquals(legacy, built)
    }

    @Test
    fun `command framing places head length msgid and crc where the reference driver expects`() {
        val built = XrealImuPacket.buildCommand(XrealImuPacket.MSG_GET_CAL_DATA_LENGTH)
        assertEquals(64, built.size)
        assertEquals(0xAA.toByte(), built[0])
        // length = 3 + dataLen, u16 LE at offset 5.
        assertEquals(3, built[5].toInt())
        assertEquals(0, built[6].toInt())
        assertEquals(0x14, built[7].toInt())
        // CRC covers [5, 5 + length) — length itself, msgid and data.
        val crc = GlassesProtocol.calculateCrc32(built.copyOfRange(5, 8))
        assertEquals((crc and 0xFF).toByte(), built[1])
        assertEquals(((crc shr 24) and 0xFF).toByte(), built[4])
    }

    @Test
    fun `command frame honours the Air 2 Ultra 512-byte payload size`() {
        val built = XrealImuPacket.buildCommand(XrealImuPacket.MSG_START_IMU_DATA, byteArrayOf(0x01), size = 512)
        assertEquals(512, built.size)
        assertEquals(0xAA.toByte(), built[0])
        assertEquals(0x19, built[7].toInt())
        assertEquals(0x01, built[8].toInt())
    }

    @Test
    fun `parses a command response payload`() {
        val frame = XrealImuPacket.buildCommand(XrealImuPacket.MSG_GET_CAL_DATA_LENGTH, byteArrayOf(0x40, 0x0D, 0, 0))
        val payload = XrealImuPacket.parseResponse(frame, frame.size, XrealImuPacket.MSG_GET_CAL_DATA_LENGTH)
        assertArrayEquals(byteArrayOf(0x40, 0x0D, 0, 0), payload)
    }

    @Test
    fun `ignores responses for another message and malformed frames`() {
        val frame = XrealImuPacket.buildCommand(XrealImuPacket.MSG_GET_CAL_DATA_LENGTH, byteArrayOf(1, 2, 3, 4))
        assertNull(XrealImuPacket.parseResponse(frame, frame.size, XrealImuPacket.MSG_CAL_DATA_GET_NEXT_SEGMENT))

        // Sensor reports arrive on the same endpoint and must not be mistaken for replies.
        val sensor = sensorReport()
        assertNull(XrealImuPacket.parseResponse(sensor, sensor.size, XrealImuPacket.MSG_START_IMU_DATA))

        assertNull(XrealImuPacket.parseResponse(frame, 4, XrealImuPacket.MSG_GET_CAL_DATA_LENGTH))

        // Length word claiming more data than the frame holds.
        val overlong = frame.copyOf().also { it[5] = 0xFF.toByte(); it[6] = 0x00 }
        assertNull(XrealImuPacket.parseResponse(overlong, overlong.size, XrealImuPacket.MSG_GET_CAL_DATA_LENGTH))
    }

    @Test
    fun `parses a response behind a HID report id`() {
        val frame = XrealImuPacket.buildCommand(XrealImuPacket.MSG_CAL_DATA_GET_NEXT_SEGMENT, byteArrayOf(0x7B, 0x7D))
        val prefixed = ByteArray(frame.size + 1).also { frame.copyInto(it, 1) }
        val payload = XrealImuPacket.parseResponse(prefixed, prefixed.size, XrealImuPacket.MSG_CAL_DATA_GET_NEXT_SEGMENT)
        assertArrayEquals(byteArrayOf(0x7B, 0x7D), payload)
    }

    // --- fixture builders ---

    private fun sensorReport(
        gx: Int = 0, gy: Int = 0, gz: Int = 0, gyroMul: Int = 1, gyroDiv: Long = 1_000,
        ax: Int = 0, ay: Int = 0, az: Int = 0, accelMul: Int = 1, accelDiv: Long = 1_000_000,
        mx: Int = 0, my: Int = 0, mz: Int = 0, magMul: Int = 1, magDiv: Long = 1_000,
        tempRaw: Int = 0, deviceNanos: Long = 0L,
        reportIdPrefix: Boolean = false,
    ): ByteArray {
        val b = ByteArray(64)
        b[0] = 0x01; b[1] = 0x02
        putLe(b, 2, tempRaw.toLong(), 2)
        putLe(b, 4, deviceNanos, 8)
        putLe(b, 12, gyroMul.toLong(), 2)
        putLe(b, 14, gyroDiv, 4)
        putLe(b, 18, gx.toLong(), 3); putLe(b, 21, gy.toLong(), 3); putLe(b, 24, gz.toLong(), 3)
        putLe(b, 27, accelMul.toLong(), 2)
        putLe(b, 29, accelDiv, 4)
        putLe(b, 33, ax.toLong(), 3); putLe(b, 36, ay.toLong(), 3); putLe(b, 39, az.toLong(), 3)
        putBe(b, 42, magMul.toLong(), 2)
        putBe(b, 44, magDiv, 4)
        putMag(b, 48, mx); putMag(b, 50, my); putMag(b, 52, mz)
        return if (reportIdPrefix) ByteArray(65).also { b.copyInto(it, 1) } else b
    }

    private fun putLe(b: ByteArray, off: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) b[off + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun putBe(b: ByteArray, off: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) b[off + i] = ((value shr (8 * (bytes - 1 - i))) and 0xFF).toByte()
    }

    /** LE 16-bit with the high byte XOR-ed with 0x80, matching the parser's inverse. */
    private fun putMag(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = (((value shr 8) and 0xFF) xor 0x80).toByte()
    }
}
