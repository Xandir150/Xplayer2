package com.teleteh.xplayer2.data.glasses

/**
 * Wire format of the XREAL Air-family IMU, decoded from bytes to physical units.
 *
 * Pure Kotlin on purpose — no Android imports, no logging — so the whole format is covered by JVM
 * unit tests ([com.teleteh.xplayer2.data.glasses.XrealImuPacketTest]) instead of only being
 * exercised on a headset. [XrealImuReader] does the USB plumbing and calls in here.
 *
 * Format knowledge is from badicsalex/ar-drivers-rs and TheJackiMonster/nrealAirLinuxDriver, both
 * MIT — see `NOTICE.md` in this directory for exactly which fact came from where.
 *
 * ## Sensor report (64 bytes, interrupt IN)
 *
 * Gyro, accelerometer and magnetometer all arrive together in one report, so there is a single
 * packet rate rather than the usual per-sensor rates: `nrealAirLinuxDriver` assumes **1000 Hz** for
 * its bias filter. Nothing here depends on that — every consumer works off the report's own
 * timestamp — but it's the number to expect when reasoning about CPU cost.
 *
 * ```
 * off  size  field                    notes
 *   0    2   signature                always 0x01 0x02 for a sensor report
 *                                     (0xAA 0x53 is the device's "IMU init" notification)
 *   2    2   temperature              i16 LE, ICM-42688-P: degC = raw / 132.48 + 25.0
 *   4    8   timestamp                u64 LE, device nanoseconds
 *  12    2   gyro multiplier          u16 LE  \
 *  14    4   gyro divisor             u32 LE   > deg/s = raw * mul / div
 *  18    3   gyro x                   i24 LE  /
 *  21    3   gyro y
 *  24    3   gyro z
 *  27    2   accel multiplier         u16 LE  \
 *  29    4   accel divisor            u32 LE   > g       = raw * mul / div
 *  33    3   accel x                  i24 LE  /
 *  36    3   accel y
 *  39    3   accel z
 *  42    2   mag multiplier           u16 *BE*   \
 *  44    4   mag divisor              u32 *BE*    > uT-ish = raw * mul / div
 *  48    2   mag x                    i16 LE, high byte XOR 0x80
 *  50    2   mag y                    /
 *  52    2   mag z
 *  54    4   checksum                 u32 (not verified here)
 *  58    6   padding
 * ```
 *
 * The magnetometer block really is that inconsistent — big-endian scale words and a high-byte flip
 * on each axis (`pack16bit_signed_bizarre` upstream). `ar-drivers-rs` skips the block entirely with
 * a "non-trivially rotated" TODO, which is why the mag output here is treated as advisory: see
 * [XrealImuSample.magValid] and the magnetometer discussion in [HeadOrientationTracker].
 *
 * ## Axes
 *
 * The raw triples are used as-is, in a right-handed body frame `(b1, b2, b3)` where `b1` is the
 * pitch axis, `b2` the roll axis and `b3` the yaw axis — which is the convention `HeadOrientationTracker`
 * has always exposed. Cross-checking with `ar-drivers-rs`, whose world frame is RUB (Right, Up,
 * Back): it maps both gyro and accel as `(-x, +z, +y)`, i.e. `b1 = -Right`, `b2 = Back`, `b3 = Up`.
 * The practical consequence — the one that makes gravity correction possible at all — is that a
 * level, motionless headset reads `accel ≈ (0, 0, +1) g`.
 *
 * ## Command framing (0xAA), same interface
 *
 * ```
 * off  size  field
 *   0    1   head       0xAA
 *   1    4   checksum   u32 LE, CRC-32 (zlib) over bytes [5, 5 + length)
 *   5    2   length     u16 LE, = 3 + dataLen  (covers length + msgId + data)
 *   7    1   msgId      see MSG_* below
 *   8    n   data
 * ```
 */
object XrealImuPacket {

    /** Sensor reports are always this size, on every model. */
    const val REPORT_SIZE = 64

    /** First two bytes of a sensor report. */
    const val SIGNATURE_0 = 0x01.toByte()
    const val SIGNATURE_1 = 0x02.toByte()

    /** Signature the glasses send once when the IMU comes up; not a sample. */
    const val INIT_SIGNATURE_0 = 0xAA.toByte()
    const val INIT_SIGNATURE_1 = 0x53.toByte()

    // Sensor report offsets.
    private const val OFF_TEMPERATURE = 2
    private const val OFF_TIMESTAMP = 4
    private const val OFF_GYRO_MUL = 12
    private const val OFF_GYRO_DIV = 14
    private const val OFF_GYRO_X = 18
    private const val OFF_ACCEL_MUL = 27
    private const val OFF_ACCEL_DIV = 29
    private const val OFF_ACCEL_X = 33
    private const val OFF_MAG_MUL = 42
    private const val OFF_MAG_DIV = 44
    private const val OFF_MAG_X = 48

