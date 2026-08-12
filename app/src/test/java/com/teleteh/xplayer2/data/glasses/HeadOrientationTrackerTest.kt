package com.teleteh.xplayer2.data.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Behavioural tests for the gyro/accelerometer/magnetometer fusion.
 *
 * These are simulations, not captures: a synthetic sensor stream is generated for a *known* true
 * orientation and the filter is asked to recover it. That is the only way to assert the properties
 * that actually matter — "pitch stops drifting", "a knock doesn't tilt the horizon" — since a
 * recording can only ever show what one headset did on one day.
 *
 * Sample rates match the hardware: the XREAL Air family sends gyro, accelerometer and magnetometer
 * together in a single report at roughly 1 kHz (`nrealAirLinuxDriver` initialises its bias filter
 * at 1000 Hz). Tests that simulate minutes of drift run at 500 Hz to stay quick; nothing in the
 * filter depends on the rate, only on each sample's timestamp.
 */
class HeadOrientationTrackerTest {

    private companion object {
        const val HZ_1K = 1_000_000L        // 1 kHz, in nanoseconds per sample
        const val HZ_500 = 2_000_000L       // 500 Hz
        const val DEG = 0.017453292f
    }

    /**
     * Verbatim reimplementation of the gyro-only tracker this replaced, kept here as the oracle for
     * the compatibility test below. If the fusion ever changes what a pure-gyro caller sees, this
     * is what catches it.
     */
    private class LegacyTracker(private val biasTauSec: Float = 4.0f) {
        var yawDeg = 0f; var pitchDeg = 0f; var rollDeg = 0f
        private var biasX = 0f; private var biasY = 0f; private var biasZ = 0f
        private var lastNanos = 0L

        fun accumulate(gx: Float, gy: Float, gz: Float, tNanos: Long) {
            val prev = lastNanos
            lastNanos = tNanos
            if (prev == 0L) return
            val dt = (tNanos - prev) / 1_000_000_000f
            if (dt <= 0f || dt > 0.25f) return
            val step = (dt / biasTauSec).coerceIn(0f, 1f)
            biasX += (gx - biasX) * step
            biasY += (gy - biasY) * step
            biasZ += (gz - biasZ) * step
            pitchDeg = (pitchDeg + (gx - biasX) * dt).coerceIn(-90f, 90f)
            rollDeg = (rollDeg + (gy - biasY) * dt).coerceIn(-90f, 90f)
            var yaw = yawDeg + (gz - biasZ) * dt
            if (yaw > 180f) yaw -= 360f else if (yaw < -180f) yaw += 360f
            yawDeg = yaw
        }
    }

    /** Specific force a motionless headset reports at the given attitude, in g. */
    private fun gravityAt(pitchDeg: Float, rollDeg: Float): FloatArray {
        val p = pitchDeg * DEG
        val r = rollDeg * DEG
        return floatArrayOf(-sin(r) * cos(p), sin(p), cos(r) * cos(p))
    }

    // ---------------------------------------------------------------- (a) legacy compatibility

