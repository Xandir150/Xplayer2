package com.teleteh.xplayer2.player

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * The remotes' vibration vocabulary.
 *
 * While the picture is on the glasses the phone can't be looked at, so every action is confirmed by
 * a *distinct* buzz and the hand learns them: one click for a discrete act, a light tick for a step
 * inside a continuous one (a volume notch, a gear change), a heavy click for something that took
 * effect on the glasses themselves. The two seek patterns differ in shape, not strength, so
 * direction is feelable: forward is a single 30 ms pulse, back is two short ones.
 *
 * Shared by both remotes so the same gesture never means two things in two places.
 */
class RemoteHaptics(context: Context) {

    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    fun click() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

    fun tick() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))

    fun heavy() = play(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))

    fun seekForward() = play(VibrationEffect.createWaveform(longArrayOf(0, 30), -1))

    fun seekBack() = play(VibrationEffect.createWaveform(longArrayOf(0, 25, 90, 25), -1))

    private fun play(effect: VibrationEffect) {
        // Some TV boxes driving the glasses have no vibrator at all, and a couple of OEMs throw
        // rather than no-op when one is asked for.
        try {
            vibrator?.vibrate(effect)
        } catch (_: Throwable) {
        }
    }
}
