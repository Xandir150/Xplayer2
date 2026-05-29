package com.teleteh.xplayer2.data.glasses

import android.content.Context
import android.util.Log
import com.viture.sdk.ArCallback
import com.viture.sdk.ArManager
import com.viture.sdk.Constants

/**
 * VITURE One SDK wrapper for 2D ⇄ 3D (SBS) switching on VITURE glasses — the VITURE counterpart
 * of the XREAL HID-MCU path in [GlassesController]. The SDK ([ArManager]) manages its own USB
 * device and permission prompt, so for VITURE we do NOT claim HID interfaces ourselves; we just
 * init the manager and drive `set3D` / `get3DState`.
 *
 * The `.aar` is fetched at build time (see `app/libs` + the release workflow). When it's absent
 * the VITURE path simply never runs — [GlassesController] only routes here for VITURE glasses.
 */
class VitureController(private val appContext: Context) {

    fun interface StateListener {
        /** Fired (on the SDK callback thread) when init completes or the 3D state changes. */
        fun onChanged()
    }

    private var manager: ArManager? = null
    private var callback: ArCallback? = null
    private var callbackRegistered = false
    @Volatile private var initCode: Int = INIT_IDLE
    private var listener: StateListener? = null

    fun setListener(l: StateListener?) { listener = l }

    /** True once the SDK reports a successful init (USB granted + device present). */
    fun isReady(): Boolean = initCode == Constants.ERROR_INIT_SUCCESS

    /** Last init result code (Constants.ERROR_INIT_*), or [INIT_IDLE] before any init. */
    fun lastInitCode(): Int = initCode

    /**
     * Initialise the SDK. On first use it may pop a USB-permission dialog; the authoritative init
     * result also arrives asynchronously via the callback (EVENT_ID_INIT). Returns the immediate
     * init code. Safe to call repeatedly (idempotent re-init for reconnect/resume).
     */
    fun init(): Int {
        val mgr = manager ?: ArManager.getInstance(appContext).also { manager = it }
        if (callback == null) {
            callback = object : ArCallback() {
                override fun onEvent(msgId: Int, event: ByteArray?, timestamp: Long) {
                    when (msgId) {
                        Constants.EVENT_ID_INIT -> {
                            if (event != null && event.size >= 4) initCode = leInt(event)
                            listener?.onChanged()
                        }
                        Constants.EVENT_ID_3D -> listener?.onChanged()
                    }
                }
                override fun onImu(timestamp: Long, imu: ByteArray?) {}
            }
        }
        registerCallback()
        try { mgr.setLogOn(false) } catch (_: Throwable) {}
        initCode = mgr.init()
        Log.i(TAG, "VITURE init() = $initCode")
        return initCode
    }

    private fun registerCallback() {
        val mgr = manager ?: return
        val cb = callback ?: return
        if (callbackRegistered) return
        try {
            mgr.registerCallback(cb)
            callbackRegistered = true
        } catch (e: Throwable) {
            Log.w(TAG, "registerCallback failed: ${e.message}")
        }
    }

    /** True if the glasses are currently in 3D (SBS) mode. */
    fun is3dOn(): Boolean =
        (manager?.get3DState() ?: Constants.STATE_OFF) == Constants.STATE_ON

    /**
     * Switch the glasses to 2D or 3D (SBS). Returns true if the command was accepted. The panel
     * needs a moment before [is3dOn] reflects the change — callers should also listen for the
     * EVENT_ID_3D callback for the settled state.
     */
    fun set3d(enabled: Boolean): Boolean {
        val mgr = manager ?: return false
        if (initCode != Constants.ERROR_INIT_SUCCESS) return false
        val r = try { mgr.set3D(enabled) } catch (e: Throwable) {
            Log.w(TAG, "set3D threw: ${e.message}"); return false
        }
        if (r != Constants.ERR_SET_SUCCESS) Log.w(TAG, "set3D($enabled) failed: code=$r")
        return r == Constants.ERR_SET_SUCCESS
    }

    fun release() {
        val mgr = manager
        val cb = callback
        if (mgr != null && cb != null && callbackRegistered) {
            try { mgr.unregisterCallback(cb) } catch (_: Throwable) {}
        }
        callbackRegistered = false
        try { mgr?.release() } catch (_: Throwable) {}
        manager = null
        callback = null
        initCode = INIT_IDLE
    }

    private fun leInt(b: ByteArray): Int =
        (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)

    companion object {
        private const val TAG = "VitureController"
        /** Sentinel for "init never attempted" (distinct from any Constants.ERROR_INIT_* value). */
        const val INIT_IDLE = Int.MIN_VALUE
    }
}