    /**
     * The contract that lets this land without touching any caller: a gyro-only feed of pure yaw
     * must still produce exactly what the old integrator produced, bias estimator and all.
     */
    @Test
    fun `pure yaw through the gyro-only path reproduces the legacy integrator`() {
        val tracker = HeadOrientationTracker()
        val legacy = LegacyTracker()
        var t = 0L
        // 5 s of a 20 deg/s turn at 1 kHz. Stays inside +-180 deg, so no wrap ambiguity.
        repeat(5_000) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 20f, t)
            legacy.accumulate(0f, 0f, 20f, t)
        }
        assertEquals(legacy.yawDeg, tracker.yawDeg, 0.01f)
        assertEquals(legacy.pitchDeg, tracker.pitchDeg, 0.01f)
        assertEquals(legacy.rollDeg, tracker.rollDeg, 0.01f)
        assertTrue("the bias estimator must still be eating most of a sustained turn", tracker.yawDeg < 60f)
    }

    @Test
    fun `all three axes through the gyro-only path reproduce the legacy integrator`() {
        val tracker = HeadOrientationTracker()
        val legacy = LegacyTracker()
        var t = 0L
        // A wobble rather than a sustained rate, so the bias estimator stays near zero and the
        // angles actually move — this exercises the Euler convention on all three axes at once.
        repeat(4_000) { i ->
            t += HZ_1K
            val phase = i * 0.004f
            val gx = 15f * sin(phase)
            val gy = 10f * sin(phase * 0.7f)
            val gz = 20f * sin(phase * 1.3f)
            tracker.accumulate(gx, gy, gz, t)
            legacy.accumulate(gx, gy, gz, t)
        }
        // Small-angle Euler integration and the quaternion agree to first order; over a few degrees
        // of coupled motion the second-order difference is the only gap.
        assertEquals(legacy.pitchDeg, tracker.pitchDeg, 0.5f)
        assertEquals(legacy.rollDeg, tracker.rollDeg, 0.5f)
        assertEquals(legacy.yawDeg, tracker.yawDeg, 0.5f)
    }

    @Test
    fun `yaw wraps at plus-minus 180 exactly as before`() {
        val tracker = HeadOrientationTracker()
        tracker.factoryCalibrated = true    // no leaky bias, so yaw really does advance
        var t = 0L
        repeat(2_500) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 100f, t)   // 100 deg/s for 2.5 s = 250 deg
        }
        assertEquals(-110f, tracker.yawDeg, 0.2f)
    }

    // ------------------------------------------------------- (b) gravity anchors pitch and roll

    /**
     * The headline improvement. A constant 2 deg/s gyro offset with the head perfectly still walks
     * gyro-only pitch off by 60 degrees over half a minute; the accelerometer holds it at level.
     */
    @Test
    fun `a constant gyro bias no longer drifts pitch away from level`() {
        val fused = HeadOrientationTracker().apply { factoryCalibrated = true }
        val gyroOnly = HeadOrientationTracker().apply { factoryCalibrated = true }
        val level = gravityAt(0f, 0f)
        var t = 0L
        repeat(15_000) {   // 30 s at 500 Hz
            t += HZ_500
            fused.accumulate(2f, 0f, 0f, level[0], level[1], level[2], t)
            gyroOnly.accumulate(2f, 0f, 0f, t)
        }
        assertTrue("gyro-only should have run away: ${gyroOnly.pitchDeg}", gyroOnly.pitchDeg > 55f)
        assertTrue("fused pitch should stay level, was ${fused.pitchDeg}", abs(fused.pitchDeg) < 2f)
    }

    @Test
    fun `roll is anchored too`() {
        val fused = HeadOrientationTracker().apply { factoryCalibrated = true }
        val level = gravityAt(0f, 0f)
        var t = 0L
        repeat(15_000) {
            t += HZ_500
            fused.accumulate(0f, -1.5f, 0f, level[0], level[1], level[2], t)
        }
        assertTrue("fused roll should stay level, was ${fused.rollDeg}", abs(fused.rollDeg) < 2f)
    }

    /**
     * Convergence from a wrong starting attitude, with the integral term switched off so the
     * response is the clean first-order one the [HeadOrientationTracker.accelGainKp] doc claims:
     * time constant ~1/Kp, i.e. ~1.7 s at the default gain.
     */
    @Test
    fun `accelerometer pulls a wrong attitude onto the true tilt`() {
        val tracker = HeadOrientationTracker(accelGainKi = 0f).apply { factoryCalibrated = true }
        val truth = gravityAt(20f, -15f)
        var t = 0L
        repeat(10_000) {   // 10 s at 1 kHz ≈ 6 time constants
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, truth[0], truth[1], truth[2], t)
        }
        assertEquals(20f, tracker.pitchDeg, 0.5f)
        assertEquals(-15f, tracker.rollDeg, 0.5f)
    }

    @Test
    fun `the correction is gentle rather than a snap`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        val truth = gravityAt(20f, 0f)
        var t = 0L
        // After a quarter of a second the filter should have moved, but nowhere near all the way —
        // an instant jump would read as a glitch on a head-tracked pointer.
        repeat(250) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, truth[0], truth[1], truth[2], t)
        }
        assertTrue("expected some movement, got ${tracker.pitchDeg}", tracker.pitchDeg > 0.5f)
        assertTrue("expected no snap, got ${tracker.pitchDeg}", tracker.pitchDeg < 8f)
    }

    @Test
    fun `the legacy leaky bias estimator still runs when there is no factory calibration`() {
        // Same constant offset, but the glasses gave us no calibration: the old estimator absorbs
        // it, so pitch must still not run away.
        val tracker = HeadOrientationTracker()      // factoryCalibrated defaults to false
        val level = gravityAt(0f, 0f)
        var t = 0L
        repeat(15_000) {
            t += HZ_500
            tracker.accumulate(2f, 0f, 0f, level[0], level[1], level[2], t)
        }
        assertTrue("was ${tracker.pitchDeg}", abs(tracker.pitchDeg) < 2f)
    }

    // --------------------------------------------------- gyro/accelerometer frame agreement

    /**
     * The decisive frame-consistency check, and the one thing the other tests here genuinely could
     * not catch.
     *
     * Every earlier gravity test either holds the attitude still or starts from a wrong one, so
     * [gravityAt] — which is the third row of this class's own rotation matrix — is only ever
     * compared against itself. This test instead rotates for real: the gyro is told the headset is
     * rolling at a constant rate while the accelerometer is fed the gravity vector for the attitude
     * that rotation *should* be producing, sample by sample. The two inputs are only consistent if
     * the gyro model and the accelerometer model agree about which way `gy` turns the headset.
     *
     * If they disagreed, the correction would fight the integration the whole way and a held tilt
     * would settle at the negated roll — a far nastier failure than a sign that is merely flipped,
     * because it is rate-dependent. Roll is the axis at issue: yaw and pitch are corroborated by
     * the shipped head-gesture code, roll is not.
     *
     * Note what this does *not* settle: which physical direction `gy` positive corresponds to. That
     * is a property of the hardware, not of this code, and stays an on-device check.
     */
    @Test
    fun `gyro and gravity agree on the direction of roll`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        val rate = 6f
        var t = 0L
        repeat(10_000) { i ->      // 10 s at 1 kHz ⇒ 60 deg of roll
            t += HZ_1K
            val g = gravityAt(0f, rate * i / 1000f)
            tracker.accumulate(0f, rate, 0f, g[0], g[1], g[2], t)
        }
        assertEquals("roll must follow the gyro, not fight it", 60f, tracker.rollDeg, 0.3f)
        assertEquals("a pure roll must not leak into pitch", 0f, tracker.pitchDeg, 0.3f)
        assertEquals(0f, tracker.yawDeg, 0.3f)
    }

    /** Same construction for pitch, as the control: this axis is already corroborated on-device. */
    @Test
    fun `gyro and gravity agree on the direction of pitch`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        val rate = 4f
        var t = 0L
        repeat(10_000) { i ->
            t += HZ_1K
            val g = gravityAt(rate * i / 1000f, 0f)
            tracker.accumulate(rate, 0f, 0f, g[0], g[1], g[2], t)
        }
        assertEquals(40f, tracker.pitchDeg, 0.3f)
        assertEquals(0f, tracker.rollDeg, 0.3f)
    }

    /**
     * Sharper version of the same idea: the gyro under-reports the roll rate by 20%, so the
     * accelerometer has to actively supply the missing rotation rather than merely agreeing with
     * it. Gyro alone would reach 48 degrees. A correction with the sign wrong would drag it *below*
     * that, not up toward the truth.
     */
    @Test
    fun `gravity makes up the difference when the roll gyro under-reports`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        val trueRate = 6f
        var t = 0L
        repeat(10_000) { i ->
            t += HZ_1K
            val g = gravityAt(0f, trueRate * i / 1000f)
            tracker.accumulate(0f, trueRate * 0.8f, 0f, g[0], g[1], g[2], t)
        }
        assertTrue(
            "accelerometer should have pulled roll up from the gyro's 48 deg, got ${tracker.rollDeg}",
            tracker.rollDeg > 55f,
        )
        assertTrue("...but not overshoot the truth, got ${tracker.rollDeg}", tracker.rollDeg < 62f)
    }

    // ------------------------------------------------------------------ (c) magnetometer on yaw

    /**
     * Yaw is the one axis gravity cannot see. With the magnetometer enabled, a constant yaw-rate
     * offset is pulled back toward the heading captured at start instead of integrating forever.
     */
    @Test
    fun `magnetometer correction bounds yaw drift`() {
        val withMag = tracker(magOn = true)
        val withoutMag = tracker(magOn = false)
        var t = 0L
        repeat(60_000) {   // 120 s at 500 Hz
            t += HZ_500
            feed(withMag, gz = 0.2f, t = t)
            feed(withoutMag, gz = 0.2f, t = t)
        }
        assertTrue("unaided yaw should drift: ${withoutMag.yawDeg}", withoutMag.yawDeg > 20f)
        assertTrue("mag-aided yaw should stay bounded: ${withMag.yawDeg}", abs(withMag.yawDeg) < 8f)
    }

    /**
     * The magnetometer cannot carry the same handedness bug as the accelerometer, and this pins
     * that down rather than leaving it to be argued about.
     *
     * [HeadOrientationTracker.magneticError] compares the measured field against where the estimate
     * says that same field should be, both expressed in the body frame — so it never has to know
     * which physical direction the mag's axes point. Any fixed vector works as a heading reference,
     * including ones with axes swapped or negated relative to the gyro's. If a handedness
     * assumption had been baked in, at least one of these would diverge instead of bounding drift.
     */
    @Test
    fun `mag correction does not assume any particular mag axis handedness`() {
        val fields = listOf(
            floatArrayOf(0.1f, 0.95f, -0.2f),      // the nominal orientation
            floatArrayOf(-0.1f, -0.95f, 0.2f),     // fully negated
            floatArrayOf(0.95f, 0.1f, -0.2f),      // x/y swapped: opposite handedness
            floatArrayOf(-0.95f, 0.1f, 0.2f),      // swapped and negated
        )
        for (field in fields) {
            val tracker = tracker(magOn = true)
            var t = 0L
            repeat(30_000) {   // 60 s at 500 Hz
                t += HZ_500
                val sample = XrealImuSample().apply {
                    gxDegSec = 0f; gyDegSec = 0f; gzDegSec = 0.2f
                    axG = 0f; ayG = 0f; azG = 1f
                    mx = field[0]; my = field[1]; mz = field[2]
                    magValid = true; calibrated = true; hostNanos = t
                }
                tracker.accumulate(sample)
            }
            // Unaided this would be 12 deg and climbing.
            assertTrue(
                "field (${field[0]}, ${field[1]}, ${field[2]}) drifted to ${tracker.yawDeg}",
                abs(tracker.yawDeg) < 8f,
            )
        }
    }

    /**
     * The flip side of the test above, and the reason it uses fields with a real horizontal
     * component: heading information lives entirely in the field's horizontal part, so a nearly
     * vertical field simply cannot correct yaw. (Same reason a compass is useless near the magnetic
     * poles.) What matters is that this degrades — drift is merely uncorrected, as if the
     * magnetometer were off — rather than the filter latching onto a meaningless heading and
     * actively steering yaw somewhere wrong.
     */
    @Test
    fun `a nearly vertical field leaves yaw uncorrected rather than steering it wrong`() {
        val tracker = tracker(magOn = true)
        var t = 0L
        repeat(30_000) {
            t += HZ_500
            val sample = XrealImuSample().apply {
                gxDegSec = 0f; gyDegSec = 0f; gzDegSec = 0.2f
                axG = 0f; ayG = 0f; azG = 1f
                mx = -0.02f; my = 0.01f; mz = 0.999f
                magValid = true; calibrated = true; hostNanos = t
            }
            tracker.accumulate(sample)
        }
        // 60 s of 0.2 deg/s, essentially unaided — and crucially still the *right* direction.
        assertEquals(12f, tracker.yawDeg, 1.5f)
    }

    @Test
    fun `magnetometer correction is off unless asked for`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        assertFalse(tracker.magYawCorrection)
        var t = 0L
        repeat(30_000) {
            t += HZ_500
            feed(tracker, gz = 0.2f, t = t)
        }
        // 60 s of 0.2 deg/s with no correction is 12 deg — the mag data was present and ignored.
        assertEquals(12f, tracker.yawDeg, 1f)
    }

    @Test
    fun `an invalid magnetometer reading is ignored even when correction is enabled`() {
        val tracker = HeadOrientationTracker().apply {
            factoryCalibrated = true
            magYawCorrection = true
        }
        var t = 0L
        repeat(30_000) {
            t += HZ_500
            feed(tracker, gz = 0.2f, t = t, magValid = false)
        }
        assertEquals(12f, tracker.yawDeg, 1f)
    }

    // ------------------------------------------------------------------- (d) motion rejection

    /**
     * A knock or a hard nod makes the accelerometer read something that is not gravity. Trusting it
     * would yank the horizon; the magnitude gate throws it out.
     */
    @Test
    fun `an accelerometer spike does not yank pitch`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        val level = gravityAt(0f, 0f)
        var t = 0L
        repeat(1_000) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, level[0], level[1], level[2], t)
        }
        val settled = tracker.pitchDeg

        // 0.5 s of a 1.27 g reading tilted ~45 deg: direction says "pitched over", magnitude says
        // "this is not gravity".
        repeat(500) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, 0f, 0.9f, 0.9f, t)
        }
        assertEquals("spike moved pitch to ${tracker.pitchDeg}", settled, tracker.pitchDeg, 0.05f)
    }

    @Test
    fun `the same direction at one g is trusted`() {
        // Control for the test above: identical direction, plausible magnitude, and now the filter
        // does follow it — so the rejection is about magnitude, not about being inert.
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        var t = 0L
        val unit = 0.70710677f
        repeat(1_500) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, 0f, unit, unit, t)
        }
        assertTrue("expected pitch to follow, got ${tracker.pitchDeg}", tracker.pitchDeg > 10f)
    }

    @Test
    fun `free fall and a hard knock are both rejected`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        var t = 0L
        repeat(2_000) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, 0f, 0.05f, 0.05f, t)      // ~0.07 g: free fall
        }
        assertEquals(0f, tracker.pitchDeg, 0.02f)
        repeat(2_000) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 0f, 0f, 2.5f, 2.5f, t)        // 3.5 g: impact
        }
        assertEquals(0f, tracker.pitchDeg, 0.02f)
    }

    @Test
    fun `an all-zero accelerometer sample degrades to gyro-only instead of exploding`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        var t = 0L
        repeat(1_000) {
            t += HZ_1K
            tracker.accumulate(0f, 0f, 10f, 0f, 0f, 0f, t)
        }
        assertEquals(10f, tracker.yawDeg, 0.1f)
        assertFalse(tracker.pitchDeg.isNaN())
        assertEquals(0f, tracker.pitchDeg, 1e-3f)
    }

    // ---------------------------------------------------------------------- (e) reset / re-centre

    @Test
    fun `reset zeroes the orientation and every learnt bias`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        val tilted = gravityAt(25f, 10f)
        var t = 0L
        repeat(5_000) {
            t += HZ_1K
            tracker.accumulate(3f, 0f, 30f, tilted[0], tilted[1], tilted[2], t)
        }
        assertTrue(tracker.hasSamples())
        assertTrue(abs(tracker.yawDeg) > 5f)

        tracker.reset()
        assertEquals(0f, tracker.yawDeg, 0f)
        assertEquals(0f, tracker.pitchDeg, 0f)
        assertEquals(0f, tracker.rollDeg, 0f)
        assertFalse("reset must look like a fresh tracker", tracker.hasSamples())

        // ...and the clock is re-seeded, so the first sample after a reset moves nothing.
        t += HZ_1K
        tracker.accumulate(0f, 0f, 90f, t)
        assertEquals(0f, tracker.yawDeg, 0f)
        assertTrue(tracker.hasSamples())
        // The one after it accumulates from zero, not from wherever we were before the reset.
        t += HZ_1K
        tracker.accumulate(0f, 0f, 90f, t)
        assertEquals(0.09f, tracker.yawDeg, 0.005f)
    }

    @Test
    fun `re-centring mid-stream restarts the magnetometer reference`() {
        val tracker = tracker(magOn = true)
        var t = 0L
        repeat(5_000) { t += HZ_1K; feed(tracker, gz = 0f, t = t) }
        tracker.reset()
        // After the reset the next valid magnetometer sample becomes the new heading reference, so
        // yaw stays put rather than being dragged back to the pre-reset heading.
        repeat(5_000) { t += HZ_1K; feed(tracker, gz = 0f, t = t) }
        assertEquals(0f, tracker.yawDeg, 0.5f)
    }

    @Test
    fun `hasSamples flips on the very first sample`() {
        val tracker = HeadOrientationTracker()
        assertFalse(tracker.hasSamples())
        tracker.accumulate(0f, 0f, 0f, HZ_1K)
        assertTrue(tracker.hasSamples())
    }

    // --------------------------------------------------------------------------- housekeeping

    @Test
    fun `a stalled stream re-seeds the clock instead of integrating across the gap`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        var t = 1_000_000L
        tracker.accumulate(0f, 0f, 90f, t)
        t += 1_000_000_000L                     // 1 s gap, well past the 0.25 s limit
        tracker.accumulate(0f, 0f, 90f, t)
        assertEquals("a 1 s gap must not fling yaw 90 deg", 0f, tracker.yawDeg, 1e-4f)
        t += HZ_1K
        tracker.accumulate(0f, 0f, 90f, t)
        assertEquals(0.09f, tracker.yawDeg, 0.005f)
    }

    @Test
    fun `a non-advancing timestamp is ignored`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        tracker.accumulate(0f, 0f, 90f, 1_000_000L)
        tracker.accumulate(0f, 0f, 90f, 1_000_000L)
        tracker.accumulate(0f, 0f, 90f, 500_000L)
        assertEquals(0f, tracker.yawDeg, 0f)
    }

    @Test
    fun `the sample overload feeds the fusion and picks up the calibration flag`() {
        val tracker = HeadOrientationTracker()
        val level = gravityAt(0f, 0f)
        val sample = XrealImuSample()
        var t = 0L
        repeat(15_000) {
            t += HZ_500
            sample.gxDegSec = 2f; sample.gyDegSec = 0f; sample.gzDegSec = 0f
            sample.axG = level[0]; sample.ayG = level[1]; sample.azG = level[2]
            sample.magValid = false
            sample.calibrated = true
            sample.hostNanos = t
            tracker.accumulate(sample)
        }
        assertTrue(tracker.factoryCalibrated)
        assertTrue("was ${tracker.pitchDeg}", abs(tracker.pitchDeg) < 2f)
    }

    @Test
    fun `pitch and roll stay inside the published range`() {
        val tracker = HeadOrientationTracker().apply { factoryCalibrated = true }
        var t = 0L
        repeat(20_000) {
            t += HZ_1K
            tracker.accumulate(30f, 30f, 0f, t)
        }
        assertTrue(tracker.pitchDeg in -90f..90f)
        assertTrue(tracker.rollDeg in -90f..90f)
        assertTrue(tracker.yawDeg in -180f..180f)
    }

    // --- helpers ---

    private fun tracker(magOn: Boolean) = HeadOrientationTracker().apply {
        factoryCalibrated = true
        magYawCorrection = magOn
    }

    /**
     * One synthetic report for a headset held level and still, apart from the gyro reporting [gz].
     * The magnetic field is mostly horizontal, which is what makes it informative about heading.
     */
    private fun feed(
        tracker: HeadOrientationTracker,
        gz: Float,
        t: Long,
        magValid: Boolean = true,
    ) {
        val sample = XrealImuSample().apply {
            gxDegSec = 0f; gyDegSec = 0f; gzDegSec = gz
            axG = 0f; ayG = 0f; azG = 1f
            mx = 0.1f; my = 0.95f; mz = -0.2f
            this.magValid = magValid
            calibrated = true
            hostNanos = t
        }
        tracker.accumulate(sample)
    }
}
