package com.teleteh.xplayer2.data.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the factory calibration blob the glasses hand over at connect time.
 *
 * Three things here are easy to get wrong and impossible to notice by eye on a headset — the unit
 * conversions (rad/s → deg/s, m/s² → g), the `(x, z, y)` axis permutation, and the fact that the
 * bias is *added* — so each gets an assertion of its own. The rest is about failing safe: a device
 * that answers with nothing, with truncated JSON or with implausible numbers must end up on
 * identity calibration, never on a filter quietly fed garbage.
 */
class XrealImuCalibrationTest {

    /** A blob shaped like the real thing, trimmed to the keys that matter. */
    private fun blob(
        gyroBias: String = "[0.01, 0.02, 0.03]",
        accelBias: String = "[0.0981, 0.1962, 0.2943]",
        magBias: String = "[1.0, 2.0, 3.0]",
        scaleGyro: String = "[1.0, 1.0, 1.0]",
        scaleAccel: String = "[1.0, 1.0, 1.0]",
        scaleMag: String = "[1.0, 1.0, 1.0]",
    ) = """
        {
          "display": { "resolution": [1920, 1080] },
          "IMU": {
            "device_1": {
              "imu_noises": [0.1, 0.2, 0.3, 0.4],
              "gyro_bias": $gyroBias,
              "accel_bias": $accelBias,
              "mag_bias": $magBias,
              "scale_gyro": $scaleGyro,
              "scale_accel": $scaleAccel,
              "scale_mag": $scaleMag
            }
          }
        }
    """.trimIndent()

    @Test
    fun `converts units and permutes into sensor-axis order`() {
        val cal = XrealImuCalibration.parse(blob())!!
        assertTrue(cal.isFactory)

        // gyro_bias is rad/s in the blob and deg/s here, permuted (x, z, y).
        assertEquals(0.01f * 57.29578f, cal.gyroBiasDegSec[0], 1e-4f)   // JSON[0] → pitch axis
        assertEquals(0.03f * 57.29578f, cal.gyroBiasDegSec[1], 1e-4f)   // JSON[2] → roll axis
        assertEquals(0.02f * 57.29578f, cal.gyroBiasDegSec[2], 1e-4f)   // JSON[1] → yaw axis

        // accel_bias is m/s² in the blob and g here; 0.0981 m/s² ≈ 0.01 g. Same permutation.
        assertEquals(0.01f, cal.accelBiasG[0], 1e-4f)
        assertEquals(0.03f, cal.accelBiasG[1], 1e-4f)
        assertEquals(0.02f, cal.accelBiasG[2], 1e-4f)

        assertEquals(1.0f, cal.magBias[0], 1e-6f)
        assertEquals(3.0f, cal.magBias[1], 1e-6f)
        assertEquals(2.0f, cal.magBias[2], 1e-6f)
        assertTrue(cal.hasMagCalibration)
    }

    @Test
    fun `applies bias additively and scale multiplicatively`() {
        val cal = XrealImuCalibration.parse(
            blob(
                gyroBias = "[0.0174533, 0.0, 0.0]",   // 1 deg/s on the pitch axis
                accelBias = "[0.0, 0.0, 0.9806]",     // 0.1 g on the roll axis (JSON[2])
                scaleGyro = "[2.0, 1.0, 1.0]",
                scaleAccel = "[1.0, 1.0, 1.0]",
            ),
        )!!

        val sample = XrealImuSample().apply {
            gxDegSec = 10f; gyDegSec = 0f; gzDegSec = 0f
            axG = 0f; ayG = 0.5f; azG = 1f
            magValid = false
        }
        cal.applyTo(sample)

        // (10 + 1) * 2 — bias first, then scale, matching FusionCalibrationInertial upstream.
        assertEquals(22f, sample.gxDegSec, 1e-3f)
        assertEquals(0.6f, sample.ayG, 1e-3f)
        assertEquals(1f, sample.azG, 1e-3f)
        assertTrue(sample.calibrated)
    }

    @Test
    fun `leaves the magnetometer alone when the report had none`() {
        val cal = XrealImuCalibration.parse(blob())!!
        val sample = XrealImuSample().apply { mx = 5f; my = 5f; mz = 5f; magValid = false }
        cal.applyTo(sample)
        assertEquals(5f, sample.mx, 0f)

        sample.magValid = true
        cal.applyTo(sample)
        assertEquals(6f, sample.mx, 1e-4f)   // + magBias[0] = 1.0
    }

