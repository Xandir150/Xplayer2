package com.teleteh.xplayer2.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
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
 * Activation is decided per configure(): only when the input is 5.1/7.1 16-bit PCM and nobody
 * downstream can use the surround channels. Two parties can, and either one stands this processor
 * down:
 *  * [multichannelSinkAvailable] — a real surround sink (HDMI / USB-DAC / dock reporting ≥6
 *    channels) is attached, and the untouched 6/8-channel stream reaches it exactly as before;
 *  * [spatializerWillRender] — the platform's own spatialiser will render the track on this route
 *    (see [platformWillSpatialize]). Folding first would leave it a stereo pair and nothing to
 *    place, which is precisely how this processor was suppressing Android's spatial audio on XR
 *    glasses until it was measured.
 *
 * Neither is a blanket exemption: both are asked again at every configure(), so a route that stops
 * being able to use six channels gets the fold-down back.
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
    private val spatializerWillRender: (channelCount: Int, sampleRate: Int) -> Boolean =
        { _, _ -> false },
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
        if (spatializerWillRender(inputAudioFormat.channelCount, inputAudioFormat.sampleRate)) {
            // The platform will spatialise this track on the route it is going out on, and it
            // needs the surround channels to do it — folding here would hand it a stereo pair
            // and leave it nothing to place. Stand aside, exactly as for a real surround sink:
            // this is the same "somebody downstream can use all six" case, and the somebody is
            // the OS. Not a blanket removal — [platformWillSpatialize] asks about *this* format
            // on *this* route, so the moment the answer is no the fold-down is back.
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
         * True when any attached audio output can take a ≥6-channel PCM stream — the "real
         * surround rig" cases where passthrough is what the user wants.
         *
         * The burden of proof differs by transport:
         *  - HDMI / ARC / eARC: an empty channel-count list means "unspecified/flexible" and IS
         *    treated as capable — TVs and AV receivers legitimately negotiate that way.
         *  - USB / dock: capable ONLY if the device EXPLICITLY reports ≥6 channels. XR glasses
         *    (RayNeo/XREAL/VITURE) are USB audio with a plain stereo DAC, and many of them ship
         *    descriptors with no channel counts at all. Treating "empty" as capable here handed
         *    the 6-ch stream to the platform fold-down — the exact quiet-center/no-dialogue OEM
         *    path this processor exists to replace (field report: RayNeo Air 4 Pro on a Samsung
         *    S20 Ultra, Russian dialogue inaudible; randomly OK when USB enumeration lost the
         *    race and the glasses weren't visible yet at configure time). Stereo-by-default is
         *    the safe side of that race: a genuine USB surround DAC that reports nothing loses
         *    passthrough, but keeps correct, audible audio.
         *
         * Phone speakers, Bluetooth and wired headsets are stereo by definition — for them (and
         * stereo USB glasses) the fold-down activates.
         */
        fun multichannelSinkAvailable(context: Context): Boolean {
            val am = context.getSystemService(AudioManager::class.java) ?: return false
            return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
                val explicitlyMultichannel = d.channelCounts.any { it >= 6 }
                when (d.type) {
                    AudioDeviceInfo.TYPE_HDMI,
                    AudioDeviceInfo.TYPE_HDMI_ARC,
                    -> explicitlyMultichannel || d.channelCounts.isEmpty()
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_DOCK,
                    -> explicitlyMultichannel
                    else ->
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                            d.type == AudioDeviceInfo.TYPE_HDMI_EARC &&
                            (explicitlyMultichannel || d.channelCounts.isEmpty())
                }
            }
        }

        /**
         * The output channel mask a [channelCount] of decoded PCM would be played with, or null
         * for a count this processor has no business speaking for.
         *
         * Only the two layouts [onConfigure] already accepts: the mask is what the platform is
         * asked about, so answering for a layout we would not pass through anyway could only
         * produce a yes we then ignore.
         */
        fun spatialChannelMask(channelCount: Int): Int? = when (channelCount) {
            6 -> AudioFormat.CHANNEL_OUT_5POINT1
            8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
            else -> null
        }

        /**
         * True when the platform's own spatialiser will render this track on the route it is
         * about to go out on — i.e. when handing it the surround channels buys something.
         *
         * Measured on the owner's XREAL Air 2 Pro on a Galaxy S23 Ultra: the glasses enumerate as
         * `TYPE_USB_HEADSET`, which AOSP's `SpatializerHelper` maps to the BINAURAL mode, the
         * phone's effect supports binaural, and the routing update says in as many words
         * `can spatialize media 5.1:true on device: type:usb_headset`. So USB glasses are not the
         * excluded case one might assume from iOS, where AVFoundation disqualifies them for being
         * USB rather than a headphone jack — here they are treated as the headset they are.
         *
         * What the platform does NOT do is turn the scene with the head: `mIsHeadTrackingSupported`
         * is false for anything without its own motion sensors, which is every pair of glasses we
         * support (we read their IMU ourselves, over USB HID, and Android has no way to be handed
         * it). So this is a fixed virtual speaker bed, the same shape as the iOS side.
         *
         * Everything here is asked fresh per configure(): the answer depends on the current route,
         * and a route can change under a running clip.
         */
        fun platformWillSpatialize(context: Context, channelCount: Int, sampleRate: Int): Boolean {
            // Spatializer arrived in 12L. Below it the question has no answer and the fold-down
            // is simply what happens, as it always did.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return false
            val mask = spatialChannelMask(channelCount) ?: return false
            val spatializer =
                context.getSystemService(AudioManager::class.java)?.spatializer ?: return false
            // `isEnabled` is the user's switch in system settings, `isAvailable` is whether the
            // current output can be spatialised at all. Both, or there is nothing to stand aside
            // for — and `canBeSpatialized` below can still say no for this particular format.
            if (!spatializer.isEnabled || !spatializer.isAvailable) return false
            // The same attributes the player builds its ExoPlayer with; asking under different
            // ones would be asking about a different track than the one we are about to send.
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build()
            return spatializer.canBeSpatialized(attributes, format)
        }
    }
}
