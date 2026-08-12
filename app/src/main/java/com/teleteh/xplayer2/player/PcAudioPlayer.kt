package com.teleteh.xplayer2.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import com.teleteh.xplayer2.data.network.PcAudioFormat

/**
 * Plays the PC's system audio out of whatever the phone's media output is — which, with XR glasses
 * plugged in, is the glasses' own USB speakers: Android routes media playback to a USB audio sink
 * automatically, so there is nothing to select here (audio-design §8).
 *
 * The design is deliberately thin around [PcAudioJitterBuffer], which owns every timing decision:
 *
 * * chunks arrive from the network thread and go straight into the buffer ([submit]);
 * * one feeder thread does nothing but `pull` a chunk and `write` it. The write blocks once the
 *   track's own buffer is full, so the DAC — not a timer — paces the pulls, which is precisely
 *   what makes the buffer's depth a measurement of the PC↔phone clock drift;
 * * silence from the buffer (prebuffering, an underrun, a drift insert) is written like any other
 *   chunk. Keeping the track running through a dropout is what lets audio resume without the
 *   ~100 ms cost of restarting it, and it holds the routing steady.
 *
 * Audio focus is deliberately NOT requested: PC Link is a screen mirror, this player is a
 * pass-through of the desktop's own mix, and the app's own focus handling belongs to the ExoPlayer
 * path (which is never alive at the same time — PC Link mode stands the whole file player down).
 * [USAGE_MEDIA]/[CONTENT_TYPE_MOVIE] match what the file player sets, so the phone's volume keys
 * and the glasses' own volume buttons act on this stream exactly as they do on a movie.
 */
class PcAudioPlayer(
    val format: PcAudioFormat,
    private val onError: (String) -> Unit = {}
) {

    val buffer = PcAudioJitterBuffer(format)

    private val lock = Any()
    private var track: AudioTrack? = null
    private var feeder: Thread? = null
    private var released = false

    /** Local mute: the user's tap takes effect here, before the wire round-trip does. */
    @Volatile
    var isMuted: Boolean = false
        private set

    /** Chunks thrown away because we are muted — the stream keeps flowing until the PC stops it. */
    @Volatile
    var mutedChunks: Long = 0L
        private set

    /**
     * Builds the track and starts the feeder. Returns false when the device refused the format,
     * in which case the caller should tell the PC to stop sending audio (§12: a client whose audio
     * path fails sends `set_audio {enabled:false}` and carries on with video).
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (released || track != null) return track != null
            val built = try {
                buildTrack()
            } catch (t: Throwable) {
                onError(t.message ?: t.javaClass.simpleName)
                null
            } ?: return false
            track = built
            try {
                built.play()
            } catch (t: Throwable) {
                built.release()
                track = null
                onError(t.message ?: t.javaClass.simpleName)
                return false
            }
            val thread = Thread({ feedLoop(built) }, "PcAudioFeeder").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
            feeder = thread
            thread.start()
            return true
        }
    }

    /** Queues one chunk from the wire. Called on the video reader coroutine. */
    fun submit(ptsUs: Long, payload: ByteArray) {
        if (released) return
        if (isMuted) {
            // Keep draining the socket and keep the buffer empty: a mute that let the queue grow
            // would replay a backlog of stale desktop audio the moment it was lifted.
            mutedChunks++
            return
        }
        buffer.push(ptsUs, payload)
    }

    /**
     * Mutes or unmutes locally and immediately. The wire-level `set_audio` is the caller's to
     * send; this is what makes the button feel instant regardless of the round trip.
     */
    fun setMuted(muted: Boolean) {
        if (isMuted == muted) return
        isMuted = muted
        if (muted) {
            buffer.reset()
            // Pause rather than stop: the track keeps its buffer and its route, so unmuting costs
            // one prebuffer (40 ms) instead of a track rebuild.
            synchronized(lock) { runCatching { track?.pause() } }
            synchronized(lock) { runCatching { track?.flush() } }
        } else {
            synchronized(lock) { runCatching { track?.play() } }
        }
    }

    /** Stops everything. The instance is not reusable. */
    fun release() {
        val thread: Thread?
        val doomed: AudioTrack?
        synchronized(lock) {
            if (released) return
            released = true
            thread = feeder
            doomed = track
            feeder = null
            track = null
        }
        thread?.interrupt()
        // The feeder can be parked inside a blocking write; pausing unblocks it promptly.
        runCatching { doomed?.pause() }
        runCatching { thread?.join(250) }
        runCatching { doomed?.stop() }
        runCatching { doomed?.release() }
    }

    /** Underruns the platform itself reported, which counts the ones our silence papered over. */
    val platformUnderruns: Int
        get() = synchronized(lock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { track?.underrunCount ?: 0 }.getOrDefault(0)
            } else {
                0
            }
        }

    // --- internals -----------------------------------------------------------------------------

    private fun buildTrack(): AudioTrack? {
        val channelMask = when (format.channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return null
        }
        val minBytes = AudioTrack.getMinBufferSize(format.rate, channelMask, ENCODING)
        if (minBytes <= 0) return null
        // Four chunks is the floor the design asks for; the platform minimum usually exceeds it,
        // and going below what the device wants is how a "low latency" track ends up glitching.
        val sizeBytes = maxOf(minBytes, 4 * buffer.chunkBytes)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(ENCODING)
                    .setSampleRate(format.rate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(sizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // A hint, not a promise — the platform grants a fast track only if the route and the
            // buffer size allow one. Asking costs nothing when it declines.
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val built = builder.build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            return null
        }
        return built
    }

    private fun feedLoop(track: AudioTrack) {
        val out = ByteArray(buffer.chunkBytes)
        while (!released && !Thread.currentThread().isInterrupted) {
            if (isMuted) {
                // Nothing to pace against while paused, so idle instead of spinning.
                try {
                    Thread.sleep(MUTED_IDLE_MS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            buffer.pull(out)
            val written = try {
                track.write(out, 0, out.size)
            } catch (_: IllegalStateException) {
                return
            }
            if (written < 0) {
                // ERROR_DEAD_OBJECT and friends: the route died under us. Say so once and stop —
                // the session's video is unaffected, which is the whole point of the audio lane.
                if (!released) onError("AudioTrack write failed ($written)")
                return
            }
        }
    }

    private companion object {
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val MUTED_IDLE_MS = 20L
    }
}
