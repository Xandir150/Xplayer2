package com.teleteh.xplayer2.data.glasses

import kotlin.math.abs

/**
 * Integrates gyro samples from [XrealImuReader] into a small parallax offset suitable
 * for shifting a video frame in a fragment shader. Outputs are pure-software pose
 * estimates clipped to a small range — this is not a real head-tracking solution,
 * just enough to fake depth on top of a flat video by nudging the picture as the
 * user turns their head.
 *
 * Coordinate convention: positive `offsetX` means the picture shifts LEFT on screen
 * (the shader samples further right). We move the picture opposite to the head turn —
 * the "stabilise the view" convention — which reads as looking through a fixed window.
 * Both outputs are in normalized 0..1 texture space, clipped to ±[maxOffset].
 *
 * XREAL gyro axes (verified on-device against the xspace / XSpace-mac / mobile ports):
 *   gx = pitch rate (head nods up/down)   → offsetY
 *   gy = roll rate  (head tilts sideways) → unused, doesn't help parallax
 *   gz = yaw rate   (head turns left/right) → offsetX
 *
 * Drift control — the important part. A MEMS gyro reports a small non-zero rate even
 * when perfectly still ("zero-rate bias"); integrated, that constant offset marches the
 * picture into a corner and never comes back. We defend in three layers:
 *   1. Bias tracking: while the head is near-still we slowly learn the resting rate and
 *      subtract it, so the integrated input has no DC component to run away on.
 *   2. Rate dead-zone: any residual sub-threshold rate is dropped to exactly zero.
 *   3. Return-to-centre leak: the integrated angle bleeds toward zero, so even after a
 *      real turn the picture eases back to centre when the head settles.
 *
 * Thread safety: [accumulate] runs on the IMU reader thread; [snapshot] / [hasSamples] /
 * [debugLine] may be read from any thread. A torn read at worst jitters one frame.
 */
