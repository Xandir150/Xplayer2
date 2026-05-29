package com.teleteh.xplayer2.data.glasses

/**
 * Integrates the XREAL gyro stream into an absolute-ish head orientation (yaw / pitch / roll in
 * degrees) for world-anchored visuals — e.g. the screensaver platform that tries to stay put as
 * the user turns their head.
 *
 * Unlike [HeadPoseTracker] (which leaks back to centre, for a parallax nudge), this *keeps* the
 * integrated angle so a "fixed" object stays fixed. Pure gyro integration drifts slowly over time
 * (there's no magnetometer to anchor yaw); a continuously-tracked zero-rate bias keeps the resting
 * drift small. Good enough for a fun visual, not a real 6-DoF pose.
 *
 * Axes (verified on-device): gx = pitch rate, gy = roll rate, gz = yaw rate.
 *
 * Thread-safety: [accumulate] runs on the IMU reader thread; the angle getters / [hasSamples] are
 * read from the render thread. Volatile fields — a torn read at worst jitters one frame.
 */
class HeadOrientationTracker(
    /** Time constant (s) for the zero-rate-bias estimate; larger = slower bias adaptation. */
    private val biasTauSec: Float = 4.0f,
) {
    @Volatile var yawDeg: Float = 0f
        private set
    @Volatile var pitchDeg: Float = 0f
        private set
    @Volatile var rollDeg: Float = 0f
        private set

    private var biasX = 0f
    private var biasY = 0f
    private var biasZ = 0f
    private var lastNanos = 0L
    @Volatile private var samples = 0L

    /** True once at least one IMU sample has arrived — i.e. telemetry is actually flowing. */
    fun hasSamples(): Boolean = samples > 0L

    fun reset() {
        yawDeg = 0f; pitchDeg = 0f; rollDeg = 0f
        biasX = 0f; biasY = 0f; biasZ = 0f
        lastNanos = 0L; samples = 0L
    }

    fun accumulate(gxDegSec: Float, gyDegSec: Float, gzDegSec: Float, tNanos: Long) {
        samples++
        val prev = lastNanos
        lastNanos = tNanos
        if (prev == 0L) return
        val dt = (tNanos - prev) / 1_000_000_000f
        if (dt <= 0f || dt > 0.25f) return

        // Track and subtract the gyro zero-rate bias so a still head doesn't run away.
        val step = (dt / biasTauSec).coerceIn(0f, 1f)
        biasX += (gxDegSec - biasX) * step
        biasY += (gyDegSec - biasY) * step
        biasZ += (gzDegSec - biasZ) * step

        pitchDeg = (pitchDeg + (gxDegSec - biasX) * dt).coerceIn(-90f, 90f)
        rollDeg = (rollDeg + (gyDegSec - biasY) * dt).coerceIn(-90f, 90f)
        var yaw = yawDeg + (gzDegSec - biasZ) * dt
        if (yaw > 180f) yaw -= 360f else if (yaw < -180f) yaw += 360f
        yawDeg = yaw
    }
}
