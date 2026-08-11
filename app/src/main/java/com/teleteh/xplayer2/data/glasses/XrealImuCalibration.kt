package com.teleteh.xplayer2.data.glasses

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-unit factory calibration for the XREAL IMU, as stored on the glasses themselves.
 *
 * [XrealImuReader] pulls a UTF-8 JSON blob off the IMU HID interface at connect time (msg `0x14`
 * for the length, then `0x15` chunks) and hands the text here. The document has a lot in it —
 * display intrinsics, SLAM camera descriptors — but the only part that matters for head tracking
 * is:
 *
 * ```json
 * { "IMU": { "device_1": {
 *     "gyro_bias":   [ ... ],   // rad/s
 *     "accel_bias":  [ ... ],   // m/s²
 *     "mag_bias":    [ ... ],
 *     "scale_gyro":  [ ... ],   // dimensionless, ~1.0
 *     "scale_accel": [ ... ],
 *     "scale_mag":   [ ... ]
 * } } }
 * ```
 *
 * Two things about this are easy to get wrong, so they are handled here once and documented:
 *
 * 1. **The bias is added, not subtracted.** `ar-drivers-rs` adds it with an "the bias fields do not
 *    correspond to the raw fields, but for some reason this looks like the correct zero" shrug;
 *    `nrealAirLinuxDriver` subtracts it *after* flipping the axes, which works out to the same
 *    arithmetic. Both were cross-checked term by term before this was written — see `NOTICE.md`.
 * 2. **The vector is not in sensor-axis order.** Both upstreams consume it in their own world
 *    frame; translated back to raw sensor axes the mapping is `(x, z, y)` — JSON component 0 goes
 *    to the pitch axis, component 2 to the roll axis, component 1 to the yaw axis. The permutation
 *    is applied at parse time so the rest of the code never has to think about it.
 *
 * Units are converted at parse time too: gyro bias rad/s → deg/s, accel bias m/s² → g, matching
 * what [XrealImuPacket] produces.
 *
 * Pure Kotlin (`org.json` only, which the unit tests have a real implementation of) so the parse
 * and its fallbacks are covered by JVM tests.
 */
