package com.teleteh.xplayer2.data.glasses

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Head orientation (yaw / pitch / roll in degrees) from the XREAL IMU.
 *
 * ## What changed, and why
 *
 * This used to integrate the gyro and nothing else. Gyro-only integration has no absolute
 * reference, so every axis walks away over time; a leaky zero-rate-bias estimate kept resting
 * drift small but also ate any genuinely slow head movement, and once the head had tilted there
 * was nothing to pull pitch and roll back to level. Now the gyro propagates a quaternion and the
 * accelerometer corrects it toward gravity, so pitch and roll are *absolute* and stop drifting
 * entirely; yaw remains the only unobserved axis.
 *
 * ## Filter choice: Mahony, explicit gains
 *
 * A Mahony complementary filter rather than Madgwick or an EKF:
 *
 *  - The two gains mean something physical and can be tuned against a real headset.
 *    [accelGainKp] sets how fast tilt error is pulled out (time constant ≈ `1/Kp`), [accelGainKi]
 *    how fast a residual gyro bias is learnt. Madgwick's single `beta` conflates the two, and this
 *    filter's whole job is to be *predictable* for a UI pointer — a visible tilt correction is
 *    worse than a slow one.
 *  - It costs one cross product per sample. At the ~1 kHz these glasses report, on a background
 *    thread, that matters more than an EKF's marginally better transient.
 *  - `nrealAirLinuxDriver` reaches the same conclusion for the same hardware, running x-io's Fusion
 *    (a Mahony variant) at gain 0.5 with 10° acceleration rejection. Our defaults are that tuning
 *    re-expressed in raw Mahony terms.
 *
 * Magnetometer: **off by default**. The XREAL mag block is scaled in device units through an
 * unknown rotation (`ar-drivers-rs` skips it outright as "non-trivially rotated"), and
 * `nrealAirLinuxDriver` parses it but deliberately feeds the no-magnetometer update path with the
 * note that it "seems to make results of sensor fusion generally worse". [magYawCorrection] exists
 * so it can be A/B-tested on real glasses without another build, not because it is trusted.
 *
 * ## Axes and conventions — unchanged from the gyro-only version
 *
 * `gx` = pitch rate, `gy` = roll rate, `gz` = yaw rate, forming a right-handed body frame whose
 * third axis is up. Accelerometer input is in g in the same frame, so level and motionless reads
 * `(0, 0, +1)`. Internally the state is a quaternion; the exposed Euler angles use the `Rz(yaw) ·
 * Rx(pitch) · Ry(roll)` decomposition, which for a pure yaw rotation reproduces the old
 * `yaw += gz·dt` exactly (asserted by [HeadOrientationTrackerTest]). Pitch and roll keep the old
 * ±90° clamps and yaw keeps the old ±180° wrap.
 *
 * ## Thread-safety
 *
 * `accumulate` runs on the IMU reader thread; the angle getters and [hasSamples] are read from the
 * render thread. Volatile fields — a torn read at worst jitters one frame.
 */