    /** ICM-42688-P temperature sensitivity (LSB/degC) and its 25 degC offset. */
    private const val TEMP_SENSITIVITY = 132.48f
    private const val TEMP_OFFSET_C = 25.0f

    // --- 0xAA command framing ---

    const val COMMAND_HEAD = 0xAA.toByte()
    const val COMMAND_HEADER_SIZE = 8

    /** Request the length (u32 LE) of the factory calibration blob. */
    const val MSG_GET_CAL_DATA_LENGTH = 0x14
    /** Request the next chunk of the factory calibration blob. */
    const val MSG_CAL_DATA_GET_NEXT_SEGMENT = 0x15
    /** Enable (0x01) / disable (0x00) the sensor report stream. */
    const val MSG_START_IMU_DATA = 0x19
    /** Device static ID (u32 LE); unused, kept because it identifies the framing. */
    const val MSG_GET_STATIC_ID = 0x1A

    /**
     * Decode a sensor report into [out]. Returns false — leaving [out] untouched — when [buffer]
     * is too short or is not a sensor report (a command response or the init notification).
     *
     * Some Android HID stacks prepend a one-byte report ID; that is detected and skipped, matching
     * what the reader has always done.
     *
     * [hostNanos] is stamped straight through to [XrealImuSample.hostNanos]; the device's own
     * timestamp lands in [XrealImuSample.deviceNanos].
     */
    fun parseInto(buffer: ByteArray, size: Int, hostNanos: Long, out: XrealImuSample): Boolean {
        if (size < REPORT_SIZE) return false
        val base = if (buffer[0] != SIGNATURE_0 && buffer[1] == SIGNATURE_0) 1 else 0
        if (size < base + REPORT_SIZE) return false
        if (buffer[base] != SIGNATURE_0 || buffer[base + 1] != SIGNATURE_1) return false

        val gyroMul = u16le(buffer, base + OFF_GYRO_MUL)
        val gyroDiv = u32le(buffer, base + OFF_GYRO_DIV)
        out.gxDegSec = scale(s24le(buffer, base + OFF_GYRO_X), gyroMul, gyroDiv)
        out.gyDegSec = scale(s24le(buffer, base + OFF_GYRO_X + 3), gyroMul, gyroDiv)
        out.gzDegSec = scale(s24le(buffer, base + OFF_GYRO_X + 6), gyroMul, gyroDiv)

        val accelMul = u16le(buffer, base + OFF_ACCEL_MUL)
        val accelDiv = u32le(buffer, base + OFF_ACCEL_DIV)
        out.axG = scale(s24le(buffer, base + OFF_ACCEL_X), accelMul, accelDiv)
        out.ayG = scale(s24le(buffer, base + OFF_ACCEL_X + 3), accelMul, accelDiv)
        out.azG = scale(s24le(buffer, base + OFF_ACCEL_X + 6), accelMul, accelDiv)

        // Magnetometer: big-endian scale words, and each axis' high byte is XOR-ed with 0x80.
        val magMul = u16be(buffer, base + OFF_MAG_MUL)
        val magDiv = u32be(buffer, base + OFF_MAG_DIV)
        out.mx = scale(s16leFlipped(buffer, base + OFF_MAG_X), magMul, magDiv)
        out.my = scale(s16leFlipped(buffer, base + OFF_MAG_X + 2), magMul, magDiv)
        out.mz = scale(s16leFlipped(buffer, base + OFF_MAG_X + 4), magMul, magDiv)
        // A zero divisor (or an all-zero reading) means "no usable magnetometer on this unit".
        out.magValid = magDiv != 0L && (out.mx != 0f || out.my != 0f || out.mz != 0f)

        out.tempRaw = s16le(buffer, base + OFF_TEMPERATURE).toFloat()
        out.temperatureC = out.tempRaw / TEMP_SENSITIVITY + TEMP_OFFSET_C
        out.deviceNanos = u64le(buffer, base + OFF_TIMESTAMP)
        out.hostNanos = hostNanos
        out.calibrated = false
        return true
    }