class HeadPoseTracker(
    /** EMA coefficient applied to the incoming gyro rate. Lower = smoother rate. */
    private val rateSmoothAlpha: Float = 0.3f,
    /** EMA coefficient applied to the output offset. Higher = snappier, lower = smoother. */
    private val outputSmoothAlpha: Float = 0.25f,
    /** Gyro rates below this (deg/s) are treated as zero, killing resting jitter. */
    private val rateDeadzoneDegSec: Float = 0.7f,
    /** Per-update drift correction toward zero, applied to the integrated angle. */
    private val driftLeak: Float = 0.02f,
    /** Maximum absolute offset in normalized [0..1] units. */
    private val maxOffset: Float = 0.04f,
    /** Sensitivity from yaw/pitch deg to normalized offset. ±15° of head turn maps to ±maxOffset. */
    private val degreesPerFullSwing: Float = 15f,
    /** Time constant (seconds) for tracking the gyro zero-rate bias. Slow, so it cancels a
     *  resting drift over a few seconds without noticeably eating sub-second head motion. */
    private val biasTauSec: Float = 3.0f,
) {

    // Integrated yaw (left/right) and pitch (up/down) in degrees.
    @Volatile private var yawDeg: Float = 0f
    @Volatile private var pitchDeg: Float = 0f

    // Low-pass-filtered gyro rates (deg/s) that actually get integrated.
    @Volatile private var yawRate: Float = 0f
    @Volatile private var pitchRate: Float = 0f

    // Tracked zero-rate bias (deg/s) subtracted from the raw rate.
    @Volatile private var biasYaw: Float = 0f
    @Volatile private var biasPitch: Float = 0f

    // Smoothed outputs in normalized texture space.
    @Volatile var offsetX: Float = 0f
        private set
    @Volatile var offsetY: Float = 0f
        private set

    @Volatile private var lastTimeNanos: Long = 0L

    // --- debug telemetry (surfaced on the remote while tuning; cheap, safe to leave in) ---
    @Volatile private var sampleCount: Long = 0L
    @Volatile private var rawGx: Float = 0f
    @Volatile private var rawGy: Float = 0f
    @Volatile private var rawGz: Float = 0f

    /** True once at least one IMU sample has been delivered — used to show "data flowing". */
    fun hasSamples(): Boolean = sampleCount > 0L

    fun reset() {
        yawDeg = 0f
        pitchDeg = 0f
        yawRate = 0f
        pitchRate = 0f
        biasYaw = 0f
        biasPitch = 0f
        offsetX = 0f
        offsetY = 0f
        lastTimeNanos = 0L
        sampleCount = 0L
        rawGx = 0f
        rawGy = 0f
        rawGz = 0f
    }

    /**
     * Feed a gyro sample. `gx` is pitch rate (deg/s), `gy` is roll rate (ignored — roll
     * doesn't help parallax), `gz` is yaw rate. [tNanos] should monotonically increase
     * from the IMU reader.
     */
    fun accumulate(gxDegSec: Float, gyDegSec: Float, gzDegSec: Float, tNanos: Long) {
        sampleCount++
        rawGx = gxDegSec
        rawGy = gyDegSec
        rawGz = gzDegSec

        val prev = lastTimeNanos
        lastTimeNanos = tNanos
        if (prev == 0L) return // first sample — bootstrap the timer
        val dtSec = (tNanos - prev) / 1_000_000_000f
        if (dtSec <= 0f || dtSec > 0.25f) return // ignore obvious gaps / large pauses

        // Map XREAL gyro axes to head pose: gz = yaw (left/right), gx = pitch (up/down).
        val rawYaw = gzDegSec
        val rawPitch = gxDegSec

        // Track and subtract the gyro zero-rate bias — the constant offset that would otherwise
        // integrate the picture into a corner and never return. A gyro reads angular *velocity*,
        // so holding a turned pose contributes nothing here; only the resting bias (and very slow
        // sustained pans) feed this estimate, which is exactly what we want to cancel. The time
        // constant is made rate-independent so it behaves the same at any IMU sample rate.
        val biasStep = (dtSec / biasTauSec).coerceIn(0f, 1f)
        biasYaw += (rawYaw - biasYaw) * biasStep
        biasPitch += (rawPitch - biasPitch) * biasStep

        var yr = rawYaw - biasYaw
        var pr = rawPitch - biasPitch

        // Rate dead-zone: drop residual sub-threshold noise so a motionless head stays put.
        if (abs(yr) < rateDeadzoneDegSec) yr = 0f
        if (abs(pr) < rateDeadzoneDegSec) pr = 0f

        // EMA the rate to take the high-frequency edge off raw gyro noise.
        yawRate += (yr - yawRate) * rateSmoothAlpha
        pitchRate += (pr - pitchRate) * rateSmoothAlpha

        // Integrate the smoothed rate into pose.
        yawDeg += yawRate * dtSec
        pitchDeg += pitchRate * dtSec

        // Return-to-centre leak — bleed accumulated angle toward zero so the picture eases
        // back to centre when the head settles instead of staying nudged off-centre.
        val leak = (1f - driftLeak * dtSec * 60f).coerceIn(0f, 1f)
        yawDeg *= leak
        pitchDeg *= leak

        // Map degrees → normalized texture units, clip to maxOffset.
        val targetX = ((yawDeg / degreesPerFullSwing) * maxOffset).coerceIn(-maxOffset, maxOffset)
        val targetY = ((pitchDeg / degreesPerFullSwing) * maxOffset).coerceIn(-maxOffset, maxOffset)

        // EMA the offset toward target to remove any residual micro-jitter.
        offsetX += (targetX - offsetX) * outputSmoothAlpha
        offsetY += (targetY - offsetY) * outputSmoothAlpha
    }

    /** Snapshot the current offsets as a pair — for callers that want both at once. */
    fun snapshot(): Pair<Float, Float> = offsetX to offsetY

    /**
     * Compact two-line telemetry for the on-screen debug overlay (temporary tuning aid):
     * sample count + raw gyro rates, then the learned bias and the applied shift.
     */
    fun debugLine(): String = ("IMU n=%d  gx=%+.1f gy=%+.1f gz=%+.1f °/s\n" +
        "bias z=%+.2f x=%+.2f  shift x=%+.1f%% y=%+.1f%%").format(
        sampleCount, rawGx, rawGy, rawGz, biasYaw, biasPitch, offsetX * 100f, offsetY * 100f
    )
}
