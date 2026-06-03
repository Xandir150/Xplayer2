package com.teleteh.xplayer2.data.glasses

import android.content.Context

/**
 * No-op VITURE controller for the `play` flavor.
 *
 * The `play` flavor ships WITHOUT the VITURE One SDK (its prebuilt `libsdk.so` is 4 KB-aligned and
 * is the only thing that blocks Google Play's 16 KB page-size requirement). This stub mirrors the
 * public API of the real controller in `src/full` so [GlassesController] compiles unchanged; on the
 * Play build VITURE 2D/3D switching is simply unavailable (XREAL and the rest work as usual).
 */
class VitureController(@Suppress("UNUSED_PARAMETER") appContext: Context) {

    fun interface StateListener {
        fun onChanged()
    }

    fun setListener(@Suppress("UNUSED_PARAMETER") l: StateListener?) {}

    fun isReady(): Boolean = false

    fun lastInitCode(): Int = INIT_IDLE

    fun init(): Int = INIT_IDLE

    fun is3dOn(): Boolean = false

    fun set3d(@Suppress("UNUSED_PARAMETER") enabled: Boolean): Boolean = false

    fun release() {}

    companion object {
        const val INIT_IDLE = Int.MIN_VALUE
    }
}