    /**
     * Build a [size]-byte command frame. [size] is the interface's max payload (64 on Air / Air 2 /
     * Air 2 Pro, 512 on Air 2 Ultra) — the firmware wants a full-length report either way.
     */
    fun buildCommand(msgId: Int, data: ByteArray = ByteArray(0), size: Int = REPORT_SIZE): ByteArray {
        val packet = ByteArray(size)
        val length = 3 + data.size
        packet[0] = COMMAND_HEAD
        packet[5] = (length and 0xFF).toByte()
        packet[6] = ((length shr 8) and 0xFF).toByte()
        packet[7] = (msgId and 0xFF).toByte()
        data.copyInto(packet, COMMAND_HEADER_SIZE)
        val crc = GlassesProtocol.calculateCrc32(packet.copyOfRange(5, 5 + length))
        packet[1] = (crc and 0xFF).toByte()
        packet[2] = ((crc shr 8) and 0xFF).toByte()
        packet[3] = ((crc shr 16) and 0xFF).toByte()
        packet[4] = ((crc shr 24) and 0xFF).toByte()
        return packet
    }

    /**
     * Extract the payload of a command response, or null if [buffer] is not a `0xAA` frame for
     * [expectedMsgId]. Sensor reports and responses share one endpoint, so callers loop until this
     * returns non-null.
     */
    fun parseResponse(buffer: ByteArray, size: Int, expectedMsgId: Int): ByteArray? {
        if (size < COMMAND_HEADER_SIZE) return null
        val base = if (buffer[0] != COMMAND_HEAD && buffer[1] == COMMAND_HEAD) 1 else 0
        if (size < base + COMMAND_HEADER_SIZE) return null
        if (buffer[base] != COMMAND_HEAD) return null
        if ((buffer[base + 7].toInt() and 0xFF) != (expectedMsgId and 0xFF)) return null
        val length = u16le(buffer, base + 5)
        val dataLen = length - 3
        if (dataLen < 0) return null
        val end = base + COMMAND_HEADER_SIZE + dataLen
        if (end > size) return null
        return buffer.copyOfRange(base + COMMAND_HEADER_SIZE, end)
    }

    // --- byte helpers (bounds-checked; a short/garbled report yields zeroes, never a crash) ---

    private fun u16le(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= b.size) return 0
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u16be(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= b.size) return 0
        return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
    }

    private fun s16le(b: ByteArray, off: Int): Int {
        val u = u16le(b, off)
        return if ((u and 0x8000) != 0) u or -0x10000 else u
    }

    /** Signed 16-bit LE with the upstream `^ 0x80` on the high byte (magnetometer only). */
    private fun s16leFlipped(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 1 >= b.size) return 0
        val u = (b[off].toInt() and 0xFF) or (((b[off + 1].toInt() and 0xFF) xor 0x80) shl 8)
        return if ((u and 0x8000) != 0) u or -0x10000 else u
    }

    private fun u32le(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 3 >= b.size) return 0
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun u32be(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 3 >= b.size) return 0
        return ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
    }

    private fun u64le(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 7 >= b.size) return 0
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** 24-bit LE signed. */
    private fun s24le(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 >= b.size) return 0
        val u = (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16)
        return if ((u and 0x800000) != 0) u or 0xFF000000.toInt() else u
    }

    private fun scale(v: Int, mul: Int, div: Long): Float =
        if (div == 0L) 0f else v.toFloat() * mul.toFloat() / div.toFloat()
}

/**
 * One decoded IMU report. Mutable and **reused** by [XrealImuReader] — the reports arrive at
 * roughly 1 kHz, so allocating per sample would be pure garbage. Treat an instance handed to a
 * callback as valid only for the duration of that call; copy anything you need to keep.
 */
class XrealImuSample {
    /** Angular rate, deg/s, in the pitch / roll / yaw body axes (see [XrealImuPacket]). */
    @JvmField var gxDegSec: Float = 0f
    @JvmField var gyDegSec: Float = 0f
    @JvmField var gzDegSec: Float = 0f

    /** Specific force in g. Level and motionless reads `(0, 0, +1)`. */
    @JvmField var axG: Float = 0f
    @JvmField var ayG: Float = 0f
    @JvmField var azG: Float = 0f

    /** Magnetic field, scaled but in device units and an unknown rotation. See [magValid]. */
    @JvmField var mx: Float = 0f
    @JvmField var my: Float = 0f
    @JvmField var mz: Float = 0f

    /** False when the report carries no usable magnetometer data (zero divisor or all-zero axes). */
    @JvmField var magValid: Boolean = false

    /** Raw temperature word, as the debug HUD has always shown it. */
    @JvmField var tempRaw: Float = 0f
    /** Same reading in degrees Celsius. */
    @JvmField var temperatureC: Float = 0f

    /** Device clock, nanoseconds. Monotonic and jitter-free, unlike [hostNanos]. */
    @JvmField var deviceNanos: Long = 0L
    /** `System.nanoTime()` at the moment the report was pulled off USB. */
    @JvmField var hostNanos: Long = 0L

    /** True once factory calibration has been applied to the gyro/accel/mag fields. */
    @JvmField var calibrated: Boolean = false
}
