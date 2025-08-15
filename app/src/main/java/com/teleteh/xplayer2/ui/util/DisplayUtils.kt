package com.teleteh.xplayer2.ui.util

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.content.pm.PackageManager

object DisplayUtils {
    private const val ULTRAWIDE_RATIO = 3.2f // ~32:10..32:9

    fun findUltraWideExternalDisplay(context: Context): Display? {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        // Prefer presentation displays
        val candidates = (dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .asList() + dm.displays.asList())
            .distinctBy { it.displayId }
            .filter { it.displayId != Display.DEFAULT_DISPLAY }
        for (d in candidates) {
            val size = android.graphics.Point()
            val metrics = android.util.DisplayMetrics()
            // Best effort; on modern devices, use getRealMetrics if possible via context.createDisplayContext(d)
            try {
                val ctx = context.createDisplayContext(d)
                val res = ctx.resources.displayMetrics
                val w = res.widthPixels
                val h = res.heightPixels.takeIf { it > 0 } ?: 1
                val ratio = w.toFloat() / h.toFloat()
                if (ratio >= ULTRAWIDE_RATIO) return d
            } catch (_: Throwable) {
                // Fallback using deprecated metrics
                d.getMetrics(metrics)
                val w = metrics.widthPixels
                val h = metrics.heightPixels.takeIf { it > 0 } ?: 1
                val ratio = w.toFloat() / h.toFloat()
                if (ratio >= ULTRAWIDE_RATIO) return d
            }
        }
        // If no ultrawide found, return the first available external display
        return candidates.firstOrNull()
    }

    fun startOnBestDisplay(activity: Activity, intent: Intent) {
        val ext = findUltraWideExternalDisplay(activity)
        if (ext != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ reliably supports launching activities on external displays
                try {
                    val opts = ActivityOptions.makeBasic().setLaunchDisplayId(ext.displayId)
                    activity.startActivity(intent, opts.toBundle())
                    return
                } catch (_: Throwable) {
                    // Fall through to primary display on failure
                }
            } else {
                // Android 10 and below: avoid setLaunchDisplayId to prevent system toast
                // TODO: implement Presentation-based external playback for API < 30
            }
        }
        // Fallback: just launch normally (primary display)
        activity.startActivity(intent)
    }

    fun startOnPrimaryDisplay(activity: Activity, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            activity.startActivity(intent, opts.toBundle())
        } else {
            activity.startActivity(intent)
        }
    }
}
