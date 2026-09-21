package com.teleteh.xplayer2.player

/** GL-thread pacing. Depth/UI redraws must never feed the same paused video back to inference. */
internal class DepthReadbackGate {
    private var lastCaptureNanos: Long? = null

    fun shouldCapture(newDecoderFrame: Boolean, nowNanos: Long, intervalNanos: Long): Boolean {
        if (!newDecoderFrame || intervalNanos == Long.MAX_VALUE) return false
        val previous = lastCaptureNanos
        if (previous != null && nowNanos - previous < intervalNanos) return false
        lastCaptureNanos = nowNanos
        return true
    }

    fun reset() {
        lastCaptureNanos = null
    }
}
