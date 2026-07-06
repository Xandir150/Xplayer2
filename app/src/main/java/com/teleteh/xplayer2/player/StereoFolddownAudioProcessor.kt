package com.teleteh.xplayer2.player

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * In-app 5.1/7.1 → stereo fold-down, VLC-style.
 *
 * Why this exists: Media3 never downmixes PCM — a 6-channel stream (our FFmpeg extension decodes
 * AC-3/E-AC-3/DTS to genuine 6ch PCM) goes to a 6-channel AudioTrack and the ANDROID PLATFORM
 * folds it down on stereo outputs. That platform fold-down is quiet by design (center at −3 dB
 * plus overall anti-clip scaling), FFmpeg's dialnorm attenuation stacks on top, and several OEM
 * audio pipelines (Samsung with Dolby processing off, MTK builds) attenuate or drop the center
 * channel outright — and in a 5.1 movie mix the center IS the dialogue. Field reports: "speech
 * inaudible, only music and effects" / "everything too quiet" on phone speakers, while glasses
 * (no fold-down) sound fine. VLC avoids all of this by downmixing itself — so do we now.
 *
 * Activation is decided per configure(): only when the input is 5.1/7.1 16-bit PCM AND
 * [multichannelSinkAvailable] says there is NO multichannel-capable output attached. When a
 * multichannel sink (HDMI / USB-DAC / dock reporting ≥6 channels) is present, the processor stays
 * inactive and the untouched 6/8-channel stream reaches it exactly as before — nobody with real
 * surround output loses anything.
 *
 * Mixing matrix (ITU-R BS.775 style, LFE omitted as per ATSC practice):
 *   L = FL + 0.707·FC + 0.707·Ls(+0.707·Lb for 7.1)
 *   R = FR + 0.707·FC + 0.707·Rs(+0.707·Rb for 7.1)
 * scaled by [MASTER_GAIN] (partial normalization: full 1/(1+2·0.707) normalization is what makes
 * platform downmixes so quiet; we accumulate in float and hard-clamp instead, trading rare
 * loud-peak saturation for dialogue you can actually hear).
 *
 * Channel order (both FFmpeg's native 5.1(side)/5.1(back) and AOSP's canonical order):
 *   6ch: FL FR FC LFE SL/BL SR/BR      8ch: FL FR FC LFE BL BR SL SR
 */
@UnstableApi
class StereoFolddownAudioProcessor(
    private val multichannelSinkAvailable: () -> Boolean,
) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // The sink's built-in chain (ToInt16PcmAudioProcessor) runs before user processors, so
        // 16-bit is what we should see; anything else — stay out of the way.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        // Only the layouts we can fold correctly by position (see channel-order note above).
        // Rarer 3/4/5-channel layouts keep the pre-existing platform behavior.
        if (inputAudioFormat.channelCount != 6 && inputAudioFormat.channelCount != 8) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (multichannelSinkAvailable()) {
            // A real surround sink is attached — hand the full multichannel stream through.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inChannels = inputAudioFormat.channelCount
        val frames = inputBuffer.remaining() / (2 * inChannels)
        if (frames == 0) return
        val out = replaceOutputBuffer(frames * 2 * 2)
        val src = inputBuffer.asShortBuffer()
        var base = 0
        repeat(frames) {
            val fl = src.get(base).toFloat()
            val fr = src.get(base + 1).toFloat()
            val fc = src.get(base + 2).toFloat() * SIDE_GAIN
            // base+3 = LFE, intentionally dropped (standard fold-down practice; folding it in
            // mostly adds mud on small speakers).
            var l = fl + fc + src.get(base + 4).toFloat() * SIDE_GAIN
            var r = fr + fc + src.get(base + 5).toFloat() * SIDE_GAIN
            if (inChannels == 8) {
                l += src.get(base + 6).toFloat() * SIDE_GAIN
                r += src.get(base + 7).toFloat() * SIDE_GAIN
            }
            out.putShort(clampToShort(l * MASTER_GAIN))
            out.putShort(clampToShort(r * MASTER_GAIN))
            base += inChannels
        }
        inputBuffer.position(inputBuffer.position() + frames * 2 * inChannels)
        out.flip()
    }

    private fun clampToShort(v: Float): Short =
        v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()

    companion object {
        private const val SIDE_GAIN = 0.7071f   // −3 dB for center + surrounds
        private const val MASTER_GAIN = 0.59f   // partial normalization — see class doc

        /**
         * True when any attached audio output can take a ≥6-channel PCM stream — HDMI (incl.
         * ARC/eARC), USB audio devices/docks, i.e. the "real surround rig" cases. An empty
         * channel-count list on those device types means "unspecified/flexible", which we treat
         * as capable: worst case the platform folds down exactly as it did before this processor
         * existed. Phone speakers, Bluetooth and wired headsets are stereo by definition — for
         * them (and stereo USB glasses) the fold-down activates.
         */
        fun multichannelSinkAvailable(context: Context): Boolean {
            val am = context.getSystemService(AudioManager::class.java) ?: return false
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                val typeOk = when (d.type) {
                    AudioDeviceInfo.TYPE_HDMI,
                    AudioDeviceInfo.TYPE_HDMI_ARC,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_DOCK,
                    -> true
                    else ->
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            d.type == AudioDeviceInfo.TYPE_HDMI_EARC
                }
                typeOk && (d.channelCounts.isEmpty() || d.channelCounts.any { it >= 6 })
            }
        }
    }
}