class HeadOrientationTracker(
    /**
     * Time constant (s) for the legacy zero-rate-bias estimate. Only used when the glasses gave us
     * no factory calibration (see [factoryCalibrated]) — it is a leaky high-pass that also eats
     * slow real rotation, which is exactly why factory bias is preferred when available.
     */
    private val biasTauSec: Float = 4.0f,
    /**
     * Proportional gain (1/s) on the gravity error. 0.6 ⇒ a tilt error decays with a ~1.7 s time
     * constant: fast enough that the horizon re-levels within a couple of seconds of the head
     * settling, slow enough that it contributes nothing visible during a deliberate nod.
     */
    private val accelGainKp: Float = 0.6f,
    /**
     * Integral gain (1/s²) on the gravity error — this is the part that learns the gyro's
     * zero-rate offset on the two axes gravity can observe. With Kp = 0.6 the loop is overdamped
     * (ζ ≈ 2), settling a constant bias over ~30 s with no overshoot.
     */
    private val accelGainKi: Float = 0.02f,
    /**
     * Proportional gain (1/s) on the magnetic heading error. An order of magnitude below
     * [accelGainKp] because the mag is the least trustworthy input here; only used when
     * [magYawCorrection] is on.
     */
    private val magGainKp: Float = 0.05f,
) {
    @Volatile var yawDeg: Float = 0f
        private set
    @Volatile var pitchDeg: Float = 0f
        private set
    @Volatile var rollDeg: Float = 0f
        private set

    /**
     * Set to true once the reader has applied the glasses' own factory gyro bias. That bias is a
     * proper per-unit constant, so the legacy leaky estimator is switched off and slow real
     * rotation is tracked honestly. Left false, the legacy estimator runs exactly as it always did.
     */
    @Volatile var factoryCalibrated: Boolean = false

    /**
     * Enable magnetometer yaw correction. Default off — see the class doc. Turning this on only
     * has an effect when the samples actually carry valid magnetometer data.
     */
    @Volatile var magYawCorrection: Boolean = false

    // Orientation: body → world, (w, x, y, z), always kept normalised.
    private var qw = 1f
    private var qx = 0f
    private var qy = 0f
    private var qz = 0f

    // Legacy leaky zero-rate estimate (fallback path, unchanged arithmetic).
    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f

    // Mahony integral term — the learnt gyro bias, rad/s, body axes.
    private var integralX = 0f
    private var integralY = 0f
    private var integralZ = 0f

    // This sample's proportional error term, body axes, dimensionless (a cross product of unit
    // vectors, so its magnitude is the sine of the angular error). Held in fields rather than
    // returned in an object to keep the ~1 kHz path allocation-free.
    private var errX = 0f
    private var errY = 0f
    private var errZ = 0f

    // Magnetic heading reference, captured on the first valid magnetometer sample.
    private var magRefValid = false
    private var magRefX = 0f
    private var magRefY = 0f

    private var lastNanos = 0L
    @Volatile private var samples = 0L

    /** True once at least one IMU sample has arrived — i.e. telemetry is actually flowing. */
    fun hasSamples(): Boolean = samples > 0L

    /** Zero the orientation and forget every learnt bias. Also the re-centre operation. */
    fun reset() {
        yawDeg = 0f; pitchDeg = 0f; rollDeg = 0f
        qw = 1f; qx = 0f; qy = 0f; qz = 0f
        biasX = 0f; biasY = 0f; biasZ = 0f
        integralX = 0f; integralY = 0f; integralZ = 0f
        magRefValid = false; magRefX = 0f; magRefY = 0f
        lastNanos = 0L; samples = 0L
    }

    /**
     * Gyro-only update — the original entry point, byte-for-byte the original behaviour. Kept
     * working for callers that have no accelerometer to hand; prefer one of the richer overloads,
     * which is the only way pitch and roll get an absolute reference.
     */
    fun accumulate(gxDegSec: Float, gyDegSec: Float, gzDegSec: Float, tNanos: Long) {
        update(gxDegSec, gyDegSec, gzDegSec, 0f, 0f, 0f, false, 0f, 0f, 0f, false, tNanos)
    }

    /**
     * Gyro + accelerometer update. Accelerometer in g, same body axes as the gyro; the correction
     * is skipped automatically whenever the reading is not dominated by gravity (see
     * [ACCEL_REJECT_G]).
     */
    fun accumulate(
        gxDegSec: Float, gyDegSec: Float, gzDegSec: Float,
        axG: Float, ayG: Float, azG: Float,
        tNanos: Long,
    ) {
        update(gxDegSec, gyDegSec, gzDegSec, axG, ayG, azG, true, 0f, 0f, 0f, false, tNanos)
    }

    /** Full update from a decoded report — gyro, accelerometer and (if enabled) magnetometer. */
    fun accumulate(sample: XrealImuSample) {
        factoryCalibrated = sample.calibrated
        update(
            sample.gxDegSec, sample.gyDegSec, sample.gzDegSec,
            sample.axG, sample.ayG, sample.azG, true,
            sample.mx, sample.my, sample.mz, sample.magValid,
            sample.hostNanos,
        )
    }

    private fun update(
        gxIn: Float, gyIn: Float, gzIn: Float,
        axG: Float, ayG: Float, azG: Float, haveAccel: Boolean,
        mx: Float, my: Float, mz: Float, haveMag: Boolean,
        tNanos: Long,
    ) {
        samples++
        val prev = lastNanos
        lastNanos = tNanos
        if (prev == 0L) return
        val dt = (tNanos - prev) / 1_000_000_000f
        // A gap this long means the stream stalled (thread descheduled, USB hiccup); integrating
        // across it would fling the orientation, so the sample only re-seeds the clock.
        if (dt <= 0f || dt > 0.25f) return

        var gx = gxIn
        var gy = gyIn
        var gz = gzIn

        if (!factoryCalibrated) {
            // Legacy fallback: track and subtract a leaky zero-rate bias so a still head doesn't
            // run away. Identical arithmetic to the pre-fusion tracker.
            val step = (dt / biasTauSec).coerceIn(0f, 1f)
            biasX += (gx - biasX) * step
            biasY += (gy - biasY) * step
            biasZ += (gz - biasZ) * step
            gx -= biasX; gy -= biasY; gz -= biasZ
        }

        if (haveAccel) {
            gravityError(axG, ayG, azG)
            if (accelGainKi > 0f) {
                integralX = (integralX + accelGainKi * errX * dt).coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT)
                integralY = (integralY + accelGainKi * errY * dt).coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT)
                integralZ = (integralZ + accelGainKi * errZ * dt).coerceIn(-INTEGRAL_LIMIT, INTEGRAL_LIMIT)
            }
            // The gains are in rad/s per unit error; the rates here are deg/s.
            gx += (accelGainKp * errX + integralX) * RAD_TO_DEG
            gy += (accelGainKp * errY + integralY) * RAD_TO_DEG
            gz += (accelGainKp * errZ + integralZ) * RAD_TO_DEG
        }
        if (haveMag && magYawCorrection) {
            magneticError(mx, my, mz)
            gx += magGainKp * errX * RAD_TO_DEG
            gy += magGainKp * errY * RAD_TO_DEG
            gz += magGainKp * errZ * RAD_TO_DEG
        }

        integrate(gx, gy, gz, dt)
        publishEuler()
    }

    /**
     * Mahony's gravity correction: compare the measured specific force against where the current
     * estimate says "down" is, and turn the mismatch into a rotation-rate nudge.
     *
     * Motion rejection: the comparison is only meaningful when the accelerometer is measuring
     * gravity and not the user's head accelerating. A magnitude gate handles this — the further
     * `‖a‖` is from 1 g the less the sample is trusted, fading linearly to nothing at
     * [ACCEL_REJECT_G]. A hard cut-off alone would make the correction switch on and off audibly
     * during motion; the ramp keeps it smooth.
     */
    private fun gravityError(axG: Float, ayG: Float, azG: Float) {
        errX = 0f; errY = 0f; errZ = 0f
        val norm = sqrt(axG * axG + ayG * ayG + azG * azG)
        if (norm < 1e-4f) return
        val trust = 1f - (abs(norm - 1f) / ACCEL_REJECT_G)
        if (trust <= 0f) return

        val ax = axG / norm
        val ay = ayG / norm
        val az = azG / norm

        // Gravity direction as the current estimate sees it, in body coordinates: R^T · (0,0,1),
        // i.e. the third row of the body→world rotation matrix.
        val vx = 2f * (qx * qz - qw * qy)
        val vy = 2f * (qy * qz + qw * qx)
        val vz = qw * qw - qx * qx - qy * qy + qz * qz

        // Error = measured × estimated. Zero when they agree; its direction is the axis to rotate
        // about to bring them together, and its sign is such that adding it to the body rate
        // shrinks the error.
        errX = (ay * vz - az * vy) * trust
        errY = (az * vx - ax * vz) * trust
        errZ = (ax * vy - ay * vx) * trust
    }

    /**
     * Heading error from the magnetometer, in the same measured-×-expected form as [gravityError]
     * so the sign convention carries over unchanged.
     *
     * The XREAL mag is in unknown units through an unknown rotation, so absolute north is not on
     * the table. What *is* usable is relative: the world-frame direction of the field should not
     * change while the headset is still, so the direction captured on the first sample becomes the
     * reference and any later disagreement is yaw drift. That anchors yaw to wherever the user was
     * looking when tracking started — precisely what a re-centred head pointer wants — without
     * pretending to be a compass. Tilt is handled implicitly: the reference keeps the field's
     * vertical component and only its horizontal part is re-pointed.
     */
    private fun magneticError(mx: Float, my: Float, mz: Float) {
        errX = 0f; errY = 0f; errZ = 0f
        val norm = sqrt(mx * mx + my * my + mz * mz)
        if (norm < 1e-6f) return
        val bx = mx / norm
        val by = my / norm
        val bz = mz / norm

        // Body→world rotation matrix rows we need.
        val r00 = 1f - 2f * (qy * qy + qz * qz)
        val r01 = 2f * (qx * qy - qw * qz)
        val r02 = 2f * (qx * qz + qw * qy)
        val r10 = 2f * (qx * qy + qw * qz)
        val r11 = 1f - 2f * (qx * qx + qz * qz)
        val r12 = 2f * (qy * qz - qw * qx)
        val r20 = 2f * (qx * qz - qw * qy)
        val r21 = 2f * (qy * qz + qw * qx)
        val r22 = 1f - 2f * (qx * qx + qy * qy)

        // Measured field in world coordinates: h = R · b.
        val hx = r00 * bx + r01 * by + r02 * bz
        val hy = r10 * bx + r11 * by + r12 * bz
        val hz = r20 * bx + r21 * by + r22 * bz

        val hNorm = sqrt(hx * hx + hy * hy)
        // Field almost vertical ⇒ no horizontal component to take a heading from.
        if (hNorm < 0.1f) return
        if (!magRefValid) {
            magRefX = hx / hNorm; magRefY = hy / hNorm; magRefValid = true
            return
        }

        // Where the field *should* be in world coordinates: same magnitude and same vertical part,
        // but pointing along the reference heading.
        val refX = magRefX * hNorm
        val refY = magRefY * hNorm
        // ...and what that would look like from the body: w = R^T · ref.
        val wx = r00 * refX + r10 * refY + r20 * hz
        val wy = r01 * refX + r11 * refY + r21 * hz
        val wz = r02 * refX + r12 * refY + r22 * hz

        errX = by * wz - bz * wy
        errY = bz * wx - bx * wz
        errZ = bx * wy - by * wx
    }

    /**
     * Advance the quaternion by [dt] under body rates [gx], [gy], [gz] (deg/s).
     *
     * Exact exponential map rather than the usual `q += ½·q⊗ω·dt` first-order step: it is one extra
     * `sin`/`cos` per sample and it makes a pure yaw rotation reproduce the old `yaw += gz·dt`
     * arithmetic to floating-point precision, so the compatibility test can assert a tight
     * tolerance instead of an approximate one.
     */
    private fun integrate(gx: Float, gy: Float, gz: Float, dt: Float) {
        val wx = gx * DEG_TO_RAD
        val wy = gy * DEG_TO_RAD
        val wz = gz * DEG_TO_RAD
        val omega = sqrt(wx * wx + wy * wy + wz * wz)
        val theta = omega * dt
        if (theta < 1e-12f) return

        val half = theta * 0.5f
        val s = sin(half) / omega
        val dw = cos(half)
        val dx = wx * s
        val dy = wy * s
        val dz = wz * s

        // q = q ⊗ dq (body-frame rotation composes on the right).
        val nw = qw * dw - qx * dx - qy * dy - qz * dz
        val nx = qw * dx + qx * dw + qy * dz - qz * dy
        val ny = qw * dy - qx * dz + qy * dw + qz * dx
        val nz = qw * dz + qx * dy - qy * dx + qz * dw

        val n = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
        if (n < 1e-9f) return
        qw = nw / n; qx = nx / n; qy = ny / n; qz = nz / n
    }

    /**
     * Quaternion → the Euler triple this class has always published, using the `Rz(yaw) · Rx(pitch)
     * · Ry(roll)` decomposition. Pitch comes straight out of `asin` (so it is naturally in ±90°,
     * matching the old clamp); roll and yaw come from `atan2` and keep the old clamp/wrap.
     */
    private fun publishEuler() {
        val r21 = 2f * (qy * qz + qw * qx)
        val r20 = 2f * (qx * qz - qw * qy)
        val r22 = 1f - 2f * (qx * qx + qy * qy)
        val r01 = 2f * (qx * qy - qw * qz)
        val r11 = 1f - 2f * (qx * qx + qz * qz)

        pitchDeg = (asin(r21.coerceIn(-1f, 1f)) * RAD_TO_DEG).coerceIn(-90f, 90f)
        rollDeg = (atan2(-r20, r22) * RAD_TO_DEG).coerceIn(-90f, 90f)
        var yaw = atan2(-r01, r11) * RAD_TO_DEG
        if (yaw > 180f) yaw -= 360f else if (yaw < -180f) yaw += 360f
        yawDeg = yaw
    }

    private companion object {
        const val DEG_TO_RAD = 0.017453292f
        const val RAD_TO_DEG = 57.295780f

        /**
         * How far `‖a‖` may stray from 1 g before the sample carries no weight at all. 0.15 g is
         * the same order as x-io Fusion's 10° acceleration rejection, expressed as a magnitude
         * instead of an angle; ordinary head movement stays well inside it, a knock does not.
         */
        const val ACCEL_REJECT_G = 0.15f

        /** Cap on the learnt gyro bias, rad/s (≈10 deg/s). Far above any real zero-rate offset,
         *  low enough that a pathological accel stream cannot wind the filter up into a spin. */
        const val INTEGRAL_LIMIT = 0.1745f
    }
}
