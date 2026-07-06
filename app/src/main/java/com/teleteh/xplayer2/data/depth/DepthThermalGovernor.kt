package com.teleteh.xplayer2.data.depth

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * Thermal governor for the Lazy-3D depth pipeline.
 *
 * Continuous depth inference is Lazy 3D's dominant heat source: ~30 Hz on the NPU/GPU for the
 * length of a movie can push a phone into thermal throttling within minutes (beta report: thermal
 * warning at ~5 min, then the OS killed the app). This class watches [PowerManager]'s thermal
 * status (API 29+) plus, where supported (API 30+), the forward-looking
 * [PowerManager.getThermalHeadroom] forecast, and maps them onto a ladder of inference rates:
 *
 *   FULL 30 Hz → HALF 15 Hz → QUARTER 7.5 Hz → PAUSED (readback stops, last depth map frozen)
 *
 * Because the pipeline is latest-only end-to-end (see [DepthFrameWorker]), lowering the rate
 * costs nothing but depth-update latency: video keeps playing at full frame rate and the GL warp
 * simply re-uses the previous depth map, so trading depth Hz for temperature is almost invisible
 * (depth is a low-frequency signal and the estimator EMAs it anyway). PAUSED freezes the last
 * depth map rather than dropping to 2D — much better than letting the OS kill playback.
 *
 * Escalation is applied immediately; recovery steps down ONE level per [STEP_DOWN_MS] of the
 * sensors reading cooler than the current level, so the rate doesn't flap around a threshold
 * (PAUSED↔7.5 Hz flapping would visibly freeze/unfreeze depth).
 *
 * Main-thread only: the status listener is registered on the main executor and [tick] is called
 * from PlayerActivity's depth tick (main-looper handler), so no locking is needed.
 */
class DepthThermalGovernor(context: Context) {

    /** Depth-inference pacing, most severe last (enum order == severity order). */
    enum class Level(val readbackIntervalNanos: Long, val label: String) {
        FULL(33_000_000L, "30Hz"),
        HALF(66_000_000L, "15Hz"),
        QUARTER(133_000_000L, "7.5Hz"),
        PAUSED(Long.MAX_VALUE, "paused"),
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val mainExecutor = context.mainExecutor

    /** Effective level, combining both signals with the anti-flap hysteresis. */
    var level: Level = Level.FULL
        private set

    private var statusLevel: Level = Level.FULL
    private var headroomLevel: Level = Level.FULL
    // uptimeMillis when the sensors first read cooler than [level]; 0 = not currently cooler.
    private var coolerSinceMs: Long = 0L
    private var lastHeadroomPollMs: Long = 0L
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        val pm = powerManager ?: return
        if (listener != null) return
        val l = PowerManager.OnThermalStatusChangedListener { status ->
            statusLevel = levelForStatus(status)
            recompute()
        }
        try {
            pm.addThermalStatusListener(mainExecutor, l)
            listener = l
            // Seed from the current status — some OEM builds don't fire the callback on register.
            statusLevel = levelForStatus(pm.currentThermalStatus)
            recompute()
        } catch (e: Throwable) {
            // No thermal service on this build — stay at FULL forever, same as pre-governor.
            Log.w(TAG, "Thermal status listener unavailable: ${e.message}")
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        try { powerManager?.removeThermalStatusListener(l) } catch (_: Throwable) { }
    }

    /**
     * Periodic housekeeping, called from the ~33 ms depth tick: refresh the headroom forecast
     * (at most once per [HEADROOM_POLL_MS] — the API itself rejects sub-second polling) and let
     * a pending recovery step land. Returns the effective level so the caller can apply it.
     */
    fun tick(): Level {
        val pm = powerManager
        val now = SystemClock.uptimeMillis()
        if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            now - lastHeadroomPollMs >= HEADROOM_POLL_MS
        ) {
            lastHeadroomPollMs = now
            val h = try { pm.getThermalHeadroom(HEADROOM_FORECAST_S) } catch (_: Throwable) { Float.NaN }
            headroomLevel = levelForHeadroom(h)
        }
        recompute()
        return level
    }

    private fun recompute() {
        val target = maxOf(statusLevel, headroomLevel)
        when {
            target > level -> {
                // Heating up — shed load immediately.
                Log.i(TAG, "Lazy 3D thermal: ${level.label} → ${target.label} " +
                        "(status=${statusLevel.label}, headroom=${headroomLevel.label})")
                level = target
                coolerSinceMs = 0L
            }
            target < level -> {
                // Cooler — recover one step at a time, only after a sustained cool reading.
                val now = SystemClock.uptimeMillis()
                if (coolerSinceMs == 0L) {
                    coolerSinceMs = now
                } else if (now - coolerSinceMs >= STEP_DOWN_MS) {
                    val next = Level.entries[level.ordinal - 1]
                    Log.i(TAG, "Lazy 3D thermal: recovered ${level.label} → ${next.label}")
                    level = next
                    coolerSinceMs = 0L
                }
            }
            else -> coolerSinceMs = 0L
        }
    }

    private fun levelForStatus(status: Int): Level = when {
        status >= PowerManager.THERMAL_STATUS_SEVERE -> Level.PAUSED
        status == PowerManager.THERMAL_STATUS_MODERATE -> Level.QUARTER
        status == PowerManager.THERMAL_STATUS_LIGHT -> Level.HALF
        else -> Level.FULL
    }

    // Headroom is "fraction of the way to SEVERE at the forecast time" (1.0 = severe). It moves
    // well before the coarse status does on most devices, which is the whole point: back off
    // BEFORE the OS starts throttling/killing. NaN = unsupported/rate-limited → no opinion.
    private fun levelForHeadroom(h: Float): Level = when {
        h.isNaN() -> Level.FULL
        h >= 1.00f -> Level.PAUSED
        h >= 0.90f -> Level.QUARTER
        h >= 0.80f -> Level.HALF
        else -> Level.FULL
    }

    companion object {
        private const val TAG = "DepthThermalGovernor"
        private const val HEADROOM_POLL_MS = 10_000L
        private const val HEADROOM_FORECAST_S = 15
        private const val STEP_DOWN_MS = 45_000L
    }
}
