package com.teleteh.xplayer2.data.glasses

/**
 * Integrates gyro samples from [XrealImuReader] into a small parallax offset suitable
 * for shifting a video frame in a fragment shader. Outputs are pure-software pose
 * estimates clipped to a small range — this is not a real head-tracking solution,
 * just enough to fake depth on top of a flat video by nudging the picture as the
 * user turns their head.
 *
 * Coordinate convention: positive `offsetX` means the user looked LEFT (so the
 * picture should shift right to compensate); positive `offsetY` means looked UP.
 * Both outputs are in normalized 0..1 texture space — typically clipped to
 * ±[maxOffset] (default ±5% of frame width / height).
 *
 * Drift: pure-gyro integration drifts over seconds. We apply a very gentle
 * "return to centre" leak so the picture re-centres if the user holds their
 * head still — at the cost of a tiny perceived push-back during fast turns,
 * which feels natural rather than wrong.
 *
 * Thread safety: [accumulate] may be called from any thread (the IMU reader
 * thread). [snapshot] is fine from any thread; it just reads the current
 * smoothed pose. Updates of [offsetX] / [offsetY] are not strictly atomic but
 * a torn read at worst causes a single jittered frame.
 */
class HeadPoseTracker(
    /** Per-update low-pass coefficient. Higher = snappier, lower = smoother. */
    private val smoothAlpha: Float = 0.35f,
    /** Per-update drift correction toward zero, applied to the integrated angle. */
    private val driftLeak: Float = 0.02f,
    /** Maximum absolute offset in normalized [0..1] units. */
    private val maxOffset: Float = 0.05f,
    /** Sensitivity from yaw/pitch deg to normalized offset. ±15° of head turn maps to ±maxOffset. */
    private val degreesPerFullSwing: Float = 15f,
) {

    // Integrated yaw (left/right) and pitch (up/down) in degrees.
    @Volatile private var yawDeg: Float = 0f
    @Volatile private var pitchDeg: Float = 0f

    // Smoothed outputs in normalized texture space.
    @Volatile var offsetX: Float = 0f
        private set
    @Volatile var offsetY: Float = 0f
        private set

    @Volatile private var lastTimeNanos: Long = 0L

    fun reset() {
        yawDeg = 0f
        pitchDeg = 0f
        offsetX = 0f
        offsetY = 0f
        lastTimeNanos = 0L
    }

    /**
     * Feed a gyro sample. `gx` is pitch rate (deg/s), `gy` is yaw rate, `gz` is roll rate
     * (currently ignored — roll doesn't help parallax much). [tNanos] should monotonically
     * increase from the IMU reader.
     */
    fun accumulate(gxDegSec: Float, gyDegSec: Float, gzDegSec: Float, tNanos: Long) {
        val prev = lastTimeNanos
        lastTimeNanos = tNanos
        if (prev == 0L) return // first sample — bootstrap the timer
        val dtSec = (tNanos - prev) / 1_000_000_000f
        if (dtSec <= 0f || dtSec > 0.25f) return // ignore obvious gaps / large pauses

        // Integrate gyro rates into pose. XREAL's gyro axes are (pitch, yaw, roll) per the
        // packet layout in the nrealAirLinuxDriver writeup — we use gx for pitch and gy for yaw.
        yawDeg += gyDegSec * dtSec
        pitchDeg += gxDegSec * dtSec

        // Drift leak — bleed off accumulated error toward zero so the picture doesn't end up
        // permanently nudged off-centre after a long session.
        yawDeg *= (1f - driftLeak * dtSec * 60f).coerceIn(0f, 1f)
        pitchDeg *= (1f - driftLeak * dtSec * 60f).coerceIn(0f, 1f)

        // Map degrees → normalized texture units, clip to maxOffset.
        val rawX = (yawDeg / degreesPerFullSwing) * maxOffset
        val rawY = (pitchDeg / degreesPerFullSwing) * maxOffset
        val targetX = rawX.coerceIn(-maxOffset, maxOffset)
        val targetY = rawY.coerceIn(-maxOffset, maxOffset)

        // EMA toward target to remove micro-jitter from raw gyro noise.
        offsetX = offsetX + (targetX - offsetX) * smoothAlpha
        offsetY = offsetY + (targetY - offsetY) * smoothAlpha
    }

    /** Snapshot the current offsets as a pair — for callers that want both at once. */
    fun snapshot(): Pair<Float, Float> = offsetX to offsetY
}
