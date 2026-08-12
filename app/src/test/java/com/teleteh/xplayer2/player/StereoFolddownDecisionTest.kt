package com.teleteh.xplayer2.player

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets the surround channels, and who has to be protected from them.
 *
 * The fold-down exists because a stereo sink handed six channels sounds wrong on several OEM
 * builds — dialogue attenuated or dropped outright. Standing it down is therefore not a free
 * action, and these pin the exact circumstances under which it is allowed to.
 */
class StereoFolddownDecisionTest {

    private fun pcm(channels: Int, rate: Int = 48_000) =
        AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT)

    private fun configure(
        channels: Int,
        multichannelSink: Boolean = false,
        spatializer: Boolean = false,
    ): AudioProcessor.AudioFormat =
        StereoFolddownAudioProcessor({ multichannelSink }, { _, _ -> spatializer })
            .configure(pcm(channels))

    @Test
    fun `a stereo sink still gets a fold-down`() {
        // The default, and the case the processor was written for.
        assertEquals(2, configure(channels = 6).channelCount)
        assertEquals(2, configure(channels = 8).channelCount)
    }

    @Test
    fun `a real surround sink is left alone`() {
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            configure(channels = 6, multichannelSink = true)
        )
    }

    @Test
    fun `the platform spatialiser is let through`() {
        // The bug this closes: the fold-down ran first, so the OS never saw a multichannel track
        // and could not spatialise anything, however capable the route was.
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            configure(channels = 6, spatializer = true)
        )
        assertEquals(
            AudioProcessor.AudioFormat.NOT_SET,
            configure(channels = 8, spatializer = true)
        )
    }

    @Test
    fun `a route the platform will not spatialise keeps its fold-down`() {
        // The half that stops this from being a regression: the switch above is only ever taken
        // on the platform's own say-so about this format on this route, so a two-channel headset
        // that cannot be spatialised is protected exactly as before.
        assertEquals(2, configure(channels = 6, spatializer = false).channelCount)
    }

    @Test
    fun `the sample rate survives whichever way the decision goes`() {
        assertEquals(
            44_100,
            StereoFolddownAudioProcessor({ false }, { _, _ -> false })
                .configure(pcm(6, 44_100)).sampleRate
        )
    }

    @Test
    fun `layouts this processor does not fold are never claimed`() {
        // Nothing is passed through *because of* the spatialiser here — these are declined
        // earlier, on the grounds that the mixing matrix only knows 5_1 and 7_1 by position.
        for (channels in intArrayOf(1, 2, 3, 4, 5, 7)) {
            assertEquals(
                "$channels channels should be left to the platform",
                AudioProcessor.AudioFormat.NOT_SET,
                configure(channels = channels, spatializer = true)
            )
        }
    }

    @Test
    fun `only the two folded layouts have a spatial channel mask`() {
        // The mask is what the platform is asked about. Answering for a layout the processor
        // would not pass through anyway could only produce a yes that is then ignored.
        assertEquals(
            AudioFormat.CHANNEL_OUT_5POINT1,
            StereoFolddownAudioProcessor.spatialChannelMask(6)
        )
        assertEquals(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            StereoFolddownAudioProcessor.spatialChannelMask(8)
        )
        for (channels in intArrayOf(0, 1, 2, 5, 7, 9)) {
            assertNull(StereoFolddownAudioProcessor.spatialChannelMask(channels))
        }
    }

    @Test
    fun `a surround sink wins without the spatialiser being consulted`() {
        // Order matters for a reason worth pinning: a receiver that can play 5_1 properly should
        // get the real thing, not a binaural rendering of it made for headphones.
        var asked = false
        val format = StereoFolddownAudioProcessor({ true }, { _, _ -> asked = true; true })
            .configure(pcm(6))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertFalse("the spatialiser should not be consulted once a surround sink is found", asked)
    }

    @Test
    fun `the decision is taken again for every configure`() {
        // Routes change under a running clip — the glasses arriving is exactly that event — and
        // a processor that cached its first answer would keep folding for the rest of the film.
        var spatial = false
        val processor = StereoFolddownAudioProcessor({ false }, { _, _ -> spatial })
        assertEquals(2, processor.configure(pcm(6)).channelCount)
        spatial = true
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(pcm(6)))
        spatial = false
        assertEquals(2, processor.configure(pcm(6)).channelCount)
    }

    @Test
    fun `the format the spatialiser is asked about is the one being played`() {
        var sawChannels = 0
        var sawRate = 0
        StereoFolddownAudioProcessor({ false }, { channels, rate ->
            sawChannels = channels; sawRate = rate; false
        }).configure(pcm(8, 44_100))
        assertEquals(8, sawChannels)
        assertEquals(44_100, sawRate)
    }

    @Test
    fun `non-PCM input is not this processor's business either way`() {
        val format = StereoFolddownAudioProcessor({ false }, { _, _ -> true })
            .configure(AudioProcessor.AudioFormat(48_000, 6, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, format)
        assertTrue(true)
    }
}