    @Test
    fun `identity calibration is a no-op and does not claim to be factory data`() {
        val sample = XrealImuSample().apply {
            gxDegSec = 3f; gyDegSec = -4f; gzDegSec = 5f
            axG = 0.1f; ayG = 0.2f; azG = 0.9f
            mx = 1f; my = 2f; mz = 3f; magValid = true
        }
        XrealImuCalibration.IDENTITY.applyTo(sample)
        assertEquals(3f, sample.gxDegSec, 0f)
        assertEquals(-4f, sample.gyDegSec, 0f)
        assertEquals(0.9f, sample.azG, 0f)
        assertEquals(2f, sample.my, 0f)
        assertFalse(sample.calibrated)
        assertFalse(XrealImuCalibration.IDENTITY.isFactory)
        assertFalse(XrealImuCalibration.IDENTITY.hasMagCalibration)
    }

    // --- fallback paths: every one of these must yield null so the reader picks IDENTITY ---

    @Test
    fun `rejects a blob that is not JSON at all`() {
        assertNull(XrealImuCalibration.parse("not json"))
        assertNull(XrealImuCalibration.parse("{ \"IMU\": "))     // truncated mid-transfer
        assertNull(XrealImuCalibration.parse("[1, 2, 3]"))       // JSON, wrong shape
    }

    @Test
    fun `rejects null empty and blank input`() {
        assertNull(XrealImuCalibration.parse(null))
        assertNull(XrealImuCalibration.parse(""))
        assertNull(XrealImuCalibration.parse("   \n  "))
    }

    @Test
    fun `rejects a blob with no IMU section`() {
        assertNull(XrealImuCalibration.parse("""{ "display": { "resolution": [1920, 1080] } }"""))
        assertNull(XrealImuCalibration.parse("""{ "IMU": { "device_2": { "gyro_bias": [0,0,0] } } }"""))
        assertNull(XrealImuCalibration.parse("""{ "IMU": { "device_1": {} } }"""))
    }

    @Test
    fun `rejects a short or non-numeric bias vector`() {
        assertNull(XrealImuCalibration.parse(blob(gyroBias = "[0.01, 0.02]")))
        assertNull(XrealImuCalibration.parse(blob(gyroBias = """["a", "b", "c"]""")))
    }

    /**
     * The sanity gate. A real zero-rate offset is a fraction of a degree per second; a value this
     * far out means the bytes were misread, and integrating it would be worse than no calibration.
     */
    @Test
    fun `rejects implausible bias and scale values`() {
        assertNull("1 rad/s ≈ 57 deg/s of 'bias'", XrealImuCalibration.parse(blob(gyroBias = "[1.0, 0.0, 0.0]")))
        assertNull(XrealImuCalibration.parse(blob(accelBias = "[100.0, 0.0, 0.0]")))
        assertNull("a zero scale would mute an axis", XrealImuCalibration.parse(blob(scaleGyro = "[0.0, 1.0, 1.0]")))
        assertNull(XrealImuCalibration.parse(blob(scaleAccel = "[1.0, 5.0, 1.0]")))
    }

    @Test
    fun `accepts a blob that only carries the gyro bias`() {
        val cal = XrealImuCalibration.parse("""{ "IMU": { "device_1": { "gyro_bias": [0.001, 0.002, 0.003] } } }""")
        assertNotNull(cal)
        assertEquals(0.001f * 57.29578f, cal!!.gyroBiasDegSec[0], 1e-4f)
        assertEquals(0f, cal.accelBiasG[0], 0f)
        assertEquals(1f, cal.gyroScale[0], 0f)      // missing scale ⇒ unity, not zero
        assertEquals(1f, cal.accelScale[2], 0f)
        assertFalse(cal.hasMagCalibration)
        assertTrue(cal.isFactory)
    }

    @Test
    fun `accepts a plausible all-zero calibration`() {
        val cal = XrealImuCalibration.parse(blob(gyroBias = "[0,0,0]", accelBias = "[0,0,0]", magBias = "[0,0,0]"))
        assertNotNull(cal)
        assertTrue(cal!!.isFactory)
        assertFalse(cal.hasMagCalibration)
    }
}