class XrealImuCalibration private constructor(
    /** Added to the gyro reading, deg/s, already permuted into sensor-axis order. */
    @JvmField val gyroBiasDegSec: FloatArray,
    /** Multiplies the gyro reading after the bias, sensor-axis order. */
    @JvmField val gyroScale: FloatArray,
    /** Added to the accelerometer reading, g, sensor-axis order. */
    @JvmField val accelBiasG: FloatArray,
    @JvmField val accelScale: FloatArray,
    /** Added to the magnetometer reading, device units, sensor-axis order. */
    @JvmField val magBias: FloatArray,
    @JvmField val magScale: FloatArray,
    /** False for [IDENTITY] — i.e. "the blob could not be read, we are guessing". */
    @JvmField val isFactory: Boolean,
) {

    /** True when the unit actually shipped magnetometer calibration (rare). */
    val hasMagCalibration: Boolean
        get() = isFactory && (magBias[0] != 0f || magBias[1] != 0f || magBias[2] != 0f)

    /** Apply gyro + accel + mag calibration to [sample] in place, and flag it as calibrated. */
    fun applyTo(sample: XrealImuSample) {
        sample.gxDegSec = (sample.gxDegSec + gyroBiasDegSec[0]) * gyroScale[0]
        sample.gyDegSec = (sample.gyDegSec + gyroBiasDegSec[1]) * gyroScale[1]
        sample.gzDegSec = (sample.gzDegSec + gyroBiasDegSec[2]) * gyroScale[2]
        sample.axG = (sample.axG + accelBiasG[0]) * accelScale[0]
        sample.ayG = (sample.ayG + accelBiasG[1]) * accelScale[1]
        sample.azG = (sample.azG + accelBiasG[2]) * accelScale[2]
        if (sample.magValid) {
            sample.mx = (sample.mx + magBias[0]) * magScale[0]
            sample.my = (sample.my + magBias[1]) * magScale[1]
            sample.mz = (sample.mz + magBias[2]) * magScale[2]
        }
        sample.calibrated = isFactory
    }

    override fun toString(): String =
        if (!isFactory) "XrealImuCalibration(identity)"
        else "XrealImuCalibration(gyroBias=%.4f,%.4f,%.4f deg/s accelBias=%.4f,%.4f,%.4f g mag=%b)"
            .format(
                gyroBiasDegSec[0], gyroBiasDegSec[1], gyroBiasDegSec[2],
                accelBiasG[0], accelBiasG[1], accelBiasG[2], hasMagCalibration,
            )

    companion object {
        /** rad → deg, for the gyro bias. */
        private const val RAD_TO_DEG = 57.295780f
        /** Standard gravity used by `nrealAirLinuxDriver` when normalising the accel bias. */
        private const val GRAVITY_G = 9.806f

        /** No-op calibration: what we use when the blob is missing, truncated or malformed. */
        @JvmField
        val IDENTITY = XrealImuCalibration(
            gyroBiasDegSec = floatArrayOf(0f, 0f, 0f),
            gyroScale = floatArrayOf(1f, 1f, 1f),
            accelBiasG = floatArrayOf(0f, 0f, 0f),
            accelScale = floatArrayOf(1f, 1f, 1f),
            magBias = floatArrayOf(0f, 0f, 0f),
            magScale = floatArrayOf(1f, 1f, 1f),
            isFactory = false,
        )

        /**
         * Parse the calibration blob. Returns null for anything unusable — not valid JSON, no
         * `IMU.device_1`, or a bias so large it can only be a misparse — so the caller can fall
         * back to [IDENTITY] rather than feed the filter nonsense.
         *
         * Missing individual fields are tolerated: a blob with `gyro_bias` but no `scale_gyro` is
         * perfectly usable, and yields unit scale.
         */
        fun parse(json: String?): XrealImuCalibration? {
            if (json.isNullOrBlank()) return null
            val device = try {
                JSONObject(json).optJSONObject("IMU")?.optJSONObject("device_1")
            } catch (_: Throwable) {
                null
            } ?: return null

            val gyroBias = vector(device, "gyro_bias") ?: return null
            val accelBias = vector(device, "accel_bias") ?: floatArrayOf(0f, 0f, 0f)
            val magBias = vector(device, "mag_bias") ?: floatArrayOf(0f, 0f, 0f)
            val gyroScale = vector(device, "scale_gyro") ?: floatArrayOf(1f, 1f, 1f)
            val accelScale = vector(device, "scale_accel") ?: floatArrayOf(1f, 1f, 1f)
            val magScale = vector(device, "scale_mag") ?: floatArrayOf(1f, 1f, 1f)

            val result = XrealImuCalibration(
                gyroBiasDegSec = permute(gyroBias) { it * RAD_TO_DEG },
                gyroScale = permute(gyroScale) { it },
                accelBiasG = permute(accelBias) { it / GRAVITY_G },
                accelScale = permute(accelScale) { it },
                magBias = permute(magBias) { it },
                magScale = permute(magScale) { it },
                isFactory = true,
            )
            return if (result.isPlausible()) result else null
        }

        /**
         * Sanity gate. A real zero-rate offset is a fraction of a deg/s and a real accel offset a
         * few hundredths of a g; anything far outside that means we decoded the wrong bytes, and
         * silently integrating it would be worse than having no calibration at all. Scale factors
         * must also stay near unity (a zero scale would mute an axis outright).
         */
        private fun XrealImuCalibration.isPlausible(): Boolean {
            for (i in 0..2) {
                if (!gyroBiasDegSec[i].isFinite() || kotlin.math.abs(gyroBiasDegSec[i]) > MAX_GYRO_BIAS_DEG_SEC) return false
                if (!accelBiasG[i].isFinite() || kotlin.math.abs(accelBiasG[i]) > MAX_ACCEL_BIAS_G) return false
                if (!gyroScale[i].isFinite() || gyroScale[i] < MIN_SCALE || gyroScale[i] > MAX_SCALE) return false
                if (!accelScale[i].isFinite() || accelScale[i] < MIN_SCALE || accelScale[i] > MAX_SCALE) return false
            }
            return true
        }

        private const val MAX_GYRO_BIAS_DEG_SEC = 30f
        private const val MAX_ACCEL_BIAS_G = 0.5f
        private const val MIN_SCALE = 0.5f
        private const val MAX_SCALE = 2.0f

        /** JSON `[a, b, c]` → sensor-axis order `(a, c, b)`, with [convert] applied to each. */
        private inline fun permute(v: FloatArray, convert: (Float) -> Float): FloatArray =
            floatArrayOf(convert(v[0]), convert(v[2]), convert(v[1]))

        /** Read a 3-element numeric array, or null if absent/short/non-numeric. */
        private fun vector(owner: JSONObject, key: String): FloatArray? {
            val array: JSONArray = owner.optJSONArray(key) ?: return null
            if (array.length() < 3) return null
            val out = FloatArray(3)
            for (i in 0..2) {
                val d = array.optDouble(i, Double.NaN)
                if (d.isNaN()) return null
                out[i] = d.toFloat()
            }
            return out
        }
    }
}
