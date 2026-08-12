    package com.teleteh.xplayer2.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.media.audiofx.LoudnessEnhancer
import android.database.Cursor
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import android.view.Display
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.media3.ui.DefaultTrackNameProvider
import android.graphics.Typeface
import android.widget.ScrollView
import android.util.TypedValue
import com.google.android.material.button.MaterialButton
import com.teleteh.xplayer2.MainActivity
import com.teleteh.xplayer2.R
import com.teleteh.xplayer2.data.RecentEntry
import com.teleteh.xplayer2.data.RecentStore
import androidx.appcompat.app.AlertDialog
import com.teleteh.xplayer2.data.depth.DepthEstimator
import com.teleteh.xplayer2.data.depth.DepthFrameWorker
import com.teleteh.xplayer2.data.depth.DepthModelManager
import com.teleteh.xplayer2.data.depth.DepthThermalGovernor
import com.teleteh.xplayer2.data.glasses.GlassesController
import com.teleteh.xplayer2.data.glasses.GlassesProtocol
import com.teleteh.xplayer2.data.network.PairingFailure
import com.teleteh.xplayer2.data.network.PcAudioFormat
import com.teleteh.xplayer2.data.network.PcLinkAuth
import com.teleteh.xplayer2.data.network.PcLinkClient
import com.teleteh.xplayer2.data.network.PcLinkPairingStore
import com.teleteh.xplayer2.data.network.PcLinkState
import com.teleteh.xplayer2.data.network.PcLinkStreamConfig
import com.teleteh.xplayer2.data.network.PcVideoFrame
import com.teleteh.xplayer2.ui.network.PcConnectActivity
import com.teleteh.xplayer2.ui.pclink.PcLinkRemoteActivity
import com.teleteh.xplayer2.ui.pclink.PcLinkRemotePolicy
import com.teleteh.xplayer2.ui.util.DisplayUtils
import com.teleteh.xplayer2.BuildConfig
import com.teleteh.xplayer2.util.VideoStreamExtractor
import com.teleteh.xplayer2.util.WebSourceClassifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlayerActivity : AppCompatActivity(), GlassesStage.Occupant, PcLinkSession.Host {

    companion object {
        /**
         * Whether the desktop is hung in the world instead of filling the panel.
         *
         * Off until the spaces mode exists — see [tryShowExternalPresentation]. This is a build
         * constant rather than a setting on purpose: a half-built mode behind a switch is a
         * support burden, and the honest state today is "not yet".
         */
        const val SPACES_MODE = false

        // Spatial audio. The same key name the iOS build uses, so the two stay recognisably the
        // same setting when someone reads either side — though the defaults differ on purpose:
        // iOS has to synthesise a scene itself and asks first, while here the platform is doing
        // it and has already said it can, so the honest default is on.
        const val SPATIAL_PREFS = "audio"
        const val SPATIAL_KEY = "spatial_audio"
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val EXTRA_TITLE = "title"
        // Durable identity for the Recent list when the played URI is ephemeral (e.g. a Yandex Disk
        // signed download href that expires). Recents are keyed/restored by this instead.
        const val EXTRA_RECENT_URI = "recent_uri"
        // Pre-resolved selectable qualities (parallel String[] arrays, highest first) — Yandex Disk
        // hands its per-quality HLS renditions straight to the quality menu (no in-player extraction).
        const val EXTRA_STREAM_LABELS = "stream_labels"
        const val EXTRA_STREAM_URLS = "stream_urls"

        // Lazy-3D depth→stereo strength (max per-eye UV shift as a fraction of frame width).
        // Single tuning knob: higher = more "pop" but a wider disocclusion smear at object
        // edges; lower = gentler 3D with cleaner edges. iw3 default-ish is ~0.02; we run a bit
        // softer for comfort on a synthesised (mono-depth) pair.
        const val LAZY3D_DIVERGENCE = 0.013f
        // Default loudness boost (millibels; +6 dB) for new ONLINE clips — online streams are often
        // quiet cinema mixes (~ -20 LUFS vs ~ -14 of streaming apps). User-overridable per clip.
        const val DEFAULT_ONLINE_BOOST_MB = 600

        // Current instance for remote control access. Newest of the live ones rather than "the
        // last one created": a second PlayerActivity is routine (see LiveInstances and
        // GlassesStage), and the throwaway one the playback notification's own body intent creates
        // used to take this with it when it finished — leaving the notification's Stop button
        // stopping nothing and the film remote closing itself over a player that was still there.
        private val liveInstances = LiveInstances<PlayerActivity>()
        val currentInstance: PlayerActivity? get() = liveInstances.newest

        // --- PC Link ---
        // Fallbacks only: the ports actually used are the ones the server advertised in its
        // discovery reply and carried on the intent (the spec forbids hardcoding them).
        private const val DEFAULT_CONTROL_PORT = 48631
        private const val DEFAULT_VIDEO_PORT = 48632
        private const val PCLINK_DEBUG_INTERVAL_MS = 500L
        // How long a client outlives its session so its farewell `set_audio` can leave. See
        // [disconnectPcLink].
        private const val PCLINK_FAREWELL_MS = 300L
    }

    // ------------------------------------------------------------------------------------------
    // GlassesStage.Occupant — one screen, one thing on it
    // ------------------------------------------------------------------------------------------

    override val glassesUse: GlassesStage.Use
        get() = when {
            isPcLinkMode -> GlassesStage.Use.PC_LINK
            // `sourceUri` outlives the ExoPlayer across a backgrounding (onStop releases the player
            // and onStart rebuilds it), so it — not `player` — is what "this activity is showing a
            // film" actually means.
            sourceUri != null -> GlassesStage.Use.LOCAL_VIDEO
            else -> GlassesStage.Use.NOTHING
        }

    /**
     * Something else is taking the glasses. End what this activity is showing and get out of the
     * way — including the window, because a player left in the back stack with nothing to play is
     * a screen the user can return to that lies about what is happening.
     */
    override fun releaseGlasses() {
        if (isPcLinkMode) exitPcLink()
        stopLocalPlayback()
        dismissPresentation()
        finish()
    }

    /**
     * Tears down the ExoPlayer side and everything hanging off it. Not a lifecycle callback: this
     * is "there is no longer a film here", which `onStop` deliberately does not mean.
     */
    private fun stopLocalPlayback() {
        // The position is worth keeping even when the film is being taken away rather than closed.
        saveProgress()
        // Same order and the same reason as onNewIntent's teardown: the LoudnessEnhancer goes
        // first, because the system has been seen leaving the effect alive after its audio session
        // dies and locking the glasses' USB output for every other app.
        releaseLoudnessEnhancer()
        try { player?.clearVideoSurface() } catch (_: Throwable) { }
        try { player?.release() } catch (_: Throwable) { }
        player = null
        trackSelector = null
        resolvedStreamUri = null
        streamVariants = emptyList()
        selectedVariantIndex = 0
        currentResolvedTitle = null
        sourceUri = null
        recentKeyUri = null
        stopPlaybackService()
    }

    private fun navigateBackToPrimary() {
        // PC Link goes back where it came from — the PC picker — not to the media library.
        if (isPcLinkMode) {
            exitPcLink()
            val pcIntent = Intent(this, PcConnectActivity::class.java)
            pcIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            DisplayUtils.startOnPrimaryDisplay(this, pcIntent)
            dismissPresentation()
            finish()
            return
        }
        // Bring MainActivity to foreground on primary display then finish this player
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        DisplayUtils.startOnPrimaryDisplay(this, intent)
        // If we explicitly leave playback, dismiss the external presentation so the second screen clears
        dismissPresentation()
        finish()
    }

    private lateinit var playerView: PlayerView
    private var glView: OuToSbsGlView? = null
    private var glSurface: Surface? = null
    private var presentationSurface: Surface? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var presentation: ExternalPlayerPresentation? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    // Display id the Presentation is currently shown on (-1 = none). Used to tell apart "our
    // external panel went away" from any other display event.
    private var presentationDisplayId: Int = -1
    private val uiHandler = Handler(Looper.getMainLooper())
    private var sourceUri: Uri? = null
    // Optional durable recents key (EXTRA_RECENT_URI) used when the play URI is ephemeral.
    private var recentKeyUri: Uri? = null
    private var titleCenterView: TextView? = null
    private var currentResolvedTitle: String? = null
    private var btnSbsRef: MaterialButton? = null
    private var btnShiftRef: MaterialButton? = null
    private var btnResizeModeRef: MaterialButton? = null
    // 0=Auto, 1=16:9, 2=4:3, 3=21:9, 4=32:9, 5=1:1, 6=2.39:1
    private var resizeMode: Int = 0

    // Audio gain via LoudnessEnhancer. The boost level is per-clip (restored from / saved to
    // the clip's RecentEntry), held here for the currently playing item.
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var volumeBoostMb: Int = 0

    // "Lazy 3D" — depth-based 2D->3D: TFLite depth estimation + GL backward-warp, gated by a
    // single toggle. Works on any device once the depth model is installed (see DepthEstimator).
    private var lazy3dEnabled: Boolean = false
    private val poseUiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var depthEstimator: DepthEstimator? = null
    private var depthWorker: DepthFrameWorker? = null
    // Effective stereo divergence for the current Lazy-3D session: the base LAZY3D_DIVERGENCE
    // scaled by the active model's divergenceScale (set when the estimator loads).
    private var lazy3dDivergence = LAZY3D_DIVERGENCE
    private var pendingDepthTick: Runnable? = null
    // Thermal-aware depth throttling (see DepthThermalGovernor): lives exactly as long as the
    // depth worker; the depth tick polls it and applies the rate to the active GL view.
    private var depthThermal: DepthThermalGovernor? = null
    // Governor level last seen by the tick (escalation-edge detection for the user hint) and
    // when the hint was last shown, so a bouncing thermal sensor can't spam toasts.
    private var depthThermalSeen = DepthThermalGovernor.Level.FULL
    private var depthThermalToastMs = 0L
    // Timestamp of the depth map most recently pushed to the GL view by the depth tick. Reset on
    // view switches so the (possibly thermally-frozen) latest map is re-sent to the new view.
    private var depthTickLastTs = 0L
    private var depthDownloadJob: kotlinx.coroutines.Job? = null
    // Startup runs off the main thread (GPU model load is slow), so this flags the in-flight
    // phase and drives the remote's "starting…" indicator.
    @Volatile private var depthStarting: Boolean = false
    // Lazy-3D startup generation, bumped by every stopLazy3d() (main thread only). An async
    // startup captures it at launch and discards itself if a stop/restart happened while the
    // model was loading — without this, a fast off→on toggle ended with TWO estimator/worker
    // pairs alive and one GPU delegate leaked per occurrence.
    private var lazy3dGen = 0
    // When Lazy 3D was last switched on (System.nanoTime). Used to time-bound the "Starting"
    // state so the remote's button can't stay disabled forever if startup stalls.
    @Volatile private var lazy3dEnabledAtNanos: Long = 0L
    private val lazy3dStartupGraceMs = 4000L
    // Model-download progress (0..99) shown on the Lazy-3D button; -1 when not downloading.
    @Volatile private var lazy3dDownloadPct: Int = -1

    /** Coarse Lazy-3D lifecycle for the remote UI: off / spinning up / live. */
    enum class Lazy3dStatus { Off, Starting, Active }

    private var audioMenuRoot: android.widget.FrameLayout? = null
    private var audioMenuCenter: LinearLayout? = null
    private var audioMenuLeft: LinearLayout? = null
    private var audioMenuRight: LinearLayout? = null
    // Debug flag: whether vertical SBS shift is enabled (not persisted)
    private var sbsShiftEnabled: Boolean = false
    // No need for reentrancy guard when we don't call show/hide inside listener
    private var lastVideoWidth: Int = 0
    private var lastVideoHeight: Int = 0
    // Source layout detected from container metadata (Media3 Format.stereoMode).
    // null means "no explicit metadata; fall back to aspect-ratio heuristic".
    private var detectedSourceStereoMode: Int? = null
    // Desired GL render config — the single source of truth, pushed to whichever glView is
    // active (the Presentation's when glasses are connected, otherwise the local one).
    private var renderSourceIsSbs: Boolean = false
    private var renderDuplicateMono: Boolean = false

    // 3-state stereo mode for the current clip. Auto-detection sets it from the source layout;
    // the SBS button cycles it manually (needed for Full-SBS/Full-OU clips that are 1920x1080
    // and thus indistinguishable from 2D by resolution). Manual choices persist per Recent item.
    private enum class StereoMode { Off, Ou, Sbs;
        fun toInt() = ordinal
        companion object { fun fromInt(v: Int) = entries.getOrElse(v) { Off } }
    }
    private var stereoMode: StereoMode = StereoMode.Off
    // Becomes true once the user has chosen an SBS state explicitly — either by tapping the
    // SBS toolbar button, or because a saved Recent entry already had sbsEnabled set. While
    // false, auto-detection is allowed to flip the SBS toggle on OU sources.
    private var sbsExplicitlyConfigured: Boolean = false

    // Active video output pipeline. GL is needed when we have to transform the picture
    // (OU→SBS, SBS source split, mono→SBS duplicate, external Presentation). For plain
    // mono playback that doesn't need any transform we route video straight into
    // PlayerView's SurfaceView, which skips an extra GL pass — lower CPU/GPU and
    // better colour fidelity (especially for HDR / wide-gamut content).
    private enum class VideoPipeline { GL, DIRECT }
    private var currentPipeline: VideoPipeline = VideoPipeline.GL
    // Foreground service for external playback
    private var playbackService: PlaybackService? = null
    private var serviceBound = false
    // True after a successful bindService() call, even before (or without) onServiceConnected —
    // the unbind obligation starts at the request, not at the connect.
    private var serviceBindRequested = false
    // True after a successful startForegroundService() call — the stop obligation exists even if
    // the companion bindService() failed outright (see stopPlaybackService's stopService fallback).
    private var serviceStartRequested = false
    // The exact GlassesController instance onCreate registered on (null if none existed then).
    private var acquiredGlasses: GlassesController? = null

    // --- Audio-route watcher for the 5.1→stereo fold-down (see StereoFolddownAudioProcessor) ---
    // The processor decides downmix-vs-passthrough once per sink configure(); if the set of
    // multichannel-capable outputs changes MID-clip (dock/HDMI/USB-DAC plugged or pulled), that
    // decision is stale — briefly toggle the audio renderer so the sink reconfigures and the
    // processor re-decides for the new route. ~100 ms of silence on a plug event is fine.
    private var lastMultichannelSink = false
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>) = onAudioRouteMaybeChanged()
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>) = onAudioRouteMaybeChanged()
    }

    // The same staleness applies to the platform-spatialiser answer, and for a sharper reason:
    // plugging the glasses in is exactly the event that turns it from false to true, and it is
    // also exactly the moment the user is about to watch something. Tracked separately from the
    // multichannel-sink flag because the two can move independently — glasses are a stereo sink
    // that can be spatialised, a dock is a multichannel sink that cannot be.
    private var lastSpatialAnswer = false

    /**
     * Whether the fold-down should stand aside and let the platform spatialise this track.
     *
     * **Automatic, and safe because of what the second condition is.** The worry with passing
     * six channels on is the two-channel sink that then has to cope; but `canBeSpatialized` is
     * not a guess about the sink, it is the platform stating it will render this exact format on
     * this exact route. Where it says no — a phone speaker, a plain stereo DAC, an old Android —
     * the fold-down happens exactly as before. So there is no route on which this can quietly
     * hand somebody a stream they cannot play.
     *
     * **Off by default, on the evidence.** Listened to on the glasses against a 5.1 file with each
     * channel announced in its own channel: front left, centre and front right were all that could
     * be told apart, the surrounds were not placed and the LFE was inaudible — they are a stereo
     * headset, and the platform's binaural rendering has nothing to work with. So the fold-down,
     * which exists because a mis-handled 5.1 costs dialogue on some OEM builds, stays the default;
     * turning it off buys a measured nothing and risks that on devices nobody here can test.
     *
     * The switch stays because the machinery is sound and cheap — a different pair of glasses, or
     * a phone whose spatialiser is worth more, only has to flip a boolean.
     */
    private fun spatialAudioWanted(channelCount: Int, sampleRate: Int): Boolean =
        isSpatialAudioEnabled() &&
            StereoFolddownAudioProcessor.platformWillSpatialize(
                applicationContext, channelCount, sampleRate
            )

    private fun isSpatialAudioEnabled(): Boolean =
        getSharedPreferences(SPATIAL_PREFS, MODE_PRIVATE).getBoolean(SPATIAL_KEY, false)

    private fun setSpatialAudioEnabled(on: Boolean) {
        getSharedPreferences(SPATIAL_PREFS, MODE_PRIVATE).edit().putBoolean(SPATIAL_KEY, on).apply()
        // The sink decided fold-vs-passthrough at its last configure(); the switch has just
        // changed that answer, so put it through the same brief renderer toggle a route change
        // uses rather than making the user seek or re-open the film.
        reconfigureAudioSink()
    }

    /** ~100 ms of silence, in exchange for the sink re-deciding on the current facts. */
    private fun reconfigureAudioSink() {
        val exo = player ?: return
        val params = exo.trackSelectionParameters
        exo.trackSelectionParameters =
            params.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        Handler(mainLooper).postDelayed({
            player?.let { p ->
                p.trackSelectionParameters =
                    p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).build()
            }
        }, 100)
    }

    private fun onAudioRouteMaybeChanged() {
        val mc = StereoFolddownAudioProcessor.multichannelSinkAvailable(applicationContext)
        // Asked with a 5.1 at the commonest rate purely as a probe of the route: this is a
        // "has anything changed" test, and the sink asks again for real with the actual format
        // when it reconfigures below.
        val spatial = isSpatialAudioEnabled() &&
            StereoFolddownAudioProcessor.platformWillSpatialize(applicationContext, 6, 48_000)
        if (mc == lastMultichannelSink && spatial == lastSpatialAnswer) return
        lastMultichannelSink = mc
        lastSpatialAnswer = spatial
        reconfigureAudioSink()
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            playbackService = (binder as? PlaybackService.LocalBinder)?.getService()
            serviceBound = true
            // Start foreground if we have a player and presentation
            if (presentation != null) {
                player?.let { playbackService?.startForegroundPlayback(it, currentResolvedTitle) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }
    // Resolved stream URL (may differ from sourceUri for ok.ru, vkvideo, etc.)
    private var resolvedStreamUri: Uri? = null
    // For YouTube (separate video+audio adaptive streams): the audio URL to merge with the video, and
    // any extra request headers (the YouTube client UA) the stream CDN needs.
    private var resolvedAudioUri: Uri? = null
    private var extractedHeaders: Map<String, String>? = null
    private var extractedTitle: String? = null
    // Flag to prevent premature player initialization during stream extraction
    private var isExtractingStream: Boolean = false

    // Selectable stream qualities for the current source (VK/OK.ru), highest first. Empty for
    // local files / single-URL streams — the quality picker is hidden unless this has ≥2 entries.
    // The default selection is index 0 (the highest), which is what already plays.
    private var streamVariants: List<VideoStreamExtractor.StreamVariant> = emptyList()
    private var selectedVariantIndex: Int = 0
    private var btnQualityRef: ImageButton? = null

    // --- PC Link (M1): the PC streams its desktop here, so there is no media item and no
    // ExoPlayer at all — a socket feeds MediaCodec, which renders into the same GL surface the
    // file path uses. Everything below is null/0 in every other mode; [isPcLinkMode] is the switch.
    private var pcLinkHost: String? = null
    private var pcLinkControlPort: Int = 0
    private var pcLinkVideoPort: Int = 0
    private var pcLinkServerName: String = ""
    /** The PC's identity fingerprint, when [PcConnectActivity] paired or re-authenticated with it. */
    private var pcLinkServerId: String? = null
    /** A `unknown_client` refusal waiting to hand the user back to [PcConnectActivity]. */
    private var pcLinkRepairPending = false
    // Built on demand from the PC Link client's IO thread (see [pcLinkAuth]), read from there only.
    @Volatile private var pcLinkStore: PcLinkPairingStore? = null
    private var pcLinkClient: PcLinkClient? = null
    // Read from the network reader thread (onVideoFrame), so volatile.
    @Volatile private var pcDecoder: PcStreamDecoder? = null
    private var pcLinkConfig: PcLinkStreamConfig? = null
    private var pcLinkSourceIsSbs = false
    private var pcVideoWidth = 0
    private var pcVideoHeight = 0
    // The PC's system audio, when the server negotiated any. Null the whole time `config.audio`
    // is absent, which is exactly the pre-audio behaviour. Read from the network reader thread.
    @Volatile private var pcAudio: PcAudioPlayer? = null
    /** The user's mute choice, kept across reconnects and format changes within one session. */
    private var pcAudioMuted = false
    /**
     * The PC's own answer to "do you have sound for us", as its last `config` gave it. A separate
     * fact from [pcAudio] being alive, because our own mute is acknowledged by a `config` with no
     * audio and so destroys the track — see [PcLinkAudioRouting], which owns both rules.
     */
    private var pcAudioOffered = false
    private var pcStatusView: TextView? = null
    private var pcAudioButton: TextView? = null
    private var pcDebugView: TextView? = null
    private var pcDebugTicker: Runnable? = null
    /** pts of the last frame the decoder actually rendered, for the A/V skew readout. */
    @Volatile private var pcLastRenderedPtsUs = 0L
    // Latency estimate. The server's pts_us is on ITS monotonic clock, so an absolute delay is not
    // computable; the running minimum of (our clock - pts) is the best-case one-way delay of this
    // session, and what we show is how far above that floor we currently are.
    @Volatile private var pcLatencyFloorUs = Long.MAX_VALUE
    @Volatile private var pcLatencyUs = 0L
    private var pcStatsAtMs = 0L
    private var pcStatsFrames = 0L
    private var pcStatsBytes = 0L
    private var pcStatsFps = 0f
    private var pcStatsMbps = 0f
    /** Identifies this session to readers that keep history across readings — see [PcLinkSession]. */
    private var pcSessionId = 0L
    /** Last state the client reported, so the tab can say "connecting" rather than draw a zero. */
    private var pcLinkLink = PcLinkSession.Link.CONNECTING

    /** True once this activity was launched (or re-launched) with the PC Link extras. */
    private val isPcLinkMode: Boolean get() = pcLinkHost != null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveInstances.add(this)
        // Scoped to the activity's whole life, not to onStart/onStop: a player whose picture is on
        // the glasses keeps running while this window is stopped (see onStop), and that is exactly
        // the instance a later claim has to be able to find.
        GlassesStage.register(this)
        // Same scope, same reason: the PC-Mirror tab reads the session's numbers off this instance
        // while its own window is stopped behind the phone's UI.
        PcLinkSession.register(this)

        // Ensure edge-to-edge and cutout mode for Android 15+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        
        setContentView(R.layout.activity_player)

        // Keep the device awake during a playback session so the external (glasses) DisplayPort
        // output doesn't drop when the phone would otherwise time out and sleep. Phone-only
        // playback benefits too. The remote dims the phone to black on top of this.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Handle system back via dispatcher to move app back to primary display
        onBackPressedDispatcher.addCallback(this) {
            navigateBackToPrimary()
        }
        
        playerView = findViewById(R.id.playerView)
        glView = findViewById(R.id.glView)
        glView?.setSbsEnabled(getStereoSbs())
        // Default: do not swap eyes
        glView?.setSwapEyes(false)
        // PlayerView's internal SurfaceView is the direct-output target. Initial visibility
        // is decided by applyVideoPipeline() once we know the player + flags.
        playerView.videoSurfaceView?.visibility = View.GONE
        // Inflate custom overlay controls into PlayerView's overlay container
        val overlay =
            playerView.findViewById<android.widget.FrameLayout>(androidx.media3.ui.R.id.exo_overlay)
        LayoutInflater.from(this).inflate(R.layout.player_controls_overlay, overlay, true)
        overlay.visibility = View.GONE
        // Ensure overlay is above the built-in controller
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            ?.let { controllerView ->
                val base = if (controllerView.elevation != 0f) controllerView.elevation else 2f
                overlay.elevation = base + 2f
            }
        overlay.bringToFront()
        val btnBack = overlay.findViewById<MaterialButton>(R.id.btnBack)
        val btnSbs = overlay.findViewById<MaterialButton>(R.id.btnSbs)
        val btnShift = overlay.findViewById<MaterialButton>(R.id.btnShift)
        val btnResizeMode = overlay.findViewById<MaterialButton>(R.id.btnResizeMode)
        val btnAudio = overlay.findViewById<ImageButton>(R.id.btnAudio)
        val btnSubtitle = overlay.findViewById<ImageButton>(R.id.btnSubtitle)
        val btnQuality = overlay.findViewById<ImageButton>(R.id.btnQuality)
        btnSbsRef = btnSbs
        btnShiftRef = btnShift
        btnResizeModeRef = btnResizeMode
        btnQualityRef = btnQuality
        titleCenterView = overlay.findViewById(R.id.tvTitleCenter)
        // Audio menu containers
        audioMenuRoot = overlay.findViewById(R.id.audioMenuRoot)
        audioMenuCenter = overlay.findViewById(R.id.audioMenuCenter)
        audioMenuLeft = overlay.findViewById(R.id.audioMenuLeft)
        audioMenuRight = overlay.findViewById(R.id.audioMenuRight)
        // Tap-to-dismiss, but NOT focusable: on a D-pad/TV device a focusable full-screen layer
        // would swallow focus so the menu items couldn't be reached.
        audioMenuRoot?.isFocusable = false
        audioMenuRoot?.setOnClickListener { hideAudioMenu() }
        // Make D-pad / TV focus visible on the overlay's top-bar controls.
        com.teleteh.xplayer2.ui.util.TvFocus.applyToButtons(overlay)
        btnBack.setOnClickListener { navigateBackToPrimary() }
        btnSbs.isCheckable = true
        btnSbs.isChecked = getStereoSbs()
        applySbsButtonVisual(btnSbs)
        btnSbs.setOnClickListener {
            sbsExplicitlyConfigured = true
            toggleStereoMode()
            applySbsButtonVisual(btnSbs)
        }
        // Resize mode button cycles through aspect ratios
        btnResizeMode.setOnClickListener {
            resizeMode = (resizeMode + 1) % 7
            applyResizeMode()
            saveProgress()
        }
        applyResizeMode()
        // Shift debug button
        btnShift.isCheckable = true
        btnShift.isChecked = sbsShiftEnabled
        btnShift.setOnClickListener {
            sbsShiftEnabled = !sbsShiftEnabled
            btnShift.isChecked = sbsShiftEnabled
            applySbsShiftIfNeeded()
            // Persist per-item shift state
            saveProgress()
        }
        btnAudio.setOnClickListener { showAudioMenu() }
        btnSubtitle.setOnClickListener { showSubtitleMenu() }
        btnQuality.setOnClickListener { showQualityMenu() }
        updateQualityButtonVisibility()
        // Configure controllers with same behavior
        val timeoutMs = 3000
        playerView.controllerShowTimeoutMs = timeoutMs
        playerView.controllerHideOnTouch = true
        playerView.controllerAutoShow = true
        // Mirror overlay visibility to controller visibility only
        val controllerListener = ControllerVisibilityListener { visibility ->
            overlay.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
            if (visibility == View.VISIBLE) {
                // Hide ExoPlayer's default settings gear — track/speed live on our own overlay buttons.
                playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            }
        }
        playerView.setControllerVisibilityListener(controllerListener)
        hideSystemBars()
        updateSbsUi()

        // React to the goggles' external panel powering on/off (proximity sensor).
        registerDisplayListener()

        // Watch audio outputs so the 5.1→stereo fold-down re-decides when a multichannel
        // sink (dock/HDMI/USB-DAC) comes or goes mid-playback — or when a route the platform
        // can spatialise, which is what the glasses are, arrives or leaves.
        lastMultichannelSink = StereoFolddownAudioProcessor.multichannelSinkAvailable(applicationContext)
        lastSpatialAnswer = isSpatialAudioEnabled() &&
            StereoFolddownAudioProcessor.platformWillSpatialize(applicationContext, 6, 48_000)
        getSystemService(android.media.AudioManager::class.java)
            ?.registerAudioDeviceCallback(audioDeviceCallback, Handler(mainLooper))

        // Hold the glasses' USB link open for our whole session. MainActivity is stopped while
        // the player owns the goggles, so without this its onStop() would release the connection
        // and features that read from USB (the Lazy-3D head-tracking IMU) would have no link.
        // Ref-counted in GlassesController, so this composes with MainActivity's own acquire.
        // Remember the exact instance we registered on: the static can be null NOW (player
        // launched directly from a share/VIEW intent, MainActivity never created) yet non-null
        // by onDestroy (back-navigation starts MainActivity first) — unregistering an instance
        // we never registered on steals MainActivity's live acquire and kills its USB link;
        // and if MainActivity is recreated mid-playback, the OLD controller would leak its
        // claimed connection forever.
        acquiredGlasses = MainActivity.glassesControllerForPlayback?.also { it.register() }

        // Resolve and start playback from the launching intent.
        loadFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode="singleTop": a second ACTION_VIEW (e.g. from Recent, Files, or notification)
        // routes through onNewIntent instead of spinning up a second PlayerActivity. Tear the
        // previous playback down completely before re-using this instance — leaving the prior
        // ExoPlayer + LoudnessEnhancer alive while a new one starts has been observed to lock
        // the USB audio device on XREAL Air goggles (no audio in any app until uninstall).
        setIntent(intent)
        releaseLoudnessEnhancer()
        try { player?.clearVideoSurface() } catch (_: Throwable) { }
        try { player?.release() } catch (_: Throwable) { }
        player = null
        trackSelector = null
        resolvedStreamUri = null
        streamVariants = emptyList()
        selectedVariantIndex = 0
        currentResolvedTitle = null
        detectedSourceStereoMode = null
        sbsExplicitlyConfigured = false
        currentPipeline = VideoPipeline.GL
        lastVideoWidth = 0
        lastVideoHeight = 0
        loadFromIntent(intent)
    }

    private fun loadFromIntent(intent: Intent?) {
        // PC Link hands us a host instead of a URI. Checked first: such an intent carries no data
        // URI at all, which everything below would read as "nothing to play" and finish().
        val pcHost = intent?.getStringExtra(PcConnectActivity.EXTRA_PCLINK_HOST)?.takeIf { it.isNotBlank() }
        if (pcHost != null) {
            startPcLink(intent, pcHost)
            return
        }
        val action = intent?.action
        sourceUri = when (action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val parsed = try {
                    if (!text.isNullOrBlank()) text.toUri() else null
                } catch (_: Throwable) {
                    null
                }
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                parsed ?: stream
            }
            else -> intent?.data
        }
        if (sourceUri == null) {
            finish()
            return
        }

        val uri = sourceUri!!
        // A container link opened/shared into the player can't be played as a single file — a VK
        // playlist/group is a video LIST, and a Yandex Disk public link may be a folder (no single
        // file) or a single file that needs its download href resolved. Classify it: container types
        // go to their browser (and the VK ones are remembered as a Sources row; YaDisk remembers
        // itself once it confirms a folder), while single videos / direct links fall through to play.
        val kind = WebSourceClassifier.classify(uri.toString())
        WebSourceClassifier.openIntent(this, uri.toString(), kind)?.let { browse ->
            startActivity(browse)
            finish()
            return
        }
        // The glasses are one screen and everything below is about to take it, so whatever else is
        // on it comes off first: a PC Link session in this activity (singleTop → onNewIntent), and
        // a desktop or another film in an older PlayerActivity, which singleTop lets exist whenever
        // this one wasn't the top of the task.
        //
        // Deliberately after the two branches above that play nothing: the playback notification's
        // bare launch intent and a container link (a VK list, a Yandex folder) both land here and
        // then leave, and neither is a reason to end a session the user never touched.
        val endedOwnPcLink = isPcLinkMode
        if (isPcLinkMode) exitPcLink()
        val handover = GlassesStage.claim(this)
        if (handover.endedPcLink || endedOwnPcLink) {
            // Unasked, but not unannounced — see GlassesStage.
            Toast.makeText(this, R.string.pclink_ended_for_playback, Toast.LENGTH_SHORT).show()
        }
        recentKeyUri = intent?.getStringExtra(EXTRA_RECENT_URI)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        android.util.Log.i("XPlayer2", "Source URI: $uri, host=${uri.host}, isSupported=${VideoStreamExtractor.isSupported(uri, isYouTubeEnabled())}")
        val injectedLabels = intent?.getStringArrayExtra(EXTRA_STREAM_LABELS)
        val injectedUrls = intent?.getStringArrayExtra(EXTRA_STREAM_URLS)
        if (injectedUrls != null && injectedLabels != null &&
            injectedUrls.isNotEmpty() && injectedLabels.size == injectedUrls.size) {
            // Yandex Disk passes its per-quality HLS renditions directly (highest first); expose them
            // as selectable variants so the quality menu works just like VK/OK. Index 0 = max quality.
            streamVariants = injectedUrls.indices.map {
                VideoStreamExtractor.StreamVariant(injectedLabels[it], injectedUrls[it])
            }
            selectedVariantIndex = 0
            resolvedStreamUri = Uri.parse(injectedUrls[0])
            initializePlayer()
            updateCenterTitle()
            updateQualityButtonVisibility()
            RemoteControlActivity.currentInstance?.syncControls()
        } else if (VideoStreamExtractor.isSupported(uri, isYouTubeEnabled())) {
            titleCenterView?.text = getString(R.string.loading_stream)
            android.util.Log.i("XPlayer2", "Starting stream extraction for: $uri")
            isExtractingStream = true
            lifecycleScope.launch {
                try {
                    val extracted = VideoStreamExtractor.extract(uri, isYouTubeEnabled())
                    isExtractingStream = false
                    if (extracted != null) {
                        android.util.Log.i("XPlayer2", "Stream extracted successfully: ${extracted.url}")
                        resolvedStreamUri = Uri.parse(extracted.url)
                        resolvedAudioUri = extracted.audioUrl?.let { Uri.parse(it) }
                        extractedHeaders = extracted.headers
                        extractedTitle = extracted.title
                        // Primary URL is always the highest variant; default selection = index 0.
                        streamVariants = extracted.variants
                        selectedVariantIndex = 0
                        if (!extracted.title.isNullOrBlank()) {
                            currentResolvedTitle = extracted.title
                        }
                        initializePlayer()
                        updateCenterTitle()
                        updateQualityButtonVisibility()
                        // The remote may already be up (glasses connected) — refresh its controls
                        // so the quality button appears once variants are known.
                        RemoteControlActivity.currentInstance?.syncControls()
                    } else {
                        android.util.Log.w("XPlayer2", "Stream extraction failed for: $uri")
                        Toast.makeText(this@PlayerActivity, R.string.stream_extraction_failed, Toast.LENGTH_LONG).show()
                        titleCenterView?.text = getString(R.string.stream_extraction_failed)
                    }
                } catch (e: Exception) {
                    isExtractingStream = false
                    android.util.Log.e("XPlayer2", "Exception during stream extraction", e)
                    Toast.makeText(this@PlayerActivity, R.string.stream_extraction_failed, Toast.LENGTH_LONG).show()
                    titleCenterView?.text = getString(R.string.stream_extraction_failed)
                }
            }
        } else {
            resolvedStreamUri = uri
            initializePlayer()
            updateCenterTitle()
            tryProbeTitleFromRetriever()
        }
    }

    private fun hideSystemBars() {
        // Enter immersive fullscreen (hide status/navigation bars)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, playerView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun isPlaybackUiHidden(): Boolean {
        val overlay = playerView.findViewById<android.widget.FrameLayout>(androidx.media3.ui.R.id.exo_overlay)
        val isOverlayVisible = overlay?.visibility == View.VISIBLE
        val isControllerVisible = playerView.isControllerFullyVisible
        val isAudioMenuVisible = audioMenuRoot?.visibility == View.VISIBLE
        return !isOverlayVisible && !isControllerVisible && !isAudioMenuVisible
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // When the UI is hidden, swallow LEFT/RIGHT D-pad events at the dispatch stage
        // so PlayerView doesn't auto-show its controller before we get a chance to seek.
        if (isPlaybackUiHidden() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) seekRelative(-10000L)
                else seekRelative(10000L)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val overlay = playerView.findViewById<android.widget.FrameLayout>(androidx.media3.ui.R.id.exo_overlay)
        val isOverlayVisible = overlay?.visibility == View.VISIBLE
        val isControllerVisible = playerView.isControllerFullyVisible
        val isAudioMenuVisible = audioMenuRoot?.visibility == View.VISIBLE
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isOverlayVisible || isControllerVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!isOverlayVisible && !isControllerVisible && !isAudioMenuVisible) {
                    seekRelative(-10000L); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isOverlayVisible && !isControllerVisible && !isAudioMenuVisible) {
                    seekRelative(10000L); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isAudioMenuVisible) {
                    hideTrackMenu(); true
                } else if (isOverlayVisible || isControllerVisible) {
                    playerView.hideController(); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                togglePlayPause(); true
            }
            // Media-remote / TV-remote transport keys (work alongside the MediaSession).
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finishAndClose(); true }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { seekRelative(-10000L); true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> { seekRelative(10000L); true }
            KeyEvent.KEYCODE_CAPTIONS -> { showSubtitleMenu(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Whether YouTube link extraction is on. ON by default in both flavours — the feature is
     * invisible (no UI, no youtube.com manifest filter; it only fires on a pasted/shared link), so
     * there's nothing for the Play listing to advertise. [BuildConfig.YOUTUBE_ENABLED_DEFAULT] is a
     * build-time kill-switch we can flip to false if Google ever objects.
     */
    private fun isYouTubeEnabled(): Boolean =
        getSharedPreferences("youtube", MODE_PRIVATE)
            .getBoolean("enabled", BuildConfig.YOUTUBE_ENABLED_DEFAULT)

    private fun initializePlayer() {
        val uri = resolvedStreamUri ?: sourceUri ?: return
        android.util.Log.i("XPlayer2", "initializePlayer called with uri=$uri, player=${player != null}")
        if (player != null) {
            android.util.Log.w("XPlayer2", "Player already initialized, skipping")
            return
        }
        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        // MODE_ON: try platform (hardware) renderers first, fall back to extension (FFmpeg)
        // when the platform doesn't support a codec. MODE_PREFER forced FFmpeg even for
        // plain stereo AAC, which on some devices fights with the AudioTrack downmix path
        // and can produce no audio at all when the output is a USB-stereo sink (XREAL Air).
        val renderersFactory = object : DefaultRenderersFactory(this) {
            // Wire the in-app 5.1/7.1→stereo fold-down into the sink's processor chain. It
            // self-activates per configure(): only for 6/8-ch PCM with no multichannel-capable
            // output attached — see StereoFolddownAudioProcessor for the full why (platform
            // fold-down loses/attenuates dialogue on several OEM builds; VLC-style self-mix
            // doesn't). With a real surround sink attached it stays inactive and the untouched
            // multichannel stream flows exactly as before.
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        StereoFolddownAudioProcessor(
                            {
                                StereoFolddownAudioProcessor.multichannelSinkAvailable(applicationContext)
                            },
                            { channels, rate -> spatialAudioWanted(channels, rate) },
                        )
                    )
                )
                .build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val isLocalUri = uri.scheme?.lowercase() in setOf("file", "content")
        val playerBuilder = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            // ±10 s so the MediaSession (remote / lock-screen) and the default controller's rewind/
            // fast-forward match our own ±10 s seek.
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
        if (isLocalUri) {
            // Local reads are essentially free; the default 50-second buffer just inflates
            // RAM use and disk activity. Drop it down to a few seconds for better battery.
            playerBuilder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs */ 5_000,
                        /* maxBufferMs */ 15_000,
                        /* bufferForPlaybackMs */ 500,
                        /* bufferForPlaybackAfterRebufferMs */ 1_000
                    )
                    .build()
            )
        }
        player = playerBuilder.build().also { exo ->
                // For HLS master playlists, force highest quality only on unmetered Wi-Fi —
                // doing it on cellular costs the user money and burns battery for no good reason.
                val ffmpegAvailableForPrefs = try { FfmpegLibrary.isAvailable() } catch (_: Throwable) { false }
                selector.parameters = selector.buildUponParameters()
                    .setForceHighestSupportedBitrate(isOnUnmeteredNetwork())
                    // Only prefer AC3/EAC3/DTS when FFmpeg is available. Otherwise prefer common AAC/Opus/Vorbis
                    .setPreferredAudioMimeTypes(
                        *(if (ffmpegAvailableForPrefs) arrayOf(
                            MimeTypes.AUDIO_E_AC3,
                            MimeTypes.AUDIO_AC3,
                            MimeTypes.AUDIO_DTS,
                            MimeTypes.AUDIO_DTS_HD,
                            MimeTypes.AUDIO_AAC,
                            MimeTypes.AUDIO_OPUS,
                            MimeTypes.AUDIO_VORBIS
                        ) else arrayOf(
                            MimeTypes.AUDIO_AAC,
                            MimeTypes.AUDIO_OPUS,
                            MimeTypes.AUDIO_VORBIS,
                            MimeTypes.AUDIO_E_AC3,
                            MimeTypes.AUDIO_AC3,
                            MimeTypes.AUDIO_DTS,
                            MimeTypes.AUDIO_DTS_HD
                        ))
                    )
                    // Subtitles default to off: most clips here are watched without them, and an
                    // unexpected caption track is intrusive on the goggles. The user can turn them
                    // on from the subtitle menu (overlay) or the remote, which re-enables the renderer.
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                // Bind player to UI controls (PlayerView), but video output will be our GL surface
                playerView.player = exo
                val exoAttrs = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                exo.setAudioAttributes(exoAttrs, true)
                // If a title was provided by caller (e.g., DLNA DIDL), attach it to MediaItem metadata
                val providedTitle = intent?.getStringExtra(EXTRA_TITLE)
                val meta = if (!providedTitle.isNullOrBlank()) {
                    currentResolvedTitle = providedTitle
                    MediaMetadata.Builder().setTitle(providedTitle).build()
                } else null
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .apply { meta?.let { setMediaMetadata(it) } }
                    .build()
                val audioUri = resolvedAudioUri
                if (audioUri != null) {
                    // YouTube adaptive: video and audio are SEPARATE streams — play them in parallel
                    // via a MergingMediaSource. The googlevideo CDN wants the YouTube client UA on
                    // every request, so build a DataSource factory carrying it.
                    val ua = extractedHeaders?.get("User-Agent")
                    val dsFactory = DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .apply { if (!ua.isNullOrBlank()) setUserAgent(ua) }
                    val videoSrc = ProgressiveMediaSource.Factory(dsFactory).createMediaSource(mediaItem)
                    val audioSrc = ProgressiveMediaSource.Factory(dsFactory)
                        .createMediaSource(MediaItem.fromUri(audioUri))
                    exo.setMediaSource(MergingMediaSource(videoSrc, audioSrc))
                } else {
                    exo.setMediaItem(mediaItem)
                }

                // --- Diagnostics: FFmpeg availability ---
                val ffmpegAvailable = try { FfmpegLibrary.isAvailable() } catch (_: Throwable) { false }
                if (!ffmpegAvailable) {
                    android.util.Log.w("XPlayer2", "FFmpeg extension not available. AC3/EAC3/DTS may not decode on this device.")
                } else {
                    android.util.Log.i("XPlayer2", "FFmpeg extension is available. Using extension renderers: PREFER")
                }

                // Wire GL surface for video output (only used when our pipeline is GL).
                glView?.setOnSurfaceReadyListener { surface ->
                    glSurface = surface
                    if (presentation == null && currentPipeline == VideoPipeline.GL) {
                        exo.setVideoSurface(surface)
                    }
                }
                exo.prepare()
                
                // If Presentation exists, bind player to it
                presentation?.let { pres ->
                    pres.setPlayer(exo)
                    // If Presentation surface is already ready, bind it now
                    presentationSurface?.let { surface ->
                        exo.setVideoSurface(surface)
                    }
                }

                // Resume position if provided or stored
                // Use sourceUri for recents lookup (not resolvedStreamUri which may be different for extracted streams)
                val requestedStart = intent?.getLongExtra(EXTRA_START_POSITION_MS, -1L) ?: -1L
                val store = RecentStore(this)
                val recent = store.find((recentKeyUri ?: sourceUri ?: uri).toString())
                val resumePos = when {
                    requestedStart >= 0L -> requestedStart
                    (recent?.lastPositionMs ?: 0L) > 0L -> recent!!.lastPositionMs
                    else -> 0L
                }
                if (resumePos > 0L) {
                    exo.seekTo(resumePos)
                }
                // Initialize per-item shift toggle from recents
                sbsShiftEnabled = recent?.sbsShiftEnabled ?: false
                btnShiftRef?.isChecked = sbsShiftEnabled
                applySbsShiftIfNeeded()
                // Initialize per-item resize mode from recents
                resizeMode = recent?.resizeMode ?: 0
                applyResizeMode()
                // Restore a manual per-clip stereo choice if one was saved; otherwise auto-detect
                // from the source layout on the first frame (see applyRenderMode). Start neutral
                // so we never flash a wrong OU/SBS cut before detection.
                val savedStereo = recent?.stereoMode ?: -1
                if (savedStereo in 0..2) {
                    stereoMode = StereoMode.fromInt(savedStereo)
                    sbsExplicitlyConfigured = true
                } else {
                    stereoMode = StereoMode.Off
                    sbsExplicitlyConfigured = false
                }
                // Restore this clip's audio boost. For a clip we haven't seen before, online streams
                // (VK/OK/YouTube/Yandex/DLNA/direct) default to a gentle loudness boost — they're
                // commonly quiet, wide-dynamic-range cinema mixes (~ -20 LUFS), worse on the quieter
                // glasses audio path; local files default to 0. Always user-overridable (0 = off),
                // applied once the audio session is ready (see rebindLoudnessEnhancer).
                volumeBoostMb = (recent?.volumeBoostMb ?: defaultBoostMbFor(sourceUri ?: uri))
                    .coerceIn(0, 2400)
                renderSourceIsSbs = false
                renderDuplicateMono = false
                applyRenderConfig()
                btnSbsRef?.let { applySbsButtonVisual(it) }
                applyVideoPipeline()
                exo.play()
                // If the foreground service already connected while the player was still null
                // (e.g. stream extraction outlived the bind), its notification is the placeholder
                // without a MediaSession — hand it the real player now that one exists.
                updatePlaybackService()
                // Listen for metadata/title updates to reflect in UI and Recent
                exo.addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        rebindLoudnessEnhancer(audioSessionId)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        // Push the state to the phone remote immediately — it otherwise polls at
                        // 500 ms, which reads as lag on the play/pause icon after a remote tap.
                        RemoteControlActivity.currentInstance?.onTransportChanged()
                    }
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        lastVideoWidth = videoSize.width
                        lastVideoHeight = videoSize.height
                        activeGlView()?.updateVideoAspectRatio(videoSize.width, videoSize.height)
                        // Re-derive the whole render mode from the now-known frame size.
                        applyRenderMode()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        // Capture Format.stereoMode and HDR transfer from the selected video track.
                        // MKV's StereoMode and MP4's st3d/sv3d both surface through Format.stereoMode;
                        // HDR10 / HLG arrive as Format.colorInfo.colorTransfer.
                        var stereo: Int? = null
                        var isHdr = false
                        val groups = tracks.groups
                        for (i in 0 until groups.size) {
                            val g = groups[i]
                            if (g.type != C.TRACK_TYPE_VIDEO) continue
                            for (j in 0 until g.length) {
                                if (!g.isTrackSelected(j)) continue
                                val fmt = g.getTrackFormat(j)
                                val mode = fmt.stereoMode
                                if (mode != Format.NO_VALUE) stereo = mode
                                val transfer = fmt.colorInfo?.colorTransfer
                                if (transfer == C.COLOR_TRANSFER_ST2084 ||
                                    transfer == C.COLOR_TRANSFER_HLG) {
                                    isHdr = true
                                }
                            }
                        }
                        if (stereo != detectedSourceStereoMode) {
                            detectedSourceStereoMode = stereo
                            applyRenderMode()
                        }
                        updateHdrColorMode(isHdr)

                        // Diagnostic logging — includes audio channel count / sample rate /
                        // support state so silent-audio bugs (e.g. surround source that no
                        // available decoder can handle) leave an obvious paper trail.
                        var hasAnySupportedAudio = false
                        var hasSelectedAudio = false
                        for (i in 0 until groups.size) {
                            val g = groups[i]
                            val typeName = when (g.type) {
                                C.TRACK_TYPE_VIDEO -> "VIDEO"
                                C.TRACK_TYPE_AUDIO -> "AUDIO"
                                C.TRACK_TYPE_TEXT -> "TEXT"
                                C.TRACK_TYPE_METADATA -> "METADATA"
                                else -> "TYPE_${g.type}"
                            }
                            for (j in 0 until g.length) {
                                val info = g.getTrackFormat(j)
                                val selected = g.isTrackSelected(j)
                                val supported = try { g.isTrackSupported(j) } catch (_: Throwable) { false }
                                if (g.type == C.TRACK_TYPE_AUDIO) {
                                    if (supported) hasAnySupportedAudio = true
                                    if (selected) hasSelectedAudio = true
                                    android.util.Log.i(
                                        "XPlayer2",
                                        "Track[AUDIO] selected=$selected supported=$supported mime=${info.sampleMimeType} codecs=${info.codecs} channels=${info.channelCount} rate=${info.sampleRate}Hz lang=${info.language} label=${info.label}"
                                    )
                                } else {
                                    android.util.Log.i(
                                        "XPlayer2",
                                        "Track[$typeName] selected=$selected supported=$supported mime=${info.sampleMimeType} label=${info.label} lang=${info.language} id=${info.id} stereoMode=${info.stereoMode}"
                                    )
                                }
                                info.metadata?.let { meta ->
                                    for (k in 0 until meta.length()) {
                                        android.util.Log.i("XPlayer2", "  Format metadata[$k]: ${meta[k]}")
                                    }
                                }
                            }
                        }
                        if (!hasSelectedAudio && hasAnySupportedAudio) {
                            android.util.Log.w("XPlayer2", "Audio: a supported track exists but none is selected — selector parameters issue?")
                        } else if (!hasAnySupportedAudio) {
                            android.util.Log.e("XPlayer2", "Audio: no audio track is supported by any available decoder — surround codec missing from FFmpeg .so?")
                        }
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        // Log all media metadata
                        android.util.Log.i("XPlayer2", "MediaMetadata changed:")
                        android.util.Log.i("XPlayer2", "  title=${mediaMetadata.title}")
                        android.util.Log.i("XPlayer2", "  displayTitle=${mediaMetadata.displayTitle}")
                        android.util.Log.i("XPlayer2", "  artist=${mediaMetadata.artist}")
                        android.util.Log.i("XPlayer2", "  albumTitle=${mediaMetadata.albumTitle}")
                        android.util.Log.i("XPlayer2", "  description=${mediaMetadata.description}")
                        android.util.Log.i("XPlayer2", "  station=${mediaMetadata.station}")
                        updateCenterTitle()
                        // Persist improved title into Recent if changed
                        val newTitle = bestTitleForCurrent()
                        if (newTitle.isNotBlank() && newTitle != currentResolvedTitle) {
                            currentResolvedTitle = newTitle
                            // Upsert with current progress to update title
                            saveProgress()
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        // Log all metadata entries
                        android.util.Log.i("XPlayer2", "onMetadata: ${metadata.length()} entries")
                        var foundTitle: String? = null
                        for (i in 0 until metadata.length()) {
                            val entry = metadata[i]
                            android.util.Log.i("XPlayer2", "  Metadata[$i]: ${entry.javaClass.simpleName} = $entry")
                            if (entry is TextInformationFrame) {
                                android.util.Log.i("XPlayer2", "    ID3 frame: id=${entry.id} values=${entry.values}")
                                if (entry.id.equals("TIT2", ignoreCase = true)) {
                                    val t = entry.values[0].trim()
                                    if (t.isNotBlank()) {
                                        foundTitle = t
                                    }
                                }
                            }
                        }
                        if (!foundTitle.isNullOrBlank()) {
                            if (currentResolvedTitle.isNullOrBlank() || currentResolvedTitle != foundTitle) {
                                currentResolvedTitle = foundTitle
                                titleCenterView?.text = foundTitle
                                saveProgress()
                            }
                        }
                    }
                })
            }
        updateSbsUi()
    }

    override fun onStart() {
        super.onStart()
        // Don't initialize player if stream extraction is in progress
        if (player == null && sourceUri != null && !isExtractingStream) initializePlayer()
        // Coming back to the foreground with the link torn down (see onStop): re-open it — unless
        // a `unknown_client` refusal is waiting to take the user back to the connect screen, which
        // it couldn't do while this activity was stopped.
        if (isPcLinkMode && !bouncePcLinkRepair()) connectPcLink()
        // Try to show Presentation on external display
        tryShowExternalPresentation()
        // If the picture is on the goggles, bring the phone-side remote to the front.
        showRemoteControlFront()
    }

    /**
     * Bring the phone-side remote to the front while the picture is on the external panel. Uses
     * REORDER_TO_FRONT + singleTop so an already-running remote (kept alive when the goggles come
     * off) just resurfaces rather than relaunching. No-op when there's no external presentation
     * (goggles off) — then the player itself stays in front on the phone. This keeps the stack as
     * Main < Player < Remote and only flips which of Player/Remote is on top by display state.
     */
    private fun showRemoteControlFront() {
        if (presentation == null) return
        // PC Link gets its own remote rather than this one: the film remote drives an ExoPlayer —
        // transport, scrubbing, track menus — and a desktop has no timeline to drive. Without it
        // the phone was left showing this activity's window, which for a stream it never decodes
        // into its own surface is a grey rectangle.
        val remote = if (isPcLinkMode) PcLinkRemoteActivity::class.java else RemoteControlActivity::class.java
        startActivity(Intent(this, remote).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        glView?.onPause()
        // If Presentation is active, keep playing when phone screen is turned off/locked
        if (presentation == null) {
            player?.playWhenReady = false
        }
        // Foreground service: keep if external playback is active, otherwise stop
        updatePlaybackService()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // PC Link follows the same rule as the player: keep streaming while the picture is on the
        // glasses (the phone screen going dark is not a reason to stop), otherwise drop the
        // sockets and the codec — nothing should decode a desktop nobody is looking at.
        if (isPcLinkMode && !(presentation != null || isOnExternalDisplay())) disconnectPcLink()
        // If external Presentation is active or activity is on external display, keep the player alive to continue playback on the secondary display
        if (!(presentation != null || isOnExternalDisplay())) {
            // Release audio-side resources FIRST. LoudnessEnhancer is attached to the player's
            // audio session and the system has been observed to leave the underlying audio
            // effect alive after the session is torn down — which on some devices (notably
            // XREAL Air via USB audio) locks the output device and starves every other app's
            // audio until the process is killed.
            releaseLoudnessEnhancer()
            player?.clearVideoSurface()
            player?.release()
            player = null
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            // (displayListener is registered in onCreate and torn down in onDestroy so it keeps
            // firing while playback continues on the external panel with the phone stopped.)
            // Dismiss Presentation if we're finishing (leaving playback), otherwise keep it during lock
            if (isFinishing) {
                dismissPresentation()
            } else if (!(presentation != null || isOnExternalDisplay())) {
                // No external playback in use -> ensure dismissal
                dismissPresentation()
            }
        }
        // Update foreground service after potential dismissal/finishing
        updatePlaybackService()
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Not used; we manage menus directly on toolbar
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val exo = player ?: return super.onOptionsItemSelected(item)
        return when (item.itemId) {
            R.id.menu_audio -> {
                TrackSelectionDialogBuilder(
                    this,
                    getString(R.string.select_audio_track),
                    exo,
                    C.TRACK_TYPE_AUDIO
                ).build().show()
                true
            }

            R.id.menu_subtitle -> {
                TrackSelectionDialogBuilder(
                    this,
                    getString(R.string.select_subtitle),
                    exo,
                    C.TRACK_TYPE_TEXT
                )
                    .setShowDisableOption(true)
                    .build()
                    .show()
                true
            }

            R.id.menu_stereo -> {
                toggleStereoMode()
                true
            }

            R.id.menu_back -> {
                finish()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView?.onResume()
        // Resume playback if needed
        player?.playWhenReady = true
        // Try to show Presentation on external display
        tryShowExternalPresentation()
        updateSbsUi()
        // Ensure foreground service matches current external playback state
        updatePlaybackService()
    }

    private fun isOnExternalDisplay(): Boolean {
        return try {
            val d = window?.decorView?.display
            d != null && d.displayId != Display.DEFAULT_DISPLAY
        } catch (_: Throwable) { false }
    }

    override fun onDestroy() {
        super.onDestroy()
        liveInstances.remove(this)
        GlassesStage.unregister(this)
        PcLinkSession.unregister(this)
        saveProgress()
        exitPcLink()
        if (lazy3dEnabled) stopLazy3d()
        unregisterDisplayListener()
        getSystemService(android.media.AudioManager::class.java)
            ?.unregisterAudioDeviceCallback(audioDeviceCallback)
        // Release our hold on the glasses USB link (ref-counted; MainActivity keeps it alive if
        // it's in the foreground). Done after stopLazy3d() so the IMU stream is halted first.
        // Strictly the same instance onCreate registered on — see the comment there.
        acquiredGlasses?.unregister()
        acquiredGlasses = null
        releaseLoudnessEnhancer()
        player?.release()
        player = null
        stopPlaybackService()
    }

    // --- Audio gain (LoudnessEnhancer) ---
    // Per-clip: held in [volumeBoostMb], persisted to / restored from the clip's RecentEntry.
    private fun getVolumeBoostMb(): Int = volumeBoostMb.coerceIn(0, 2400)

    /** Default loudness boost for a brand-new clip: a gentle bump for online (http/https) streams,
     *  none for local files. The LoudnessEnhancer is an AGC+limiter, so this raises perceived
     *  loudness without hard-clipping; the user can still set it to 0 or higher per clip. */
    private fun defaultBoostMbFor(source: Uri?): Int {
        val scheme = source?.scheme?.lowercase() ?: return 0
        return if (scheme == "http" || scheme == "https") DEFAULT_ONLINE_BOOST_MB else 0
    }

    private fun setVolumeBoostMb(value: Int) {
        val clamped = value.coerceIn(0, 2400)
        volumeBoostMb = clamped
        // Persist against the current clip so the level comes back when it is reopened —
        // a boost that fixes one quiet upload would distort a normally-mastered video.
        saveProgress()
        if (clamped <= 0) {
            // No boost requested — detach the effect entirely. Letting an enabled=false
            // LoudnessEnhancer linger on the audio session has been observed to lock the
            // USB audio device on XREAL Air goggles, killing audio system-wide until the
            // app process is uninstalled.
            releaseLoudnessEnhancer()
            return
        }
        val enhancer = loudnessEnhancer ?: run {
            // Prefer the live player's current session; fall back to the last captured one. We must
            // not rely solely on lastAudioSessionId — releasing the effect (e.g. after cycling the
            // boost to off) used to clear it, leaving a later re-enable with no session to attach to.
            val sid = (player?.audioSessionId?.takeIf { it != C.AUDIO_SESSION_ID_UNSET && it != 0 })
                ?: lastAudioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET && it != 0 }
                ?: return
            lastAudioSessionId = sid
            try {
                LoudnessEnhancer(sid).also { loudnessEnhancer = it }
            } catch (e: Exception) {
                android.util.Log.w("XPlayer2", "Failed to attach LoudnessEnhancer to session $sid", e)
                null
            }
        } ?: return
        try {
            enhancer.setTargetGain(clamped)
            enhancer.enabled = true
        } catch (e: Exception) {
            android.util.Log.w("XPlayer2", "LoudnessEnhancer setTargetGain failed", e)
        }
    }

    private fun rebindLoudnessEnhancer(audioSessionId: Int) {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) {
            releaseLoudnessEnhancer()
            return
        }
        // Audio session changed — drop any prior effect tied to the old one.
        if (audioSessionId != lastAudioSessionId) {
            releaseLoudnessEnhancer()
            lastAudioSessionId = audioSessionId
        }
        // Only attach the effect if the user actually asked for boost. Otherwise leave the
        // audio session untouched (avoids both the device-lock and the cost of attaching
        // an effect we won't use).
        val gainMb = getVolumeBoostMb()
        if (gainMb <= 0) {
            releaseLoudnessEnhancer()
            return
        }
        if (loudnessEnhancer != null) return
        try {
            loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                setTargetGain(gainMb)
                enabled = true
            }
        } catch (e: Exception) {
            android.util.Log.w("XPlayer2", "Failed to create LoudnessEnhancer for session $audioSessionId", e)
            loudnessEnhancer = null
        }
    }

    private fun releaseLoudnessEnhancer() {
        try { loudnessEnhancer?.release() } catch (_: Exception) { }
        loudnessEnhancer = null
        // Keep lastAudioSessionId: the audio session is still valid during playback (e.g. when the
        // user cycles the boost to off and back on), so we must be able to re-attach to it.
    }

    private fun boostLabel(mb: Int): String {
        val db = mb / 100
        return if (mb <= 0) getString(R.string.volume_boost_off)
        else getString(R.string.volume_boost_with_value, db)
    }

    /** Current spatial-audio row label, e.g. "Объёмный звук: выкл." */
    private fun spatialLabel(on: Boolean): String = getString(
        if (on) R.string.spatial_audio_on else R.string.spatial_audio_off
    )

    /** Current volume-boost button label, e.g. "Boost: off" / "Boost: +12 dB". */
    fun getVolumeBoostLabel(): String = boostLabel(getVolumeBoostMb())

    /** Compact label for the icon-only remote row: always "+N", never "off" — the icon already
     *  says what the button is, this just needs to carry the current dB value. */
    fun getVolumeBoostShortLabel(): String = "+${getVolumeBoostMb() / 100}"

    /** Cycle volume boost 0 -> +6 -> +12 -> +18 -> +24 dB -> 0; returns the new label. */
    fun cycleVolumeBoost(): String {
        val steps = intArrayOf(0, 600, 1200, 1800, 2400)
        val cur = getVolumeBoostMb()
        val next = steps.firstOrNull { it > cur } ?: 0
        setVolumeBoostMb(next)
        return boostLabel(next)
    }

    private fun updatePlaybackService() {
        if (presentation != null) {
            startPlaybackService()
        } else {
            stopPlaybackService()
        }
    }

    private fun startPlaybackService() {
        if (serviceBound) {
            // Already bound, just start foreground
            player?.let { playbackService?.startForegroundPlayback(it, currentResolvedTitle) }
            return
        }
        val intent = Intent(this, PlaybackService::class.java)
        try {
            // ContextCompat routes to startForegroundService() on API 26+ (PlaybackService
            // promotes ITSELF in onStartCommand, inside the 5s window, even if this bind never
            // connects) — plain startService() here used to throw
            // BackgroundServiceStartNotAllowedException / "app is in background" because this is
            // called from onPause()/onStop() (see updatePlaybackService()), i.e. exactly when the
            // OS may no longer count the app as foreground. Still guarded: even
            // startForegroundService() can be refused in rare states (API 31+
            // ForegroundServiceStartNotAllowedException) — if so, playback on the external
            // display continues regardless (the service only owns the persistent notification,
            // not the ExoPlayer instance), we just skip the notification rather than crash.
            ContextCompat.startForegroundService(this, intent)
            serviceStartRequested = true
            // Track the bind REQUEST, not just the connect: unbinding must happen for any
            // successful bindService() call even if onServiceConnected never fired (activity
            // left before the connect landed) — otherwise the ServiceConnection leaks.
            serviceBindRequested = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.w("XPlayer2", "Failed to start PlaybackService (external playback continues without it)", e)
        }
    }

    private fun stopPlaybackService() {
        if (!serviceStartRequested && !serviceBindRequested && !serviceBound) return
        playbackService?.stopForegroundPlayback()
        if (serviceBindRequested || serviceBound) {
            try { unbindService(serviceConnection) } catch (_: Throwable) { }
        }
        if (playbackService == null && serviceStartRequested) {
            // We started the (now-foreground) service but never got a binder to stopSelf()
            // through — covers both "bind still pending" and "bindService returned false".
            // Without this the service and its notification would outlive the player.
            try { stopService(Intent(this, PlaybackService::class.java)) } catch (_: Throwable) { }
        }
        serviceStartRequested = false
        serviceBindRequested = false
        serviceBound = false
        playbackService = null
    }

    // --- Custom Track Menu (SBS-aware) — used for audio and subtitle selection ---
    private data class TrackMenuItem(
        val label: String,
        val isAuto: Boolean,
        val isOff: Boolean,
        val group: Tracks.Group?,
        val trackIndexInGroup: Int?
    )

    private fun buildTrackMenuItems(trackType: Int): List<TrackMenuItem> {
        val items = mutableListOf<TrackMenuItem>()
        when (trackType) {
            C.TRACK_TYPE_AUDIO -> items += TrackMenuItem("Auto", isAuto = true, isOff = false, group = null, trackIndexInGroup = null)
            C.TRACK_TYPE_TEXT -> items += TrackMenuItem(getString(R.string.subtitle_off), isAuto = false, isOff = true, group = null, trackIndexInGroup = null)
        }
        val tracks = player?.currentTracks ?: return items
        val nameProvider = DefaultTrackNameProvider(resources)
        for (i in 0 until tracks.groups.size) {
            val g = tracks.groups[i]
            if (g.type != trackType) continue
            for (j in 0 until g.length) {
                if (!g.isTrackSupported(j)) continue
                val f = g.getTrackFormat(j)
                val pretty = nameProvider.getTrackName(f)
                items += TrackMenuItem(pretty, isAuto = false, isOff = false, group = g, trackIndexInGroup = j)
            }
        }
        return items
    }

    // Backward-compat alias used by remote control / public API
    private fun buildAudioMenuItems(): List<TrackMenuItem> = buildTrackMenuItems(C.TRACK_TYPE_AUDIO)

    private fun showTrackMenu(trackType: Int) {
        val root = audioMenuRoot ?: return
        val center = audioMenuCenter ?: return
        val left = audioMenuLeft ?: return
        val right = audioMenuRight ?: return
        center.removeAllViews()
        left.removeAllViews()
        right.removeAllViews()
        val items = buildTrackMenuItems(trackType)
        val sbs = getStereoSbs()
        val titleStr = when (trackType) {
            C.TRACK_TYPE_TEXT -> getString(R.string.select_subtitle)
            else -> getString(R.string.select_audio_track)
        }
        val isTextDisabled = trackSelector?.parameters?.getRendererDisabled(C.TRACK_TYPE_TEXT) == true ||
            (trackSelector?.parameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true)
        // First D-pad-focusable row, so we can move focus into the menu when it opens (TV/box).
        var firstFocusable: View? = null
        fun addItemsTo(container: LinearLayout) {
            container.removeAllViews()
            fun dp(value: Int): Int =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value.toFloat(),
                    resources.displayMetrics
                ).toInt()
            val title = TextView(this).apply {
                text = titleStr
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            val scroll = ScrollView(this).apply {
                val h = (audioMenuRoot?.height ?: 0)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (h > 0) (h * 0.6f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(title)
            // Audio menu gets a first-row loudness boost control so the user can lift quiet sources.
            if (trackType == C.TRACK_TYPE_AUDIO) {
                val boostTv = TextView(this).apply {
                    text = boostLabel(getVolumeBoostMb())
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    isAllCaps = false
                    alpha = 0.95f
                    setOnClickListener {
                        val label = cycleVolumeBoost()
                        text = label
                        Toast.makeText(this@PlayerActivity, label, Toast.LENGTH_SHORT).show()
                    }
                }
                com.teleteh.xplayer2.ui.util.TvFocus.makeFocusableItem(boostTv)
                if (firstFocusable == null) firstFocusable = boostTv
                inner.addView(boostTv)

                // Spatial audio, on the routes that can do it. The row is only drawn where it
                // would do something: on a phone speaker or a plain stereo sink the platform
                // answers no, and a switch that visibly does nothing is worse than no switch.
                if (StereoFolddownAudioProcessor.platformWillSpatialize(applicationContext, 6, 48_000)) {
                    val spatialTv = TextView(this).apply {
                        text = spatialLabel(isSpatialAudioEnabled())
                        setPadding(dp(16), dp(10), dp(16), dp(10))
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        isAllCaps = false
                        alpha = 0.95f
                        setOnClickListener {
                            val on = !isSpatialAudioEnabled()
                            setSpatialAudioEnabled(on)
                            text = spatialLabel(on)
                            Toast.makeText(this@PlayerActivity, spatialLabel(on), Toast.LENGTH_SHORT).show()
                        }
                    }
                    com.teleteh.xplayer2.ui.util.TvFocus.makeFocusableItem(spatialTv)
                    inner.addView(spatialTv)
                }
            }
            items.forEach { item ->
                val isSelected = when {
                    item.isOff -> trackType == C.TRACK_TYPE_TEXT && isTextDisabled
                    item.isAuto -> false
                    item.group != null && item.trackIndexInGroup != null -> {
                        try { item.group.isTrackSelected(item.trackIndexInGroup) } catch (_: Exception) { false }
                    }
                    else -> false
                }
                val tv = TextView(this).apply {
                    text = item.label
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    isAllCaps = false
                    if (isSelected) {
                        setTypeface(typeface, Typeface.BOLD)
                        // Mark the active track with a filled accent pill. The old bold+alpha-only
                        // cue is invisible on a TV / external panel from across the room, so the
                        // current audio track / subtitle wasn't readable there (main-screen menu).
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat()
                            setColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                        }
                    }
                    alpha = if (isSelected) 1.0f else 0.85f
                    setOnClickListener {
                        applyTrackSelection(item, trackType)
                        hideTrackMenu()
                    }
                }
                com.teleteh.xplayer2.ui.util.TvFocus.makeFocusableItem(tv)
                if (firstFocusable == null) firstFocusable = tv
                inner.addView(tv)
            }
            scroll.addView(inner)
            container.addView(scroll)
        }
        if (sbs) {
            center.visibility = View.GONE
            left.visibility = View.GONE
            right.visibility = View.VISIBLE
            addItemsTo(right)
        } else {
            center.visibility = View.VISIBLE
            left.visibility = View.GONE
            right.visibility = View.GONE
            addItemsTo(center)
        }
        root.visibility = View.VISIBLE
        // Move focus into the menu so D-pad works immediately (no-op on touch devices).
        firstFocusable?.let { fv -> root.post { fv.requestFocus() } }
    }

    private fun showAudioMenu() = showTrackMenu(C.TRACK_TYPE_AUDIO)
    private fun showSubtitleMenu() = showTrackMenu(C.TRACK_TYPE_TEXT)

    private fun hideTrackMenu() {
        audioMenuRoot?.visibility = View.GONE
    }

    private fun hideAudioMenu() = hideTrackMenu()

    // --- Stream quality picker (VK / OK.ru multi-quality sources) ---

    /** Show/hide the player overlay's quality button based on how many qualities the source has. */
    private fun updateQualityButtonVisibility() {
        btnQualityRef?.visibility = if (streamVariants.size > 1) View.VISIBLE else View.GONE
    }

    /**
     * Quality chooser, rendered into the same SBS-aware container the audio/subtitle menu uses so
     * it sits correctly per-eye when the picture is split. Lists every variant (highest first),
     * marks the active one, and switches the ExoPlayer source on tap. Shown only when there are
     * ≥2 qualities.
     */
    private fun showQualityMenu() {
        if (streamVariants.size <= 1) return
        val root = audioMenuRoot ?: return
        val center = audioMenuCenter ?: return
        val left = audioMenuLeft ?: return
        val right = audioMenuRight ?: return
        center.removeAllViews()
        left.removeAllViews()
        right.removeAllViews()
        val sbs = getStereoSbs()
        var firstFocusable: View? = null
        fun dp(value: Int): Int =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
            ).toInt()
        fun addItemsTo(container: LinearLayout) {
            container.removeAllViews()
            val title = TextView(this).apply {
                text = getString(R.string.quality)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            val scroll = ScrollView(this).apply {
                val h = (audioMenuRoot?.height ?: 0)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (h > 0) (h * 0.6f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(title)
            streamVariants.forEachIndexed { index, variant ->
                val isSelected = index == selectedVariantIndex
                val tv = TextView(this).apply {
                    text = variant.label
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    isAllCaps = false
                    if (isSelected) {
                        setTypeface(typeface, Typeface.BOLD)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat()
                            setColor(themeColor(androidx.appcompat.R.attr.colorPrimary))
                        }
                    }
                    alpha = if (isSelected) 1.0f else 0.85f
                    setOnClickListener {
                        selectQuality(index)
                        hideTrackMenu()
                    }
                }
                com.teleteh.xplayer2.ui.util.TvFocus.makeFocusableItem(tv)
                if (firstFocusable == null) firstFocusable = tv
                inner.addView(tv)
            }
            scroll.addView(inner)
            container.addView(scroll)
        }
        if (sbs) {
            center.visibility = View.GONE
            left.visibility = View.GONE
            right.visibility = View.VISIBLE
            addItemsTo(right)
        } else {
            center.visibility = View.VISIBLE
            left.visibility = View.GONE
            right.visibility = View.GONE
            addItemsTo(center)
        }
        root.visibility = View.VISIBLE
        firstFocusable?.let { fv -> root.post { fv.requestFocus() } }
    }

    /**
     * Switch the ExoPlayer source to the chosen quality variant, preserving playback position and
     * play/pause state. The stereo (OU/SBS) and resize state live in the activity / OuToSbsGlView
     * and are NOT tied to the media item, so they survive this swap untouched. No-op if the index
     * is out of range or already selected.
     */
    private fun switchQuality(index: Int) {
        val variant = streamVariants.getOrNull(index) ?: return
        if (index == selectedVariantIndex) return
        val exo = player ?: return
        val url = variant.url
        selectedVariantIndex = index
        resolvedStreamUri = Uri.parse(url)
        val pos = exo.currentPosition
        val wasPlaying = exo.playWhenReady
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.seekTo(pos)
        exo.playWhenReady = wasPlaying
        android.util.Log.i("XPlayer2", "Quality switched to ${variant.label} @${pos}ms (playing=$wasPlaying)")
        Toast.makeText(this, variant.label, Toast.LENGTH_SHORT).show()
    }

    private fun applyTrackSelection(item: TrackMenuItem, trackType: Int) {
        val selector = trackSelector ?: return
        val exo = player ?: return
        val builder = selector.buildUponParameters()
        builder.clearOverridesOfType(trackType)
        if (item.isOff) {
            builder.setTrackTypeDisabled(trackType, true)
        } else {
            builder.setTrackTypeDisabled(trackType, false)
            if (!item.isAuto) {
                val group = item.group ?: return
                val index = item.trackIndexInGroup ?: return
                builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, listOf(index)))
            }
        }
        selector.parameters = builder.build()
        exo.playWhenReady = exo.playWhenReady
        val toastText = when {
            item.isOff -> getString(R.string.subtitle_off)
            item.isAuto -> "Audio: Auto"
            else -> item.label
        }
        Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
    }

    // Backward-compat alias used by getSelectedAudioTrackIndex/selectAudioTrack remote control API
    private fun applyAudioSelection(item: TrackMenuItem) = applyTrackSelection(item, C.TRACK_TYPE_AUDIO)

    private fun tryShowExternalPresentation() {
        // Find external display for Presentation
        val ext = DisplayUtils.findUltraWideExternalDisplay(this) ?: run {
            dismissPresentation()
            return
        }
        // The desktop is shown as it arrives, filling the panel — the same thing iOS does — and
        // NOT hung in the world.
        //
        // `VirtualDesktopGlView` and its head tracking work and are kept, but a screen fixed in
        // the world is only worth its cost as part of the spaces mode that is not built yet:
        // several windows placed around the viewer, which is what makes looking around mean
        // something. On its own it bought one screen you have to turn your head to read, and it
        // brought both of that idea's unsolved problems with it — a canvas that has to fit inside
        // roughly 40°×23° to be seen whole, and a yaw that drifts because a gyro with no absolute
        // reference always drifts. Neither is worth solving for a single window; both have to be
        // solved for spaces, and that is when this comes back.
        //
        // The flag stays where the decision belongs, so turning it on is one line and the
        // presentation is still rebuilt when the mode changes.
        val wantWorldFixed = isPcLinkMode && SPACES_MODE

        // Already showing on this display in the right mode
        if (presentation?.display?.displayId == ext.displayId &&
            presentation?.isWorldFixedDesktop == wantWorldFixed
        ) return

        dismissPresentation()

        // Create Presentation and route video there
        val pres = ExternalPlayerPresentation(this, ext, wantWorldFixed, hostsExoPlayer = !isPcLinkMode) { surface ->
            presentationSurface = surface
            if (isPcLinkMode) {
                // PC Link has no ExoPlayer to re-target: the decoder takes the new surface (and
                // detaches on null, when the goggles' panel powers down).
                if (surface != null) glView?.visibility = View.GONE
                updatePcLinkSurface()
                return@ExternalPlayerPresentation
            }
            if (surface != null) {
                player?.let { exo ->
                    exo.setVideoSurface(surface)
                }
                // Hide local GL view - video goes to Presentation only
                glView?.visibility = View.GONE
            } else {
                player?.clearVideoSurface()
            }
        }
        presentation = pres
        presentationDisplayId = ext.displayId
        try {
            pres.show()
            
            // Clear main surface - video will render on Presentation
            player?.clearVideoSurface()
            glView?.visibility = View.GONE

            pres.setPlayer(player)
            // World-fixed desktop: head orientation is polled straight off the glasses IMU each
            // rendered frame (volatile reads; null until telemetry flows, which renders the
            // canvas head-fixed dead ahead — a graceful stand-in, not an error).
            pres.desktopView?.setOrientationProvider { acquiredGlasses?.headOrientationDegrees() }
            // The Presentation's glView is now the active render target — push the full
            // current render config (SBS / source-layout / resize / aspect) and re-derive
            // the mode for this display so resize, OU/SBS and parallax all take effect.
            applyRenderMode()
            // Same for PC Link: the decoder must follow the render target that just changed.
            if (isPcLinkMode) updatePcLinkSurface()
            updatePcLinkImu()
            if (lazy3dEnabled) reapplyLazy3dToActiveView()
        } catch (e: Throwable) {
            android.util.Log.e("XPlayer2", "Failed to show Presentation", e)
            presentation = null
            presentationDisplayId = -1
            // Restore local rendering
            glView?.visibility = View.VISIBLE
            glSurface?.let { player?.setVideoSurface(it) }
            if (isPcLinkMode) updatePcLinkSurface()
        }
        updatePlaybackService()
    }

    private fun dismissPresentation() {
        if (presentation != null) {
            presentation?.dismiss()
            presentation = null
            presentationDisplayId = -1
            presentationSurface = null
            // Restore local GL view
            glView?.visibility = View.VISIBLE
            glSurface?.let { player?.setVideoSurface(it) }
            // Local glView is the active target again — re-push render config to it.
            applyRenderMode()
            // …and hand the decoder the local surface, for the same reason.
            if (isPcLinkMode) updatePcLinkSurface()
            if (lazy3dEnabled && !hasLazy3dDisplay()) {
                // The glasses were the only stereo target (phone screen isn't ultrawide):
                // an SBS pair has nowhere to go now, so shut Lazy 3D down instead of burning
                // inference on a split picture nobody can view.
                setLazy3dEnabled(false)
                btnSbsRef?.let { applySbsButtonVisual(it) }
                RemoteControlActivity.currentInstance?.syncControls()
            } else if (lazy3dEnabled) {
                reapplyLazy3dToActiveView()
            }
            // No presentation → no world-fixed desktop → the IMU has nothing to feed.
            updatePcLinkImu()
            updatePlaybackService()
        }
    }

    // --- External-display (goggles) hot-plug handling ---
    // The XREAL panel has a proximity sensor: taking the goggles off powers the panel down and
    // its external display goes away; putting them on brings it back. Each transition fires a
    // BURST of add/remove/change events (the panel re-enumerates, often with brand-new display
    // ids), so reacting per-event is unreliable — a trailing "added" during removal churn would
    // cancel a pending "gone" check. Instead we debounce a single reconcile that runs once the
    // burst settles and inspects the *final* state. ([displayListener] was declared long ago but
    // never actually registered — this wires it up.)
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = scheduleExternalReconcile()
            override fun onDisplayRemoved(displayId: Int) = scheduleExternalReconcile()
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) scheduleExternalReconcile()
            }
        }
        dm.registerDisplayListener(l, uiHandler)
        displayListener = l
    }

    private fun unregisterDisplayListener() {
        uiHandler.removeCallbacks(externalReconcile)
        displayListener?.let { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        displayListener = null
    }

    private val externalReconcile = Runnable { reconcileExternalDisplay() }

    /**
     * The one-shot a starting cast arms, because there is no event to wait for when the panel never
     * comes up at all — see [ExternalPanelPolicy.ENTRY_GRACE_MS]. Same check, but it explains the
     * ending, since from the user's side nothing happened for it to be the consequence of.
     */
    private val pcLinkPanelGrace = Runnable { reconcileExternalDisplay(explainMissingPanel = true) }

    // Coalesce the hot-plug burst: both add and remove reschedule the SAME check, so it never
    // gets starved by a trailing event and fires once the dust settles.
    private fun scheduleExternalReconcile() {
        // A real display event answers the entry question too, and does it sooner.
        uiHandler.removeCallbacks(pcLinkPanelGrace)
        uiHandler.removeCallbacks(externalReconcile)
        uiHandler.postDelayed(externalReconcile, ExternalPanelPolicy.RECONCILE_DEBOUNCE_MS)
    }

    private fun reconcileExternalDisplay(explainMissingPanel: Boolean = false) {
        // PC Link has no ExoPlayer, and this used to return here because of that — so switching
        // the glasses between 2D and 3D mid-cast, which tears the panel down and brings it back
        // at a different size, was never reconciled at all: the presentation was not rebuilt and
        // the phone was left showing this window's empty grey.
        if (player == null && !isPcLinkMode) return
        val dm = getSystemService(DisplayManager::class.java)
        val ext = DisplayUtils.findUltraWideExternalDisplay(this)
        val extAlive = ext != null &&
            (dm?.getDisplay(ext.displayId)?.state ?: Display.STATE_ON) != Display.STATE_OFF
        if (extAlive) {
            // Panel present (e.g. glasses connected after starting on the phone): move the
            // picture to the goggles, push the saved mode and bring up the remote.
            val wasShowing = presentation != null
            tryShowExternalPresentation()
            if (isPcLinkMode) {
                // The panel just changed shape (a 2D↔3D switch is a teardown and a re-add), so
                // the renderer has to be told what it is looking at now — and the PC too, since
                // its stereo decision follows the glasses.
                applyPcLinkRenderConfig()
                updatePcLinkSurface()
                pcLinkClient?.reportGlasses(glassesAreStereo())
            }
            if (presentation != null && !wasShowing) {
                // No longer force a remembered glasses mode here (it sometimes restored the wrong one);
                // the panel keeps its current mode and the picker reflects it.
                showRemoteControlFront()
            }
        } else if (ExternalPanelPolicy.shouldEndSession(
                panelAlive = false, hasPresentation = presentation != null, isPcLink = isPcLinkMode
            )
        ) {
            onExternalPanelLost(explain = explainMissingPanel)
        }
    }

    // Goggles came off (proximity sensor cut the panel) or the external display was unplugged:
    // just stop, as if the user hit Stop. The position is saved, so they pick the clip back up
    // from Recent when ready. Far simpler and more predictable than juggling player/remote layers.
    private fun onExternalPanelLost(explain: Boolean = false) {
        android.util.Log.i("XPlayer2", "External panel gone -> stop playback")
        // Nothing was ever on the glasses for the user to notice going away, so say what happened —
        // otherwise a cast that ends on its own a few seconds after it started reads as a crash.
        if (explain) {
            Toast.makeText(this, R.string.pclink_needs_glasses_body, Toast.LENGTH_LONG).show()
        }
        saveProgress()
        // The glasses are the whole point of a cast: with them gone there is nobody to show the
        // desktop to, and a session left running would go on costing the PC its bitrate and its
        // speakers. Say goodbye properly (the PC gets its sound back) rather than letting the
        // socket rot.
        if (isPcLinkMode) exitPcLink()
        finishAndClose()
    }

    private fun saveProgress() {
        val uri = sourceUri ?: return
        // Recents are keyed by the durable identity when one was provided (e.g. Yandex Disk's
        // yadi.sk/i/… link), since the actual play URI (a signed href) is ephemeral.
        val keyUri = recentKeyUri ?: uri
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0L)
        val duration = exo.duration.takeIf { it > 0 } ?: 0L
        // Prefer Media3 metadata title if available; fallback to display name/lastPath
        val title = bestTitleForCurrent()
        // Extract optional frame-packing information from URI query (?frame-packing=3|4)
        val framePacking: Int? = try {
            uri.getQueryParameter("frame-packing")?.toIntOrNull()
        } catch (_: Throwable) {
            null
        }
        val entry = RecentEntry(
            uri = keyUri.toString(),
            title = title,
            lastPositionMs = position,
            durationMs = duration,
            lastPlayedAt = System.currentTimeMillis(),
            framePacking = framePacking,
            sbsShiftEnabled = sbsShiftEnabled,
            sourceType = RecentEntry.detectSourceType(keyUri),
            resizeMode = resizeMode,
            // Persist the effective stereo mode (auto-detected or manual) as the single source of
            // truth — restored on reopen and used for the history badge. 0 = 2D, 1 = OU→SBS, 2 = SBS.
            stereoMode = stereoMode.toInt(),
            volumeBoostMb = volumeBoostMb
        )
        RecentStore(this).upsert(entry)
    }

    /**
     * Three layouts the source frame might be in.
     */
    private enum class SourceLayout { Mono, Sbs, Ou }

    /**
     * Single source of truth for "what is the layout of this video?". Metadata first
     * (MKV StereoMode / MP4 st3d/sv3d in Format.stereoMode), aspect heuristic as fallback.
     * Online streams almost never carry stereo metadata, so for them we rely entirely on the
     * aspect fallback (which is safer than filename parsing — filenames are arbitrary).
     *
     * Aspect thresholds:
     *   - aspect ≥ 2.6 → SBS source (2.67 = Full-SBS of two 4:3 halves, 3.55 = 32:9 Full-SBS of
     *     two 16:9 halves). The old 1.95 threshold misclassified EVERY ordinary cinemascope 2D
     *     film (2:1, 21:9 = 2.33, 2.35:1, 2.40:1 — i.e. most downloaded movies) as SBS: the
     *     picture auto-split in half on open, and the mode cycle skipped its 2D→3D step (which
     *     is gated on Mono), so those files could never reach Lazy 3D at all. Real mono cinema
     *     tops out around 2.40 (rare 2.55/2.76 anamorphics are accepted collateral); real SBS by
     *     aspect starts at 2.67.
     *   - aspect ≤ 1.05 → OU source (1:1 stacked, 8:9 Half-OU).
     *   - otherwise   → mono (ambiguous Full-* formats fall here and need a manual SBS toggle).
     */
    private fun detectSourceLayout(): SourceLayout {
        when (detectedSourceStereoMode) {
            C.STEREO_MODE_LEFT_RIGHT -> return SourceLayout.Sbs
            C.STEREO_MODE_TOP_BOTTOM -> return SourceLayout.Ou
            C.STEREO_MODE_MONO -> return SourceLayout.Mono
        }
        val w = lastVideoWidth
        val h = lastVideoHeight
        if (w <= 0 || h <= 0) return SourceLayout.Mono
        val aspect = w.toFloat() / h.toFloat()
        return when {
            aspect >= 2.6f -> SourceLayout.Sbs
            aspect <= 1.05f -> SourceLayout.Ou
            else -> SourceLayout.Mono
        }
    }

    /** The glView that currently owns the decoded frames: the Presentation's when glasses are
     *  connected, otherwise the activity's local one. All render-state must target this view. */
    private fun activeGlView(): OuToSbsGlView? = presentation?.renderView ?: glView

    /** Whether the active output display is an ultrawide (≈32:9) panel — i.e. the glasses. */
    private fun activeDisplayIsUltrawide(): Boolean {
        return try {
            val pres = presentation
            if (pres != null) {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") pres.display.getMetrics(dm)
                dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1) >= 3.2f
            } else {
                val dm = resources.displayMetrics
                dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1) >= 3.2f
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Push the current desired render config to the active GL view. Called whenever the config
     * changes OR the active view changes (presentation created/dismissed), so the freshly
     * active view immediately shows the right thing.
     */
    private fun applyRenderConfig() {
        val v = activeGlView() ?: return
        v.setSbsEnabled(getStereoSbs())
        v.setSourceIsSbs(renderSourceIsSbs)
        v.setDuplicateMonoToSbs(renderDuplicateMono)
        v.updateResizeMode(resizeMode)
        if (lastVideoWidth > 0 && lastVideoHeight > 0) {
            v.updateVideoAspectRatio(lastVideoWidth, lastVideoHeight)
        }
    }

    /**
     * Decide what to render from the detected source layout. This is the single place that
     * maps "what is this clip" → "how do we show it", per the product rules:
     *
     *   - SBS mode → split the frame left/right per eye (half-SBS passthrough OR full-SBS).
     *   - OU mode  → convert over-under to SBS (the app's signature feature).
     *   - 2D (Off) → do NOT cut. On the glasses' ultrawide panel duplicate the frame into both
     *                eye-halves so it sits centred per eye; Lazy-3D is offered for depth synth.
     *
     * Auto-detection picks the mode from the source layout. Half-SBS (wide) and Half/Full-OU
     * (tall) are detectable by aspect; Full-SBS in a 16:9 frame is NOT (it looks like 2D), so
     * the user cycles the SBS button to it. A manual choice ([sbsExplicitlyConfigured]) sticks
     * for the clip and is persisted.
     */
    private fun applyRenderMode() {
        // PC Link picks its layout from the server's `config`, never from aspect-ratio detection:
        // the desktop canvas is ultrawide by design, which the heuristic below would read as SBS.
        if (isPcLinkMode) {
            applyPcLinkRenderConfig()
            return
        }
        if (!sbsExplicitlyConfigured) {
            // Auto: derive the stereo mode from the detected source layout.
            val layout = detectSourceLayout()
            stereoMode = when (layout) {
                SourceLayout.Sbs -> StereoMode.Sbs
                SourceLayout.Ou -> StereoMode.Ou
                // On the ultrawide goggle panel (3D/SBS display mode) an undetectable clip with no
                // saved format is most likely SBS content — default to SBS. On the phone / 2D
                // panel, keep plain 2D.
                SourceLayout.Mono -> if (activeDisplayIsUltrawide()) StereoMode.Sbs else StereoMode.Off
            }
            // Lock in the goggle-panel SBS default as an explicit choice: otherwise it's re-derived
            // every applyRenderMode, and when the panel goes away (stop/disconnect) it flips back to
            // 2D and onStop's saveProgress overwrites the SBS we saved — so history would reopen 2D.
            if (layout == SourceLayout.Mono && stereoMode == StereoMode.Sbs) {
                sbsExplicitlyConfigured = true
            }
        }
        when (stereoMode) {
            StereoMode.Sbs -> { renderSourceIsSbs = true; renderDuplicateMono = false }
            StereoMode.Ou -> { renderSourceIsSbs = false; renderDuplicateMono = false }
            StereoMode.Off -> {
                renderSourceIsSbs = false
                // On the wide glasses panel, centre 2D per eye by duplicating into both halves.
                renderDuplicateMono = activeDisplayIsUltrawide()
            }
        }
        val aspect = if (lastVideoHeight > 0) lastVideoWidth.toFloat() / lastVideoHeight else 0f
        android.util.Log.i(
            "XPlayer2",
            "applyRenderMode: ${lastVideoWidth}x${lastVideoHeight} aspect=${"%.2f".format(aspect)} stereoMode=$stereoMode sourceIsSbs=$renderSourceIsSbs dup=$renderDuplicateMono manual=$sbsExplicitlyConfigured"
        )
        applyRenderConfig()
        btnSbsRef?.let { applySbsButtonVisual(it) }
        // Swap the Shift / Lazy-3D slot to match the new stereo mode.
        updateStereoControlButtons()
        // The phone-side remote sets its labels once on resume — if the mode was auto-derived
        // here afterwards (e.g. Full-SBS detected on the first frame), refresh it so its SBS
        // button doesn't keep showing "2D" while the picture is correctly SBS.
        RemoteControlActivity.currentInstance?.syncControls()
        applySbsShiftIfNeeded()
        applyVideoPipeline()
        maybeWarnGlassesNot3d()
    }

    // Last time the "glasses aren't in 3D mode" warning was shown — applyRenderMode() runs on
    // every mode/display/config change, so without this the dialog would restack itself.
    private var glassesNot3dWarnedAtMs = 0L

    /**
     * Warn (don't block) when a stereo output mode is active but the goggles' panel is still in
     * its 2D display mode: the SBS halves get squeezed into one 16:9 frame and "nothing works".
     * Detection is by display geometry — in 3D mode the glasses present an ultrawide (32:9) panel,
     * in 2D a 16:9 one — which is the ground truth regardless of brand or how the mode was
     * changed (our button, the glasses' own button, or never at all). Deliberately ONLY a
     * warning: auto-switching the panel here (or refusing stereo modes) is exactly what used to
     * glitch madly, so the user stays in control. Dialog auto-dismisses after 5 s; shown on
     * whichever activity is actually in front (the remote, when the picture is on the glasses).
     */
    private fun maybeWarnGlassesNot3d() {
        if (!(getStereoSbs() || lazy3dEnabled)) return   // 2D output — nothing to warn about
        if (presentation == null) return                  // picture not on the glasses
        if (activeDisplayIsUltrawide()) return            // panel IS in 3D mode — all good
        val now = android.os.SystemClock.uptimeMillis()
        if (now - glassesNot3dWarnedAtMs < 30_000L) return
        glassesNot3dWarnedAtMs = now
        // Same foreground rule as the depth-model picker: with the picture on the glasses, the
        // remote is what's in front on the phone — a dialog on the backgrounded player never shows.
        val host: android.app.Activity = RemoteControlActivity.currentInstance ?: this
        if (host.isFinishing || host.isDestroyed) return
        try {
            val dlg = AlertDialog.Builder(host)
                .setMessage(R.string.glasses_not_3d_warning)
                .setPositiveButton(android.R.string.ok, null)
                .create()
            dlg.show()
            Handler(mainLooper).postDelayed({
                try { if (dlg.isShowing) dlg.dismiss() } catch (_: Throwable) { }
            }, 5_000L)
        } catch (t: Throwable) {
            // A dying host window must never take playback down with it.
            android.util.Log.w("XPlayer2", "glasses-not-3D warning failed to show: ${t.message}")
        }
    }

    /**
     * Returns true on unmetered networks (Wi-Fi, Ethernet) — false on cellular or unknown.
     * Used to gate features like forcing the highest HLS variant, which would otherwise
     * blow the user's cellular cap or battery.
     */
    private fun isOnUnmeteredNetwork(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        // hasCapability(NET_CAPABILITY_NOT_METERED) is the right signal — Wi-Fi can be
        // explicitly metered, Ethernet is never, and cellular is always metered.
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Throwable) {
        false
    }

    /**
     * Switch the activity window between SDR and HDR colour mode based on the selected
     * video track. HDR mode requires API 26+ and is silently ignored by devices that don't
     * actually have an HDR-capable display, so it's safe to set unconditionally on supported
     * sources. The DIRECT video pipeline (PlayerView SurfaceView) benefits most — HDR
     * content can then bypass the SDR tone-map that Android would otherwise apply.
     */
    private fun updateHdrColorMode(isHdr: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val target = if (isHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        if (window.colorMode != target) {
            try {
                window.colorMode = target
                android.util.Log.i("XPlayer2", "Window colorMode = ${if (isHdr) "HDR" else "DEFAULT"}")
            } catch (e: Throwable) {
                android.util.Log.w("XPlayer2", "Failed to set window colorMode", e)
            }
        }
    }

    /**
     * Decide whether to route video through our GL pipeline (OuToSbsGlView) or straight to
     * PlayerView's SurfaceView, and apply the switch on ExoPlayer if it changed.
     *
     * Direct SurfaceView is preferable when no transform is needed: less GPU work, lower
     * battery, and HDR/wide-gamut content reaches the display without an intermediate 8-bit
     * RGB texture pass. We fall back to GL whenever we have to manipulate the picture:
     *   - SBS toggle is ON (OU→SBS or SBS-source split)
     *   - Ultrawide screen + non-stereo source (we duplicate mono into both halves)
     *   - An external Presentation surface owns the output
     */
    private fun applyVideoPipeline() {
        val exo = player ?: return
        if (presentation != null) {
            // External display owns the surface; do nothing here.
            currentPipeline = VideoPipeline.GL
            return
        }

        // GL pass is needed whenever we transform the picture: SBS split/convert, or
        // duplicate-mono on an ultrawide panel. Plain mono passthrough goes DIRECT for
        // best quality / battery. lazy3dEnabled is a safety net: its display gate
        // (hasLazy3dDisplay) means GL is normally already on via presentation/ultrawide,
        // but the depth warp + frame readback live in the GL view, so a transitional
        // state must never leave Lazy 3D running on a DIRECT pipeline (it would silently
        // render nothing while burning inference).
        val needsGl = getStereoSbs() || renderDuplicateMono || lazy3dEnabled
        val target = if (needsGl) VideoPipeline.GL else VideoPipeline.DIRECT
        if (target == currentPipeline) return

        currentPipeline = target
        when (target) {
            VideoPipeline.GL -> {
                playerView.videoSurfaceView?.visibility = View.GONE
                glView?.visibility = View.VISIBLE
                glSurface?.let { exo.setVideoSurface(it) }
                android.util.Log.i("XPlayer2", "Pipeline: GL")
            }
            VideoPipeline.DIRECT -> {
                playerView.videoSurfaceView?.visibility = View.VISIBLE
                glView?.visibility = View.GONE
                // Disconnect from GL surface, then rebind PlayerView so it grabs its own surface back.
                exo.clearVideoSurface()
                playerView.player = null
                playerView.player = exo
                android.util.Log.i("XPlayer2", "Pipeline: DIRECT (no GL pass)")
            }
        }
    }

    private fun applyResizeMode() {
        btnResizeModeRef?.text = resizeModeLabel(resizeMode)
        activeGlView()?.updateResizeMode(resizeMode)
    }

    private fun bestTitleForCurrent(): String {
        val cached = currentResolvedTitle
        if (!cached.isNullOrBlank()) return cached
        val uri = sourceUri
        val exo = player
        val metaTitle = exo?.currentMediaItem?.mediaMetadata?.title?.toString()
        if (!metaTitle.isNullOrBlank()) return metaTitle
        if (uri != null) {
            resolveDisplayName(uri)?.let { return it }
            return uri.lastPathSegment ?: uri.toString()
        }
        return metaTitle ?: ""
    }

    private fun updateCenterTitle() {
        val t = bestTitleForCurrent()
        currentResolvedTitle = t
        titleCenterView?.text = t
    }

    private fun tryProbeTitleFromRetriever() {
        val uri = sourceUri ?: return
        // Run on background thread to avoid blocking UI
        Thread {
            val title = runCatching {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, uri)
                val t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                r.release()
                t
            }.getOrNull()
            if (!title.isNullOrBlank()) {
                runOnUiThread {
                    if (currentResolvedTitle.isNullOrBlank() || currentResolvedTitle != title) {
                        currentResolvedTitle = title
                        titleCenterView?.text = title
                        saveProgress()
                    }
                }
            }
        }.start()
    }

    // --- Stereo mode state ---
    // Cycle the SBS button: 2D → OU→SBS → SBS → 2D. Manual press wins over auto-detection
    // for the rest of this clip and is persisted, so Full-SBS/Full-OU clips (which look like
    // 2D by resolution) keep the user's choice on reopen.
    private fun cycleStereoMode() {
        // One button, four states: 2D → Lazy 3D → OU→SBS → SBS → 2D. Lazy 3D is folded into this
        // cycle (it used to be a separate button) and is skipped on devices that can't run it.
        // TEMPORARY beta A/B: entering Lazy 3D asks which depth model to use; the dialog callback
        // finishes the enable + UI refresh, so we return early here.
        // Offer the 2D→3D step ONLY for a genuine 2D (mono) source — not for SBS/OU films, which are
        // already stereo: there you just want the 2D↔SBS toggle, and a Lazy 3D prompt would hijack it
        // (the tester's "after SBS→2D I can't get back to SBS"). Source layout comes from the stream
        // metadata / aspect ratio — reliable, and independent of glasses detection.
        if (stereoMode == StereoMode.Off && !lazy3dEnabled && isLazy3dSupported() &&
            detectSourceLayout() == SourceLayout.Mono) {
            offerDepthModelPicker()
            return
        }
        when {
            stereoMode == StereoMode.Off && lazy3dEnabled -> {
                setLazy3dEnabled(false); stereoMode = StereoMode.Ou        // Lazy 3D → OU→SBS
            }
            stereoMode == StereoMode.Off -> stereoMode = StereoMode.Ou     // 2D → OU→SBS (no Lazy 3D)
            stereoMode == StereoMode.Ou -> stereoMode = StereoMode.Sbs     // OU→SBS → SBS
            else -> stereoMode = StereoMode.Off                            // SBS → 2D
        }
        finishStereoModeChange()
    }

    private fun finishStereoModeChange() {
        sbsExplicitlyConfigured = true
        Toast.makeText(this, getStereoModeLabel(), Toast.LENGTH_SHORT).show()
        applyRenderMode()
        if (lazy3dEnabled) reapplyLazy3dToActiveView()
        updateStereoControlButtons()
        btnSbsRef?.let { applySbsButtonVisual(it) }
        RemoteControlActivity.currentInstance?.syncControls()
        saveProgress()
    }

    /**
     * Show the depth-model picker on whichever screen is actually in front. When the picture is
     * on the glasses, [showRemoteControlFront] has pushed [RemoteControlActivity] in front of
     * this activity on the phone — a Dialog built with THIS (backgrounded) activity as context
     * doesn't show/isn't interactable, which used to make the stereo-mode cycle button appear
     * stuck on 2D (it bailed into the dialog call every press and never reached the mode switch
     * below). So delegate to the activity that's actually foreground; fall back to showing it
     * here directly when there's no external presentation (phone-only playback).
     */
    private fun offerDepthModelPicker() {
        // With a single selectable model there's nothing to A/B — enable it directly, no dialog.
        // (The picker below stays wired for the day a second model becomes selectable again.)
        val selectable = DepthModelManager.DepthModel.values().filter { it.selectable }
        if (selectable.size == 1) {
            applyChosenDepthModel(selectable.single())
            return
        }
        val remote = RemoteControlActivity.currentInstance
        if (presentation != null && remote != null) {
            remote.showDepthModelDialog()
        } else {
            promptDepthModelThenEnableLazy3d()
        }
    }

    /**
     * TEMPORARY beta: ask which depth model to use before turning Lazy 3D on, so testers can A/B
     * MiDaS vs Depth-Anything-V2. The choice is persisted and applied on the next Lazy 3D start
     * (this one). Dialog is navigable by touch (phone) or the remote d-pad (glasses). Remove this
     * prompt once we know which model wins.
     */
    private fun promptDepthModelThenEnableLazy3d() {
        // Remote-styled custom dialog (dark rc_card + RemoteRow buttons) — see dialog_depth_model.xml.
        // Navigable by touch (phone) or the remote d-pad (glasses-only boxes). Cancel (back/outside)
        // leaves Lazy 3D off. TEMPORARY beta A/B picker.
        val view = layoutInflater.inflate(R.layout.dialog_depth_model, null)
        val container = view.findViewById<android.widget.LinearLayout>(R.id.modelContainer)
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val active = DepthModelManager.activeModel(this)
        for (m in DepthModelManager.DepthModel.values().filter { it.selectable }) {
            val btn = layoutInflater.inflate(R.layout.item_depth_model_button, container, false)
                    as com.google.android.material.button.MaterialButton
            btn.text = m.uiLabel
            btn.isChecked = (m == active)   // filled accent marks the current model (remote style)
            btn.setOnClickListener {
                dialog.dismiss()
                applyChosenDepthModel(m)
            }
            container.addView(btn)
        }
        dialog.show()
    }

    /** Shared by [promptDepthModelThenEnableLazy3d] and RemoteControlActivity's mirrored dialog. */
    fun applyChosenDepthModel(model: DepthModelManager.DepthModel) {
        DepthModelManager.setActiveModel(this, model)
        setLazy3dEnabled(true)
        finishStereoModeChange()
    }

    private fun toggleStereoMode() = cycleStereoMode()

    // "Is any stereo split active" — true for both OU→SBS and SBS modes, false for 2D.
    // Used by the pipeline / shift / save paths that only care whether we're splitting at all.
    private fun getStereoSbs(): Boolean = stereoMode != StereoMode.Off

    // --- SBS vertical shift to approximate 16:9 without bars ---
    private fun applySbsShiftIfNeeded() {
        val gl = activeGlView() ?: return
        if (!sbsShiftEnabled || !getStereoSbs()) {
            gl.setEyeVerticalShiftNormalized(0f, 0f)
            gl.setPerEyeLetterboxPx(0f, referenceHeightPx = 1f)
            return
        }
        val w = lastVideoWidth
        val h = lastVideoHeight
        if (w <= 0 || h <= 0) {
            gl.setEyeVerticalShiftNormalized(0f, 0f)
            gl.setPerEyeLetterboxPx(0f, referenceHeightPx = 1f)
            return
        }
        // Compute desired half height for 16:9 based on HALF video width (each SBS half uses half width)
        val targetHalfH = kotlin.math.round((w / 2f) * 9f / 16f)
        // OU source half height is h/2
        val halfH = h / 2f
        val delta = (targetHalfH - halfH).toInt()
        if (delta <= 0) {
            // Already 16:9 or taller; no padding
            gl.setEyeVerticalShiftNormalized(0f, 0f)
            gl.setPerEyeLetterboxPx(0f, referenceHeightPx = halfH.coerceAtLeast(1f))
            return
        }
        // Fine-tune: use 90% of delta as one-sided pad per half in source pixels
        val pad = delta * 0.9f
        gl.setEyeVerticalShiftNormalized(0f, 0f)
        gl.setPerEyeLetterboxPx(pad, referenceHeightPx = halfH)
    }

    // Entry point used by lifecycle callbacks (config change / resume). The actual decision
    // lives in applyRenderMode(), which is layout-driven and targets the active view.
    private fun updateSbsUi() {
        applyRenderMode()
    }

    private fun applySbsButtonVisual(btn: MaterialButton) {
        // Reflect the 4-state mode (2D / Lazy 3D / OU→SBS / SBS): label + filled (active) vs 2D outline.
        btn.text = getStereoModeLabel()
        // While Lazy 3D is downloading the model / warming up, lock the button so testers wait
        // (and watch the % on the caption) instead of re-tapping — a re-tap would cancel + cycle.
        btn.isEnabled = !isLazy3dBusy()
        val active = lazy3dEnabled || stereoMode != StereoMode.Off
        btn.isChecked = active
        applyToggleButtonVisual(btn, active)
    }

    /** Filled accent (on) vs outlined-white (off) — shared by the stereo and Lazy-3D toggles. */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun applyToggleButtonVisual(btn: MaterialButton, on: Boolean) {
        if (on) {
            btn.backgroundTintList = ColorStateList.valueOf(themeColor(androidx.appcompat.R.attr.colorPrimary))
            btn.setTextColor(themeColor(com.google.android.material.R.attr.colorOnPrimary))
            btn.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.strokeWidth = 0
        } else {
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(Color.WHITE)
            btn.strokeColor = ColorStateList.valueOf(Color.WHITE)
            btn.strokeWidth = (2 * resources.displayMetrics.density).toInt()
        }
    }

    /**
     * Drive the Shift / Lazy-3D slot in the player's own top bar by stereo mode:
     *   - OU→SBS : Shift (vertical pad toward 16:9) — meaningful only here.
     *   - 2D     : Lazy 3D (2D→3D depth synthesis) toggle — only applies to a flat clip.
     *   - SBS    : neither (the source is already stereo).
     * The phone remote has its own equivalent logic and is intentionally left untouched.
     */
    private fun updateStereoControlButtons() {
        // Shift (vertical pad toward 16:9) is only meaningful in OU→SBS. Lazy 3D is no longer a
        // separate button — it's a state of the stereo-mode cycle (see cycleStereoMode / btnSbs).
        btnShiftRef?.visibility = if (isOuSbsMode()) View.VISIBLE else View.GONE
    }

    // syncSbsButtons no longer needed with single toolbar

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSbsUi()
    }


    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor: Cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        } catch (_: Throwable) {
            null
        }
    }

    // ========== Public methods for RemoteControlActivity ==========

    fun getCurrentTitle(): String = currentResolvedTitle ?: sourceUri?.lastPathSegment ?: ""

    fun getCurrentPosition(): Long = player?.currentPosition ?: 0L

    fun getDuration(): Long = player?.duration?.takeIf { it > 0 } ?: 0L

    fun isPlaying(): Boolean = player?.isPlaying ?: false

    fun togglePlayPause() {
        player?.let { exo ->
            if (exo.isPlaying) exo.pause() else exo.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0))
        flashGlassesOsd()
    }

    fun seekRelative(deltaMs: Long) {
        player?.let { exo ->
            val newPos = (exo.currentPosition + deltaMs).coerceIn(0, exo.duration.coerceAtLeast(0))
            exo.seekTo(newPos)
        }
        flashGlassesOsd()
    }

    /** Feedback on the goggles for remote actions: flash the transport OSD there for ~2 s.
     *  No-op when there's no external presentation (phone-only or glasses-as-primary/TV-box
     *  playback, where PlayerActivity's own overlay is the visible UI). */
    fun flashGlassesOsd() {
        presentation?.flashOsd()
    }

    fun isStereoSbsEnabled(): Boolean = getStereoSbs()

    /** True only in OU→SBS mode — the vertical Shift control is meaningful only there. */
    fun isOuSbsMode(): Boolean = stereoMode == StereoMode.Ou

    /** Remote-control entry point: cycle 2D → OU→SBS → SBS. */
    fun toggleStereoSbs() = cycleStereoMode()

    /** Current stereo-mode label for the remote ("2D" / "2D→3D" / "OU→SBS" / "SBS"). */
    fun getStereoModeLabel(): String = when {
        // "2D→3D" (not the internal "Lazy 3D" codename) — this is what the mode button shows.
        lazy3dEnabled -> lazy3dBusyLabel() ?: "2D→3D"
        stereoMode == StereoMode.Ou -> "OU→SBS"
        stereoMode == StereoMode.Sbs -> "SBS"
        else -> "2D"
    }

    /** True while Lazy 3D is downloading its model or warming up — taps should be ignored. */
    fun isLazy3dBusy(): Boolean = lazy3dEnabled && (depthDownloadJob != null || depthStarting)

    /** Button caption during the busy phase ("2D→3D… 37%" download / "2D→3D…" warm-up), else null. */
    private fun lazy3dBusyLabel(): String? = when {
        lazy3dDownloadPct in 0..99 -> "2D→3D… ${lazy3dDownloadPct}%"
        isLazy3dBusy() -> "2D→3D…"
        else -> null
    }

    fun isShiftEnabled(): Boolean = sbsShiftEnabled

    fun toggleShift() {
        sbsShiftEnabled = !sbsShiftEnabled
        btnShiftRef?.isChecked = sbsShiftEnabled
        applySbsShiftIfNeeded()
        saveProgress()
    }

    // --- Lazy 3D (depth-based 2D->3D synthesis) ---

    /** Whether the Lazy-3D toggle is currently on. */
    fun isLazy3dEnabled(): Boolean = lazy3dEnabled

    /**
     * Coarse status for the remote to show feedback right after the toggle is tapped, so the
     * user isn't tempted to tap again while the (slow, off-thread) startup is still spinning up.
     * "Active" means real data is flowing: IMU samples for an XREAL link, or a ready depth model
     * when there's no IMU link.
     */
    fun lazy3dStatus(): Lazy3dStatus {
        if (!lazy3dEnabled) return Lazy3dStatus.Off
        if (depthEstimator?.isReady() == true) return Lazy3dStatus.Active
        // Still downloading the model or warming up → keep reporting Starting (the ~99 MB DA-V2
        // download can outlast the grace window; don't flip to Active while there's no estimator).
        if (depthDownloadJob != null || depthStarting) return Lazy3dStatus.Starting
        // Don't keep the remote's toggle locked forever if startup stalls — after a short grace
        // window report Active so the button becomes tappable again (debug line shows the truth).
        val elapsedMs = (System.nanoTime() - lazy3dEnabledAtNanos) / 1_000_000L
        return if (elapsedMs > lazy3dStartupGraceMs) Lazy3dStatus.Active else Lazy3dStatus.Starting
    }

    /**
     * Whether the Lazy-3D toggle makes sense for the current clip — i.e. the source is plain 2D.
     * Real SBS sources and OU sources we're already converting to SBS are themselves stereo and
     * don't need synthesised depth on top.
     */
    // Lazy 3D synthesises depth from a flat image, so only offer it while the clip is actually
    // shown in plain 2D — not when it's already being split as OU→SBS or SBS.
    fun isLazy3dApplicable(): Boolean = stereoMode == StereoMode.Off

    /**
     * ALWAYS true: the 2D→3D step is offered on every plain-2D film regardless of glasses, display,
     * network, or whether the model is downloaded yet (the cycle still requires a MONO source, so
     * SBS/OU films keep the plain 2D↔SBS toggle). If the model isn't present, enabling it kicks off
     * the download — the button shows progress and surfaces a network error (with the host) if it
     * can't fetch — but the option is always there (matches iOS).
     *
     * Every display/glasses gate we tried proved unreliable and kept HIDING the option from real
     * users (a glasses panel is 16:9 in 2D, mirror setups make no Presentation, new models like
     * RayNeo 4Pro aren't in any VID/PID list), so we stopped gating entirely.
     */
    fun isLazy3dSupported(): Boolean = true

    /**
     * Lazy 3D synthesizes an SBS pair, so it's offered where one can be shown: supported XR glasses
     * attached over USB, an external Presentation, or an ultrawide (~32:9) active display.
     *
     * The glasses-attached check is the RELIABLE signal. Geometry alone misses real setups: a glasses
     * panel reports 16:9 in 2D and only becomes 32:9 once switched to 3D, so gating on "currently
     * ultrawide" hid the option exactly when the user wanted to turn it on from 2D (chicken-and-egg)
     * — that's why many users couldn't find it. A bare phone with no glasses has none of the three,
     * so an SBS split — useless there — still isn't offered.
     */
    private fun hasLazy3dDisplay(): Boolean =
        GlassesController.anyAttached(this) ||   // static USB scan — works even without the main screen
            presentation != null ||
            activeDisplayIsUltrawide()

    /**
     * Turn the Lazy-3D feature on or off. Starting it downloads the depth-estimation TFLite model
     * on first use (~65 MB; only the first run on this device pays this cost), then starts
     * depth-based stereo synthesis. Stopping releases it so the feature costs nothing when off.
     */
    fun setLazy3dEnabled(enabled: Boolean) {
        if (enabled == lazy3dEnabled) return
        lazy3dEnabled = enabled
        if (enabled) {
            lazy3dEnabledAtNanos = System.nanoTime()
            val mgr = DepthModelManager(applicationContext)
            if (mgr.isAvailable()) {
                // Depth model already cached — start the synthesis half now too.
                startLazy3dDepth()
            } else {
                // Fetch the depth model in the background and light up depth synthesis once it lands.
                // Show the % on the button + lock it so testers wait through the (up to ~99 MB) fetch.
                Toast.makeText(this, R.string.lazy3d_downloading, Toast.LENGTH_SHORT).show()
                lazy3dDownloadPct = 0
                btnSbsRef?.let { applySbsButtonVisual(it) }
                RemoteControlActivity.currentInstance?.syncControls()
                depthDownloadJob?.cancel()
                depthDownloadJob = lifecycleScope.launch {
                    var lastShown = -1
                    val ok = mgr.ensureAvailable { bytes, total ->
                        if (total > 0) {
                            val pct = (bytes * 100 / total).toInt().coerceIn(0, 99)
                            if (pct != lastShown && pct % 5 == 0) {
                                lastShown = pct
                                lazy3dDownloadPct = pct   // @Volatile; callback runs on the IO thread
                                runOnUiThread { btnSbsRef?.let { applySbsButtonVisual(it) } }
                            }
                        }
                    }
                    lazy3dDownloadPct = -1
                    depthDownloadJob = null
                    btnSbsRef?.let { applySbsButtonVisual(it) }
                    if (!lazy3dEnabled) return@launch // user toggled off while downloading
                    if (ok) {
                        Toast.makeText(this@PlayerActivity, R.string.lazy3d_downloaded, Toast.LENGTH_SHORT).show()
                        startLazy3dDepth()
                    } else {
                        // Download failed — drop the toggle so the button/status don't claim an
                        // "active" Lazy 3D that will never come, and so the user can simply retry.
                        Toast.makeText(this@PlayerActivity, getString(R.string.lazy3d_download_failed_host, mgr.downloadHost()), Toast.LENGTH_LONG).show()
                        lazy3dEnabled = false
                        applyVideoPipeline()
                        btnSbsRef?.let { applySbsButtonVisual(it) }
                        RemoteControlActivity.currentInstance?.syncControls()
                    }
                }
            }
            // Fire-and-forget update check: if the cached model is older than what's on GitHub,
            // fetch the new one in the background. Next session uses the fresh copy. Gated to
            // (a) once per process — no point re-HEADing GitHub on every toggle — and (b) only
            // when a cached copy actually exists: with no copy, ensureAvailable above is already
            // downloading, and this used to kick off a SECOND download of the same file.
            if (mgr.isCached() && DepthModelManager.claimUpdateCheck()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (mgr.isUpdateAvailable()) {
                            android.util.Log.i("XPlayer2", "Lazy 3D: depth model update available, refreshing in background")
                            mgr.forceUpdate(null)
                        }
                    } catch (_: Throwable) { }
                }
            }

            // If nothing is starting and nothing is in flight (no depth model / download), there's
            // nothing to enable — drop the toggle back off. Startup is async, so check the in-flight
            // flags rather than the not-yet-assigned estimator.
            if (!depthStarting && depthEstimator == null && depthDownloadJob == null) {
                android.util.Log.w("XPlayer2", "Lazy 3D: nothing to enable, disabling toggle")
                lazy3dEnabled = false
            }
        } else {
            depthDownloadJob?.cancel()
            depthDownloadJob = null
            stopLazy3d()
        }
    }

    /**
     * Depth-based stereo synthesis — works with any goggles, but needs the TFLite
     * depth model installed). Called once the model is present. No-op if already running.
     */
    private fun startLazy3dDepth() {
        if (depthEstimator != null || depthStarting) return
        depthStarting = true
        lazy3dDownloadPct = -1   // download done (if any) — now warming up
        btnSbsRef?.let { applySbsButtonVisual(it) }   // show "Lazy 3D…" + keep locked during warm-up
        RemoteControlActivity.currentInstance?.syncControls()
        val gen = lazy3dGen
        lifecycleScope.launch {
          try {
            // Loading the TFLite model + GPU init takes a couple of seconds — do it off the
            // main thread so the toggle doesn't freeze. GL wiring happens back on the main thread.
            val estimator = withContext(Dispatchers.IO) {
                val m = DepthModelManager.activeModel(applicationContext)
                lazy3dDivergence = LAZY3D_DIVERGENCE * m.divergenceScale
                DepthEstimator(m.inputSize, m.gpuSafe, m::mapDepth, m.convergencePct)
                    .apply { if (!init(applicationContext)) close() }
            }
            if (gen != lazy3dGen) {
                // stopLazy3d() ran while we were loading (toggle-off, or an off→on restart whose
                // new startup now owns depthStarting/depthEstimator) — this startup is stale.
                // Release our estimator and leave the shared state strictly alone.
                withContext(Dispatchers.IO) { estimator.close() }
                return@launch
            }
            depthStarting = false
            if (!lazy3dEnabled || !estimator.isReady()) {
                withContext(Dispatchers.IO) { estimator.close() }
                if (lazy3dEnabled && !estimator.isReady()) {
                    android.util.Log.w("XPlayer2", "Lazy 3D: depth model not loaded on this device")
                    // No backend could load the model (GPU→NNAPI→CPU; plain CPU only rejects a
                    // corrupt flatbuffer or OOM). A corrupt cached file would brick Lazy 3D on
                    // every future attempt — it passes the size check, and when its size matches
                    // the remote the update check never replaces it. Delete it so the next enable
                    // re-downloads a fresh copy (no-op when the model came from bundled assets).
                    DepthModelManager(applicationContext).invalidateCache()
                    // Drop the toggle so the button/status reflect reality and retry stays one tap.
                    lazy3dEnabled = false
                    applyVideoPipeline()
                    btnSbsRef?.let { applySbsButtonVisual(it) }
                    RemoteControlActivity.currentInstance?.syncControls()
                    Toast.makeText(this@PlayerActivity, R.string.lazy3d_unsupported, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            depthEstimator = estimator
            val worker = DepthFrameWorker(estimator).also { it.start() }
            depthWorker = worker
            activeGlView()?.setLazy3dStereoEnabled(true)
            activeGlView()?.setStereoParams(divergence = lazy3dDivergence, convergence = estimator.dynamicConvergence)
            activeGlView()?.setOnFrameReadbackListener { pixels, w, h, ts ->
                // Called on the GL thread when a fresh source snapshot is ready.
                worker.submit(pixels, w, h, ts)
            }
            depthThermal = DepthThermalGovernor(applicationContext).also { it.start() }
            startDepthTick(worker, estimator)
            // Warm-up done → unlock the button and show the active label.
            btnSbsRef?.let { applySbsButtonVisual(it) }
            RemoteControlActivity.currentInstance?.syncControls()
            android.util.Log.i("XPlayer2", "Lazy 3D: depth synthesis started on ${estimator.backend} (avg inference will appear in logs)")
            // No GPU/NNAPI here → depth runs on CPU; warn that it may be heavy on this device.
            if (estimator.backend?.startsWith("CPU") == true) {
                Toast.makeText(this@PlayerActivity, R.string.lazy3d_slow_cpu, Toast.LENGTH_LONG).show()
            }
          } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e   // activity going away — not a failure
            // Any failure spinning up depth (OOM on low-RAM devices, an unexpected delegate error)
            // must not crash the app — disable Lazy 3D and restore the normal picture/toggle state.
            android.util.Log.e("XPlayer2", "Lazy 3D: startup failed, disabling", e)
            if (gen == lazy3dGen) {
                // Only the startup that still owns the state may reset it — a stale one (stop or
                // restart happened mid-load) must not clobber the newer startup's flags.
                depthStarting = false
                lazy3dEnabled = false
                stopLazy3d()
                applyVideoPipeline()
                btnSbsRef?.let { applySbsButtonVisual(it) }
                RemoteControlActivity.currentInstance?.syncControls()
                val msg = if (e is OutOfMemoryError) R.string.lazy3d_low_memory else R.string.lazy3d_failed
                Toast.makeText(this@PlayerActivity, msg, Toast.LENGTH_LONG).show()
            }
          }
        }
    }

    /** Pump fresh depth results into the active GL view every ~33 ms. */
    private fun startDepthTick(worker: DepthFrameWorker, estimator: DepthEstimator) {
        pendingDepthTick?.let { poseUiHandler.removeCallbacks(it) }
        depthTickLastTs = 0L
        val depthTick = object : Runnable {
            private var ticks = 0
            override fun run() {
                // Thermal governor → readback pacing. Applied every tick (one volatile store) so
                // it also lands on whichever glView became active after a display switch. Pacing
                // is the single throttle knob: fewer readbacks → fewer inferences (the worker is
                // latest-only) → the NPU/GPU duty cycle drops; at PAUSED the readback stops and
                // the warp keeps using the last depth map (frozen 3D beats a thermal kill).
                val thermal = depthThermal?.tick() ?: DepthThermalGovernor.Level.FULL
                activeGlView()?.setDepthReadbackIntervalNanos(thermal.readbackIntervalNanos)
                maybeHintThermal(thermal)
                // Push only NEW inferences (latestDepth is a slot, not a queue — it stays set):
                // re-uploading the same map re-ran the GL bilateral refine pass at 30 Hz for
                // nothing, and kept the readback→inference loop spinning even while the video
                // was PAUSED. Gating on the timestamp lets the whole pipeline idle with playback.
                // Timestamp is read BEFORE the depth: the worker publishes depth-then-ts, so this
                // order can at worst re-push one map, never record a ts for a map it didn't push.
                val depthTs = worker.latestDepthTimestampNanos
                val depth = worker.pollLatestDepth()
                if (depth != null && depthTs != depthTickLastTs) {
                    depthTickLastTs = depthTs
                    val bytes = ByteArray(depth.size)
                    for (i in depth.indices) {
                        bytes[i] = (depth[i].coerceIn(0f, 1f) * 255f).toInt().toByte()
                    }
                    activeGlView()?.setDepthMap(bytes, estimator.inputSize, estimator.inputSize)
                    // Follow the dynamic convergence (slow-EMA'd subject depth) so the screen
                    // plane stays locked to the subject as scenes change. Two float stores — cheap.
                    activeGlView()?.setStereoParams(lazy3dDivergence, estimator.dynamicConvergence)
                }
                if (++ticks % 60 == 0) {
                    android.util.Log.i("XPlayer2", "Lazy 3D: avg depth inference ${"%.1f".format(estimator.avgInferenceMs)} ms")
                }
                if (lazy3dEnabled && depthEstimator != null) poseUiHandler.postDelayed(this, 33)
            }
        }
        pendingDepthTick = depthTick
        poseUiHandler.post(depthTick)
    }

    /**
     * One-shot "device is hot" hint on thermal escalation. Quiet for the 15 Hz step (visually
     * indistinguishable); speaks up when depth drops to 7.5 Hz or freezes, at most once per
     * 5 minutes so a sensor bouncing around a threshold can't spam.
     */
    private fun maybeHintThermal(level: DepthThermalGovernor.Level) {
        val prev = depthThermalSeen
        if (level == prev) return
        depthThermalSeen = level
        if (level < prev) return                                    // recovering — silent
        if (level < DepthThermalGovernor.Level.QUARTER) return      // 15 Hz — not worth a toast
        val now = android.os.SystemClock.uptimeMillis()
        if (now - depthThermalToastMs < 300_000L) return
        depthThermalToastMs = now
        val msg = if (level == DepthThermalGovernor.Level.PAUSED) R.string.lazy3d_thermal_paused
        else R.string.lazy3d_thermal_reduced
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun stopLazy3d() {
        depthStarting = false
        // Invalidate any in-flight async startup: it compares its captured generation and discards
        // itself (closing its own estimator) instead of racing a later restart into two live
        // estimator/worker pairs (which leaked a GPU delegate per toggle).
        lazy3dGen++
        // --- Immediate, main-thread visual teardown so the picture restores at once. ---
        // We're on the main thread, and the tick runs on the same thread, so removeCallbacks
        // guarantees no tick is mid-flight or will fire again.
        pendingDepthTick?.let { poseUiHandler.removeCallbacks(it) }
        pendingDepthTick = null
        depthThermal?.stop(); depthThermal = null
        depthThermalSeen = DepthThermalGovernor.Level.FULL
        val worker = depthWorker; depthWorker = null
        val estimator = depthEstimator; depthEstimator = null
        // Clear lazy3d state on every possible render target (incl. the active one) so the depth
        // split doesn't linger after a display switch or toggle-off.
        for (v in listOfNotNull(glView, presentation?.renderView, activeGlView())) {
            v.setOnFrameReadbackListener(null)
            v.setLazy3dStereoEnabled(false)
        }
        android.util.Log.i("XPlayer2", "Lazy 3D disabled")
        // --- Slow hardware teardown off the main thread (thread joins + GPU release). A detached
        // thread, not lifecycleScope, so it still completes when stopLazy3d() is called from
        // onDestroy (where the scope is cancelled). ---
        if (worker != null || estimator != null) {
            Thread({
                val workerExited = try { worker?.stop() ?: true } catch (_: Throwable) { true }
                if (workerExited) {
                    try { estimator?.close() } catch (_: Throwable) {}
                } else {
                    // The worker thread is wedged inside interp.run() (driver stall / very slow CPU
                    // frame): closing the interpreter under a live inference is native UB that can
                    // take the GPU delegate down for the whole process — after which every Lazy 3D
                    // re-enable fails until the app is killed. Leaking this one estimator is the
                    // lesser evil; the OS reclaims it with the process.
                    android.util.Log.w("XPlayer2", "Lazy 3D: depth worker didn't exit in time; leaking estimator (close mid-inference is unsafe)")
                }
            }, "Lazy3dTeardown").start()
        }
    }

    /**
     * Re-bind the depth-stereo flag, params and readback listener to whatever glView is now
     * active (after a presentation create/dismiss). The IMU/depth worker threads keep running;
     * only the GL-side wiring needs to move to the new view.
     */
    private fun reapplyLazy3dToActiveView() {
        val v = activeGlView() ?: return
        val worker = depthWorker
        if (depthEstimator?.isReady() == true && worker != null) {
            v.setLazy3dStereoEnabled(true)
            v.setStereoParams(divergence = lazy3dDivergence, convergence = depthEstimator?.dynamicConvergence ?: 0.5f)
            v.setOnFrameReadbackListener { pixels, w, h, ts -> worker.submit(pixels, w, h, ts) }
            // Carry the thermal pacing over (a fresh view defaults to full rate), and re-arm the
            // tick to re-push the latest depth map — with inference throttled/paused there may be
            // no new inference for a while, and the new view has no depth texture yet.
            v.setDepthReadbackIntervalNanos(
                (depthThermal?.level ?: DepthThermalGovernor.Level.FULL).readbackIntervalNanos
            )
            depthTickLastTs = 0L
        }
    }

    /** Current resize-mode label for the RemoteControlActivity to display. */
    fun getResizeModeLabel(): String = resizeModeLabel(resizeMode)

    /** Advance through the resize-mode cycle (same one the player overlay button uses)
     *  and return the new label so a remote UI can refresh its button text. */
    fun cycleResizeMode(): String {
        resizeMode = (resizeMode + 1) % 7
        applyResizeMode()
        saveProgress()
        return resizeModeLabel(resizeMode)
    }

    private fun resizeModeLabel(mode: Int): String = when (mode) {
        1 -> "16:9"
        2 -> "4:3"
        3 -> "21:9"
        4 -> "32:9"
        5 -> "1:1"
        6 -> "2.39:1"
        else -> "Auto"
    }

    /**
     * Get list of audio tracks for remote control
     * Returns list of pairs: (label, index) where index -1 means "Auto"
     */
    fun getAudioTracks(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        result.add("Auto" to -1)
        
        val items = buildAudioMenuItems()
        items.forEachIndexed { index, item ->
            if (!item.isAuto) {
                result.add(item.label to index)
            }
        }
        return result
    }
    
    /**
     * Get currently selected audio track index (-1 for auto)
     */
    fun getSelectedAudioTrackIndex(): Int {
        val items = buildAudioMenuItems()
        items.forEachIndexed { index, item ->
            if (!item.isAuto && item.group != null && item.trackIndexInGroup != null) {
                try {
                    if (item.group.isTrackSelected(item.trackIndexInGroup)) {
                        return index
                    }
                } catch (_: Exception) { }
            }
        }
        return -1 // Auto
    }
    
    /**
     * Select audio track by index (-1 for auto)
     */
    fun selectAudioTrack(index: Int) {
        val items = buildAudioMenuItems()
        val item = if (index < 0) {
            items.firstOrNull { it.isAuto }
        } else {
            items.getOrNull(index)
        }
        item?.let { applyAudioSelection(it) }
    }

    /**
     * Get list of subtitle (text) tracks for remote control.
     * Returns list of pairs: (label, index) where index -1 means "Off".
     */
    fun getSubtitleTracks(): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        result.add(getString(R.string.subtitle_off) to -1)

        val items = buildTrackMenuItems(C.TRACK_TYPE_TEXT)
        items.forEachIndexed { index, item ->
            if (!item.isOff) {
                result.add(item.label to index)
            }
        }
        return result
    }

    /**
     * Get currently selected subtitle track index (-1 when subtitles are off/disabled).
     */
    fun getSelectedSubtitleTrackIndex(): Int {
        val isTextDisabled = trackSelector?.parameters?.getRendererDisabled(C.TRACK_TYPE_TEXT) == true ||
            (trackSelector?.parameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true)
        if (isTextDisabled) return -1
        val items = buildTrackMenuItems(C.TRACK_TYPE_TEXT)
        items.forEachIndexed { index, item ->
            if (!item.isOff && item.group != null && item.trackIndexInGroup != null) {
                try {
                    if (item.group.isTrackSelected(item.trackIndexInGroup)) {
                        return index
                    }
                } catch (_: Exception) { }
            }
        }
        return -1 // Off
    }

    /**
     * Select subtitle track by index (-1 turns subtitles off).
     */
    fun selectSubtitleTrack(index: Int) {
        val items = buildTrackMenuItems(C.TRACK_TYPE_TEXT)
        val item = if (index < 0) {
            items.firstOrNull { it.isOff }
        } else {
            items.getOrNull(index)
        }
        item?.let { applyTrackSelection(it, C.TRACK_TYPE_TEXT) }
    }

    // --- Stream quality (remote control API) ---

    /** Quality labels for the current source, highest first. Empty / single-entry => no picker. */
    fun getQualityVariants(): List<String> = streamVariants.map { it.label }

    /** Index of the currently playing quality (0 = highest). */
    fun getSelectedQualityIndex(): Int = selectedVariantIndex

    /** Whether a quality picker should be offered (source exposes ≥2 qualities). */
    fun hasMultipleQualities(): Boolean = streamVariants.size > 1

    /**
     * Remote-control entry point: switch to the quality at [index], preserving position and the
     * stereo/resize state. Routed to the same in-process player the goggles render from.
     */
    fun selectQuality(index: Int) = switchQuality(index)

    fun finishAndClose() {
        dismissPresentation()
        finish()
    }

    // ------------------------------------------------------------------------------------------
    // PC Link — desktop streaming from a PC (milestone M1)
    // ------------------------------------------------------------------------------------------
    //
    // An additive branch that shares only the *output* half of this activity: the decoded picture
    // goes to whichever OuToSbsGlView is live — the Presentation's when the glasses are connected,
    // the activity's own otherwise — exactly like a file. Everything upstream of the surface is
    // different: no ExoPlayer, no MediaItem, no track selection, no recents, no resume position.
    // The video arrives as Annex-B access units over TCP (PcLinkClient) and is decoded straight to
    // that surface with no pacing (PcStreamDecoder); the PC does the pacing.

    /** One-time setup for a PC Link session, then [connectPcLink]. */
    private fun startPcLink(intent: Intent, host: String) {
        exitPcLink()
        // The other half of the exclusivity, and the one that was already intended: the glasses can
        // only show one thing, so a film — in this activity or in an older one still holding the
        // panel — comes off before the desktop goes on. `onNewIntent` has already released the
        // player when the intent landed here, but a fresh instance reaches this by another road.
        GlassesStage.claim(this)
        stopLocalPlayback()
        pcLinkHost = host
        // A new session even when it is the same PC at the same address: whatever a reader has
        // collected describes the one that just ended, and splicing the two would draw a minute
        // that never happened.
        pcSessionId = PcLinkSession.newSessionId()
        pcLinkLink = PcLinkSession.Link.CONNECTING
        pcLinkControlPort = intent.getIntExtra(PcConnectActivity.EXTRA_PCLINK_CONTROL_PORT, DEFAULT_CONTROL_PORT)
        pcLinkVideoPort = intent.getIntExtra(PcConnectActivity.EXTRA_PCLINK_VIDEO_PORT, DEFAULT_VIDEO_PORT)
        pcLinkServerName = intent.getStringExtra(PcConnectActivity.EXTRA_PCLINK_NAME)
            ?.takeIf { it.isNotBlank() } ?: host
        pcLinkServerId = intent.getStringExtra(PcConnectActivity.EXTRA_PCLINK_SERVER_ID)
            ?.takeIf { it.isNotBlank() }
        // Not a media item: nothing to resume, nothing to put in Recents (saveProgress() bails on a
        // null sourceUri, which is exactly what we want).
        sourceUri = null
        recentKeyUri = null
        currentResolvedTitle = pcLinkServerName
        // Stand the whole ExoPlayer UI down. PlayerView with no player closes its shutter — an
        // opaque black view drawn ON TOP of glView — so leaving it visible would hide the stream.
        playerView.player = null
        playerView.visibility = View.GONE
        // Only claim the local view when the glasses aren't already showing the picture.
        if (presentation == null) glView?.visibility = View.VISIBLE
        // A remote left over from a previous file session drives an ExoPlayer that no longer
        // exists, and there is no PC Link transport for it to control.
        RemoteControlActivity.currentInstance?.finish()
        // A presentation carried over from a file session hosts the flat OuToSbsGlView; PC Link
        // on the glasses wants the world-fixed VirtualDesktopGlView. The mode is baked into the
        // presentation, so recreate it (only when we're in a state to show one — otherwise
        // onStart/onResume's tryShowExternalPresentation does it).
        if (presentation != null && presentation?.isWorldFixedDesktop != true &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            tryShowExternalPresentation()
        }
        applyPcLinkRenderConfig()
        // Our own surface listener replaces the one initializePlayer() installs; in PC Link mode
        // that never runs, and on a mode switch the other side re-installs its own.
        glView?.setOnSurfaceReadyListener { surface ->
            glSurface = surface
            if (isPcLinkMode) updatePcLinkSurface()
        }
        ensurePcLinkOverlay()
        setPcLinkStatus(getString(R.string.pclink_connecting), dim = false)
        connectPcLink()
        // The door proved a pair of glasses is plugged in, not that its screen is up. Give the panel
        // its grace and then let the rule that governs *losing* it decide — that rule was only ever
        // wired to a transition, so a cast that never had a panel was never asked about at all, and
        // ran on with the desktop flattened into this window, the PC's speakers held, and no remote
        // (showRemoteControlFront makes none without a presentation).
        uiHandler.removeCallbacks(pcLinkPanelGrace)
        uiHandler.postDelayed(pcLinkPanelGrace, ExternalPanelPolicy.ENTRY_GRACE_MS)
    }

    /** Opens (or re-opens, after a background trip) the link. */
    private fun connectPcLink() {
        val host = pcLinkHost ?: return
        if (pcLinkClient != null) return
        pcLatencyFloorUs = Long.MAX_VALUE
        pcLatencyUs = 0L
        val decoder = PcStreamDecoder(pcDecoderListener)
        pcDecoder = decoder
        pcLinkConfig?.let { decoder.configure(it.mime, it.width, it.height) }
        updatePcLinkSurface()
        val client = PcLinkClient(
            context = applicationContext,
            host = host,
            controlPort = pcLinkControlPort,
            videoPort = pcLinkVideoPort,
            listener = pcLinkListener,
            authProvider = { pcLinkAuth() }
        )
        pcLinkClient = client
        // A reconnect (this is a fresh client — backgrounding tore the last one down) starts out
        // asking for audio, because `hello` carrying caps is itself the request. Re-assert a mute
        // the user made before, or the PC would resume spending 1.5 Mbit/s on sound we discard.
        if (pcAudioMuted) client.setAudioEnabled(false)
        client.connect(lifecycleScope)
        // Tell the PC what the glasses are showing, now and whenever it changes (protocol.md
        // §2.16). The PC decides what to do with it — with its own setting on "follow the
        // glasses", switching them into 3D is what turns its desktop into stereo.
        acquiredGlasses?.setPlaybackListener { _, mode ->
            val stereo = GlassesProtocol.is3DMode(mode)
            pcLinkClient?.reportGlasses(stereo)
            // The renderer needs the same fact the PC is being told. It used to be told only the
            // PC, which is how a pair of glasses in 2D ended up showing two half-width copies of
            // the desktop: the view divided the panel in two regardless of what the panel was.
            runOnUiThread { presentation?.desktopView?.setPanelIsStereo(stereo) }
        }
        if (pcDebugView?.visibility == View.VISIBLE) startPcLinkDebugTicker()
    }

    /**
     * The stored pairing this connection should authenticate with, or null to speak the
     * unauthenticated M1 flow (a PC we've never paired with, or one the user has since forgotten).
     *
     * Called by [PcLinkClient] on its IO thread once per connection attempt — which is what it
     * needs to be: the token [PcConnectActivity] saw during its own handshake died with that
     * control session (protocol.md §2.13), so the player authenticates its own connection, and
     * every reconnect authenticates again with a fresh challenge.
     */
    private fun pcLinkAuth(): PcLinkAuth? {
        val serverId = pcLinkServerId ?: return null
        val store = pcLinkStore ?: PcLinkPairingStore(applicationContext).also { pcLinkStore = it }
        // Exactly this PC: PcConnectActivity has already resolved which pairing belongs to the
        // address, so there is nothing here for the FSM's other candidates to rescue.
        val pairing = store.get(serverId) ?: return null
        return PcLinkAuth(store.identity(), listOf(pairing))
    }

    /**
     * Drops the sockets and the codec but stays in PC Link mode — what backgrounding does, so a
     * phone in a pocket isn't decoding a desktop nobody is looking at.
     */
    private fun disconnectPcLink(sayGoodbye: Boolean = false) {
        stopPcLinkDebugTicker()
        acquiredGlasses?.setPlaybackListener(null)
        val client = pcLinkClient
        pcLinkClient = null
        if (sayGoodbye && client != null) {
            // `set_audio: false` is what hands the computer its own speakers back: xpl-server keeps
            // them silenced for as long as sound is reaching the glasses and releases the hold the
            // moment it stops flowing. Dropping the socket releases them too — the hold restores on
            // any way the session ends — but only once the PC notices, and until then the user's
            // desktop is mute for no reason it can see. So the message goes out first and the
            // socket outlives it by a beat.
            client.setAudioEnabled(false)
            closePcFarewell()
            pcFarewellClient = client
            uiHandler.postDelayed(pcFarewell, PCLINK_FAREWELL_MS)
        } else {
            closePcFarewell()
            client?.close()
        }
        pcDecoder?.release()
        pcDecoder = null
        // The audio path is session-scoped like the decoder: the next `config` rebuilds it, and a
        // phone in a pocket must not hold a live AudioTrack (or the glasses' audio route) open.
        pcAudio?.release()
        pcAudio = null
        updatePcLinkAudioButton()
    }

    /**
     * A client kept alive past the end of its session so its farewell can leave — see
     * [disconnectPcLink]. Never more than one, and never past the next thing that happens: a
     * client left retrying against a PC nobody is watching is a socket and a reconnect ladder
     * leaking out of a dead session.
     */
    private var pcFarewellClient: PcLinkClient? = null
    private val pcFarewell = Runnable {
        pcFarewellClient?.close()
        pcFarewellClient = null
    }

    private fun closePcFarewell() {
        uiHandler.removeCallbacks(pcFarewell)
        pcFarewellClient?.close()
        pcFarewellClient = null
    }

    /** Leaves PC Link mode entirely and gives the normal player UI its window back. */
    private fun exitPcLink() {
        if (!isPcLinkMode) return
        // The entry grace belongs to a session that is over. (A stray fire is already harmless —
        // reconcileExternalDisplay returns early once this is no longer PC Link mode — but a
        // pending "no panel, end it" against the next session is not something to leave lying.)
        uiHandler.removeCallbacks(pcLinkPanelGrace)
        // Leaving the mode is the user going somewhere else, so the PC is told rather than left to
        // work it out. Backgrounding (`disconnectPcLink()` on its own) is not: the session is
        // coming back, and re-muting it on the way out would only have to be undone.
        disconnectPcLink(sayGoodbye = true)
        // A world-fixed presentation is PC Link-only: drop it so whatever comes next (a file, or
        // nothing) gets the normal OuToSbsGlView presentation recreated by the usual lifecycle
        // paths. This also turns the IMU off via updatePcLinkImu() inside dismissPresentation().
        if (presentation?.isWorldFixedDesktop == true) dismissPresentation()
        pcLinkHost = null
        pcLinkServerName = ""
        pcLinkServerId = null
        pcLinkStore = null
        pcLinkRepairPending = false
        pcLinkConfig = null
        pcLinkSourceIsSbs = false
        pcVideoWidth = 0
        pcVideoHeight = 0
        pcAudioMuted = false
        // Deliberately not cleared by `disconnectPcLink()` alongside the track: a parked session
        // has not stopped having sound, and the reconnect re-asserts the user's choice. Leaving the
        // mode is where the PC's answer stops meaning anything.
        pcAudioOffered = false
        pcLastRenderedPtsUs = 0L
        stopPcLinkDebugTicker()
        (pcStatusView?.parent as? ViewGroup)?.removeView(pcStatusView)
        (pcAudioButton?.parent as? ViewGroup)?.removeView(pcAudioButton)
        (pcDebugView?.parent as? ViewGroup)?.removeView(pcDebugView)
        pcStatusView = null
        pcAudioButton = null
        pcDebugView = null
        playerView.visibility = View.VISIBLE
        // The cast and its remote have one lifetime between them: whichever side ends first takes
        // the other with it, so the user is never left holding a remote for a session that stopped
        // (nor — the older fault — dropped onto this activity's own empty window). Re-entrant from
        // the remote's own exit, and a no-op there: it has already started leaving.
        PcLinkRemoteActivity.currentInstance?.onSessionEnded()
    }

    /**
     * The surface handoff, and the single place that decides which one is live:
     *
     * * glasses connected → the [ExternalPlayerPresentation]'s GL view owns the picture, so the
     *   decoder renders into [presentationSurface];
     * * no glasses (or the panel just went away) → the activity's own glView, [glSurface].
     *
     * Both surfaces arrive asynchronously (GLSurfaceView creates them on its own thread) and can
     * disappear at any moment when the goggles are taken off; passing null detaches the codec
     * until one comes back. The decoder rebuilds itself and asks for a fresh IDR on every change,
     * so a swap costs one sync frame and nothing else.
     */
    private fun updatePcLinkSurface() {
        val surface = if (presentation != null) presentationSurface else glSurface
        pcDecoder?.setSurface(surface)
    }

    /**
     * Pushes the server's `config` into whichever renderer is live:
     *
     * * glasses presentation → the world-fixed [VirtualDesktopGlView]: canvas geometry
     *   (angular width / distance), stereo packing and frame size — head tracking does the rest;
     * * phone-local fallback → the flat [OuToSbsGlView] (a phone isn't head-tracked): a plain
     *   full-screen picture, no OU→SBS conversion, no mono duplication, no auto-detection (which
     *   would read a 32:9 canvas as side-by-side content). The one thing honoured is the server
     *   saying its stream *is* already SBS.
     */
    /**
     * Whether the glasses' panel is currently the ultrawide side-by-side one.
     *
     * Falls back to the panel's own shape when no pair is attached over USB to ask — a display
     * about twice as wide as it is tall is the SBS mode by construction, and a presentation
     * hosted on anything else is flat. Guessing beats defaulting to stereo here: the cost of
     * being wrong the stereo way is two half-width copies, which is unusable, while being wrong
     * the flat way is merely a picture in one eye.
     */
    private fun glassesAreStereo(): Boolean {
        // The panel we are drawing on is the authority, and it is measured, not remembered.
        //
        // This asked the glasses over USB first, and that was wrong in a way that only shows up
        // on someone else's setup: `lastMode()` is `lastReportedMode`, which starts at 2D and is
        // only updated when *we* command a mode. Glasses already in 3D when the app started — or
        // a brand with no read-back at all — therefore answered "2D" with total confidence, and a
        // 3840-wide SBS panel got one desktop stretched across both eyes: an un-fusable double
        // image. The `acquiredGlasses != null` branch also won almost always, since that field is
        // set from a process-wide controller, so the measurement below was nearly unreachable.
        //
        // The USB answer is kept only for when there is no panel to measure, where a stale guess
        // beats no answer.
        presentation?.display?.let { display ->
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            return VirtualDesktopMath.panelIsStereo(metrics.widthPixels, metrics.heightPixels)
        }
        acquiredGlasses?.let { return GlassesProtocol.is3DMode(it.lastMode()) }
        return false
    }

    private fun applyPcLinkRenderConfig() {
        presentation?.desktopView?.let { desktop ->
            pcLinkConfig?.let { c -> desktop.setCanvas(c.canvasAngularWidthDeg, c.canvasDistanceM) }
            desktop.setSourceIsSbs(pcLinkSourceIsSbs)
            // What the panel is, which is a different question from how the stream is packed:
            // glasses sitting in 2D are an ordinary flat display and must be drawn once.
            desktop.setPanelIsStereo(glassesAreStereo())
            if (pcVideoWidth > 0 && pcVideoHeight > 0) {
                desktop.setVideoSize(pcVideoWidth, pcVideoHeight)
            }
            return
        }
        val v = activeGlView() ?: return
        // SBS *output* only when the screen is the glasses' 3D panel. In 2D they are an ordinary
        // 1920x1080 external display — below `findUltraWideExternalDisplay`'s threshold, so no
        // presentation is made and this window is simply mirrored into them. Splitting it in two
        // there is what put a pair of half-width desktops on a flat panel.
        val stereoPanel = glassesAreStereo()
        v.setSbsEnabled(pcLinkSourceIsSbs && stereoPanel)
        v.setSourceIsSbs(pcLinkSourceIsSbs)
        v.setDuplicateMonoToSbs(false)
        v.setSwapEyes(false)
        v.updateResizeMode(0)
        if (pcVideoWidth > 0 && pcVideoHeight > 0) {
            v.updateVideoAspectRatio(pcVideoWidth, pcVideoHeight)
        }
    }

    /**
     * Builds, rebuilds or tears down the audio path to match what `config.audio` announced —
     * §2.2's "single wire truth". Main thread (that is where `onConfig` lands).
     *
     * The three transitions that matter:
     *
     * * **absent → present**: the PC has audio for us. Build a player for that exact format.
     * * **present → absent**: either side muted, or the PC's capture died. Drop the player; video
     *   is untouched, because the two never shared anything but the socket.
     * * **format change**: rebuild, since AudioTrack's rate and channel mask are fixed at build.
     *
     * A device that refuses to open the track tells the PC to stop sending (§12) rather than
     * pretending: the bandwidth is wasted otherwise, and the user gets an honest muted state.
     */
    private fun applyPcLinkAudioConfig(format: PcAudioFormat?) {
        // First, and before any of the early returns below: every `config` is the PC restating what
        // it has, including the one with no audio that acknowledges our own mute.
        pcAudioOffered = PcLinkAudioRouting.offeredAfterConfig(
            configHasAudio = format != null,
            wasOffered = pcAudioOffered,
            mutedHere = pcAudioMuted
        )
        val current = pcAudio
        if (format == null) {
            if (current != null) {
                current.release()
                pcAudio = null
            }
            updatePcLinkAudioButton()
            return
        }
        if (current != null && current.format == format) {
            updatePcLinkAudioButton()
            return
        }
        current?.release()
        val player = PcAudioPlayer(format) { message ->
            runOnUiThread {
                if (!isPcLinkMode) return@runOnUiThread
                android.util.Log.w("XPlayer2", "PC Link audio: $message")
                pcAudio?.release()
                pcAudio = null
                failPcLinkAudio()
            }
        }
        pcAudio = player
        player.setMuted(pcAudioMuted)
        if (!player.start()) {
            player.release()
            pcAudio = null
            failPcLinkAudio()
        }
        updatePcLinkAudioButton()
    }

    /**
     * This phone couldn't play what the PC offered (§12: no output device, an engine error).
     *
     * Tell the PC to stop spending the bandwidth, and show the same muted state a deliberate mute
     * shows — which is honest (there is no sound) and, unlike a vanished button, leaves the user a
     * way back: unmuting asks for audio again, and the PC answers with a fresh `config`.
     */
    private fun failPcLinkAudio() {
        pcAudioMuted = true
        pcLinkClient?.setAudioEnabled(false)
        updatePcLinkAudioButton()
    }

    /** The overlay's speaker button: mute locally now, ask the PC to stop sending right after. */
    private fun togglePcLinkMute() {
        if (!isPcLinkMode) return
        pcAudioMuted = !pcAudioMuted
        pcAudio?.setMuted(pcAudioMuted)
        // Local gate first (instant), wire second (saves the 1.5 Mbit/s). The PC acknowledges by
        // re-sending `config` with or without `audio`, which flows back through onConfig.
        pcLinkClient?.setAudioEnabled(!pcAudioMuted)
        updatePcLinkAudioButton()
    }

    /**
     * Whether this session has sound to route at all — what the overlay's speaker button and the
     * remote's switch are both drawn from, asked in one place so the two cannot drift apart.
     */
    private fun pcHasSoundToRoute(): Boolean = PcLinkAudioRouting.hasSoundToRoute(
        offered = pcAudioOffered,
        playing = pcAudio != null,
        mutedHere = pcAudioMuted
    )

    /**
     * Shows the speaker button only while there is audio to mute — a PC that sends none (an older
     * server, or one whose capture failed) gets no dead control, and the debug overlay is where
     * "why is there no sound" is answered.
     *
     * Muted stays visible: it is the one state the user has to be able to undo. So does the moment
     * after unmuting, when the PC has stopped sending and its answer is still on the wire — see
     * [PcLinkAudioRouting].
     */
    private fun updatePcLinkAudioButton() {
        val button = pcAudioButton ?: return
        val visible = isPcLinkMode && pcHasSoundToRoute()
        button.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        button.text = getString(
            if (pcAudioMuted) R.string.pclink_audio_muted else R.string.pclink_audio_on
        )
        button.alpha = if (pcAudioMuted) 1f else 0.35f
    }

    /**
     * Re-anchors the virtual desktop dead ahead of wherever the user is looking right now —
     * gyro-only yaw drifts over a long session, and this is the one-gesture fix. Reached from
     * the phone remote's touchpad long-press and a long-press on the PC Link status overlay.
     */
    override fun recenterPcLink() {
        if (!isPcLinkMode) return
        presentation?.desktopView?.recenter()
    }

    /** Whether this player is running a PC Link session (for the phone remote's gesture routing). */
    fun isPcLinkActive(): Boolean = isPcLinkMode

    // ------------------------------------------------------------------------------------------
    // PcLinkSession.Host — what the PC-Mirror tab reads and drives
    // ------------------------------------------------------------------------------------------

    /**
     * One reading of this session, or null when this instance isn't running one.
     *
     * Counters, not rates — the caller has its own clock (see [PcLinkSession.Stats]). Everything
     * here is a plain field read on the main thread, so it costs nothing to ask once a second and
     * there is nothing to keep updated while nobody is asking.
     */
    override fun pcLinkStats(): PcLinkSession.Stats? {
        if (!isPcLinkMode) return null
        val client = pcLinkClient
        val audio = pcAudio
        val buffer = audio?.buffer
        val videoPts = pcLastRenderedPtsUs
        val audioPts = buffer?.lastPlayedPtsUs ?: 0L
        return PcLinkSession.Stats(
            sessionId = pcSessionId,
            serverName = pcLinkServerName,
            // A session with no client is parked, not streaming: with no glasses attached, this
            // activity drops the sockets when it stops (see onStop) and rebuilds them when it comes
            // back. Reporting the last state it saw would leave the tab saying "streaming" over a
            // frozen zero. With the glasses on — the case this tab is for — nothing is dropped and
            // this is the client's own state.
            link = if (client == null) PcLinkSession.Link.FAILED else pcLinkLink,
            // Both restart at zero when a dropped link is rebuilt (a new decoder, a new client),
            // which is why the contract tells readers to floor a negative difference at zero.
            framesRendered = pcDecoder?.framesRendered ?: 0L,
            videoBytes = client?.videoBytes?.get() ?: 0L,
            droppedFrames = pcDecoder?.droppedFrames ?: 0L,
            // Null all the way through rather than a zero: a parked or refused session has no client
            // to ask, and until the first pong lands the client has no answer either.
            rttMs = client?.lastRttUs?.div(1000f),
            codec = pcLinkConfig?.mime?.removePrefix("video/"),
            width = pcLinkConfig?.width ?: 0,
            height = pcLinkConfig?.height ?: 0,
            stereo = pcLinkConfig?.stereo,
            audioRateHz = audio?.format?.rate ?: 0,
            audioChannels = audio?.format?.channels ?: 0,
            audioBufferedMs = buffer?.bufferedMs ?: 0,
            // Ours (the stream ran dry) and the platform's (the track ran dry) are one fact to a
            // reader who is not debugging the feeder.
            audioDropouts = (buffer?.underruns ?: 0L) + (audio?.platformUnderruns?.toLong() ?: 0L),
            audioSkewMs = if (videoPts > 0L && audioPts > 0L) (videoPts - audioPts) / 1000L else null,
            audioToGlasses = !pcAudioMuted,
            audioAvailable = pcHasSoundToRoute()
        )
    }

    /**
     * The routing switch on the tab. Routed through the same [togglePcLinkMute] the overlay button
     * uses rather than repeating it: the local gate, the `set_audio` on the wire that hands the
     * PC's own speakers back, and the button's own label all move together there.
     */
    override fun setPcLinkAudioToGlasses(enabled: Boolean) {
        if (!isPcLinkMode) return
        if (enabled == !pcAudioMuted) return
        togglePcLinkMute()
    }

    /** The tab's disconnect. Same ending as an eviction: the session stops and the window goes. */
    override fun endPcLink() {
        if (!isPcLinkMode) return
        exitPcLink()
        finish()
    }

    // Last IMU state we asked GlassesController for, so lifecycle churn doesn't spam USB
    // start/stop commands (setImuStreaming is idempotent but each start is blocking USB I/O).
    private var pcImuStreaming = false

    /**
     * The on-demand IMU discipline (CLAUDE.md): stream head telemetry ONLY while the world-fixed
     * desktop is actually on the glasses — same rule as MainActivity's head-gesture menu. Called
     * from every place the answer can change: presentation created/dismissed, PC Link entered/
     * exited. No-op without a glasses controller (player launched with no MainActivity), in which
     * case the desktop renders head-fixed until one exists.
     */
    private fun updatePcLinkImu() {
        val want = isPcLinkMode && presentation?.isWorldFixedDesktop == true
        if (want == pcImuStreaming) return
        pcImuStreaming = want
        acquiredGlasses?.setImuStreaming(want)
    }

    private val pcLinkListener = object : PcLinkClient.Listener {
        /**
         * Whether what a client is saying still concerns us.
         *
         * A client outlives the session that owned it twice over: [close] races its own reader, and
         * a handover keeps one alive on purpose so its farewell can leave. The `config` that
         * arrives in either gap used to be applied — and `applyPcLinkAudioConfig` would then build
         * a fresh AudioTrack, putting the PC's sound back on top of the film we just made room for.
         * Both callbacks below run on Main, after whatever cleared these fields.
         */
        private val isLive: Boolean get() = isPcLinkMode && pcLinkClient != null

        override fun onState(state: PcLinkState) {
            if (!isLive) return
            when (state) {
                is PcLinkState.Connecting -> {
                    pcLinkLink = PcLinkSession.Link.CONNECTING
                    setPcLinkStatus(getString(R.string.pclink_connecting), dim = false)
                }
                is PcLinkState.Streaming -> {
                    pcLinkLink = PcLinkSession.Link.STREAMING
                    setPcLinkStatus(pcLinkServerName, dim = true)
                }
                is PcLinkState.Reconnecting -> {
                    pcLinkLink = PcLinkSession.Link.RECONNECTING
                    setPcLinkStatus("$pcLinkServerName — reconnecting (${state.attempt})", dim = false)
                }
                is PcLinkState.Failed -> {
                    pcLinkLink = PcLinkSession.Link.FAILED
                    setPcLinkStatus("$pcLinkServerName — disconnected: ${state.reason}", dim = false)
                }
                is PcLinkState.AuthFailed -> onPcLinkAuthFailed(state.reason)
            }
        }

        override fun onConfig(config: PcLinkStreamConfig) {
            if (!isLive) return
            pcLinkConfig = config
            pcLinkSourceIsSbs = config.isSbs
            pcVideoWidth = config.width
            pcVideoHeight = config.height
            pcDecoder?.configure(config.mime, config.width, config.height)
            applyPcLinkRenderConfig()
            applyPcLinkAudioConfig(config.audio)
            android.util.Log.i(
                "XPlayer2",
                "PC Link config: ${config.mime} ${config.width}x${config.height}@${config.fps} " +
                    "${config.stereo} audio=${config.audio?.let { "${it.codec} ${it.rate}/${it.channels}" } ?: "none"}"
            )
        }

        /** Network thread — straight into the decoder, no main-thread hop. */
        override fun onVideoFrame(frame: PcVideoFrame) {
            pcDecoder?.submit(frame)
        }

        /**
         * Network thread, same as [onVideoFrame] — the chunks share that socket. Straight into
         * the jitter buffer; the feeder thread inside the player is what talks to AudioTrack.
         */
        override fun onAudioChunk(ptsUs: Long, payload: ByteArray) {
            pcAudio?.submit(ptsUs, payload)
        }
    }

    /**
     * The PC refused our stored key. Two outcomes, and the difference is the whole of §8.4:
     *
     * * `unknown_client` — the PC has genuinely forgotten this phone. Nothing here can fix that, so
     *   we hand the user back to [PcConnectActivity] with [PcConnectActivity.EXTRA_PCLINK_REPAIR],
     *   which offers a fresh ceremony *behind a tap*. Never automatically: a re-pair must always
     *   resurface the 6-digit code, or a MITM could strip the pairing invisibly.
     * * anything else — `bad_proof`, an unverifiable server proof, a `protocol` refusal — is an
     *   impostor or corruption signal. We say so and offer nothing; the client has already stopped
     *   retrying, and "Forget this PC" stays a deliberate long-press on the connect screen.
     */
    private fun onPcLinkAuthFailed(reason: PairingFailure) {
        if (!isPcLinkMode) return
        disconnectPcLink()
        if (reason == PairingFailure.UNKNOWN_TO_PC) {
            pcLinkRepairPending = true
            // With the picture on the glasses the link keeps running while this activity is
            // stopped (see onStop), and a stopped activity may not launch another one — so the
            // bounce is made by the remote in front of us instead. With neither of us started
            // (a parked session, no glasses) it waits for onStart, and the overlay says why.
            if (!bouncePcLinkRepair()) {
                setPcLinkStatus(
                    "$pcLinkServerName — ${getString(R.string.pclink_stream_unknown_client)}",
                    dim = false
                )
            }
            return
        }
        val message = if (reason == PairingFailure.AUTH_FAILED) {
            R.string.pclink_stream_auth_failed
        } else {
            R.string.pclink_stream_auth_error
        }
        setPcLinkStatus("$pcLinkServerName — ${getString(message)}", dim = false)
    }

    /**
     * Hands the user back to [PcConnectActivity] with the re-pair request, if anyone is in a
     * position to. Returns true once that's under way — the caller must then not re-open the link,
     * which would only earn the same refusal.
     *
     * During a cast this activity is *stopped* behind [PcLinkRemoteActivity] and may not launch
     * anything, and it never comes back to STARTED while the remote is up — so waiting for its own
     * `onStart` waited forever and the remote sat on a bare "Disconnected" with no route to the
     * ceremony. The app is in the foreground either way, so the launch is simply made by whichever
     * screen is in front (see [PcLinkRemotePolicy.repairLauncher]).
     */
    private fun bouncePcLinkRepair(): Boolean {
        if (!pcLinkRepairPending) return false
        val remote = PcLinkRemoteActivity.currentInstance
            ?.takeIf { it.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        val launcher = PcLinkRemotePolicy.repairLauncher(
            playerStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            remoteStarted = remote != null
        )
        val intent = Intent(this, PcConnectActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(PcConnectActivity.EXTRA_PCLINK_REPAIR, true)
            putExtra(PcConnectActivity.EXTRA_PCLINK_HOST, pcLinkHost)
            putExtra(PcConnectActivity.EXTRA_PCLINK_CONTROL_PORT, pcLinkControlPort)
            putExtra(PcConnectActivity.EXTRA_PCLINK_VIDEO_PORT, pcLinkVideoPort)
            putExtra(PcConnectActivity.EXTRA_PCLINK_NAME, pcLinkServerName)
            putExtra(PcConnectActivity.EXTRA_PCLINK_SERVER_ID, pcLinkServerId)
        }
        when (launcher) {
            PcLinkRemotePolicy.RepairLauncher.PLAYER -> startActivity(intent)
            // Already leaving of its own accord: keep the request pending rather than dropping it.
            PcLinkRemotePolicy.RepairLauncher.REMOTE ->
                if (remote?.startRepair(intent) != true) return false
            PcLinkRemotePolicy.RepairLauncher.NOBODY -> return false
        }
        pcLinkRepairPending = false
        // Still the usual ending: onDestroy runs exitPcLink(), so the PC gets its speakers back.
        finish()
        return true
    }

    private val pcDecoderListener = object : PcStreamDecoder.Listener {
        override fun onRequestIdr() {
            pcLinkClient?.requestIdr()
        }

        override fun onDecoderError(message: String) {
            runOnUiThread {
                if (isPcLinkMode) setPcLinkStatus("$pcLinkServerName — decoder: $message", dim = false)
            }
        }

        /** Codec callback thread: volatile writes only. */
        override fun onFrameRendered(ptsUs: Long) {
            val delta = android.os.SystemClock.elapsedRealtimeNanos() / 1000L - ptsUs
            if (delta < pcLatencyFloorUs) pcLatencyFloorUs = delta
            pcLatencyUs = delta - pcLatencyFloorUs
            // Both streams carry the server's clock, so the difference between this and the pts
            // of the audio at the DAC is the A/V skew — free to compute, and the only honest way
            // to check lipsync without a slow-motion camera (audio-design §9).
            pcLastRenderedPtsUs = ptsUs
        }

        override fun onVideoSize(width: Int, height: Int) {
            runOnUiThread {
                if (!isPcLinkMode || width <= 0 || height <= 0) return@runOnUiThread
                pcVideoWidth = width
                pcVideoHeight = height
                applyPcLinkRenderConfig()
            }
        }
    }

    // --- overlay: connection state + a tappable latency/throughput readout ---------------------

    private fun ensurePcLinkOverlay() {
        if (pcStatusView != null) return
        val root = findViewById<ViewGroup>(android.R.id.content) ?: return
        val pad = (12 * resources.displayMetrics.density).toInt()
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor("#B0000000".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, pad / 2, pad, pad / 2)
            isClickable = true
            // The overlay is also the toggle: there is no other UI in this mode, and the debug
            // readout is exactly what a "why is it laggy?" report needs.
            setOnClickListener { togglePcLinkDebug() }
            // …and the re-center handle: long-press snaps the world-fixed desktop back to dead
            // ahead (gyro yaw drifts over a long session). Harmless no-op on the phone fallback.
            setOnLongClickListener {
                if (presentation?.desktopView == null) return@setOnLongClickListener false
                recenterPcLink()
                setPcLinkStatus(getString(R.string.pclink_recentered), dim = false)
                uiHandler.postDelayed({
                    if (isPcLinkMode && pcLinkClient != null) setPcLinkStatus(pcLinkServerName, dim = true)
                }, 1200L)
                true
            }
        }
        root.addView(
            status,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                setMargins(pad, pad, pad, pad)
            }
        )
        // The mute control. Its own tap target opposite the status line: the status line is
        // already the debug toggle, and muting must never be one mis-tap away from that.
        val audio = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor("#B0000000".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(pad, pad / 2, pad, pad / 2)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { togglePcLinkMute() }
        }
        root.addView(
            audio,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(pad, pad, pad, pad)
            }
        )
        val debug = TextView(this).apply {
            setTextColor("#80FF80".toColorInt())
            setBackgroundColor("#B0000000".toColorInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(pad, pad / 2, pad, pad / 2)
            visibility = View.GONE
        }
        root.addView(
            debug,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
                setMargins(pad, pad, pad, pad)
            }
        )
        pcStatusView = status
        pcAudioButton = audio
        pcDebugView = debug
        updatePcLinkAudioButton()
    }

    private fun setPcLinkStatus(text: String, dim: Boolean) {
        val view = pcStatusView ?: return
        view.text = text
        // Dimmed = "everything is fine": still there (and still tappable) but out of the way of
        // the picture. Anything else stays fully legible.
        view.alpha = if (dim) 0.35f else 1f
    }

    private fun togglePcLinkDebug() {
        val debug = pcDebugView ?: return
        if (debug.visibility == View.VISIBLE) {
            debug.visibility = View.GONE
            stopPcLinkDebugTicker()
        } else {
            debug.visibility = View.VISIBLE
            startPcLinkDebugTicker()
        }
    }

    private fun startPcLinkDebugTicker() {
        stopPcLinkDebugTicker()
        pcStatsAtMs = 0L
        val tick = object : Runnable {
            override fun run() {
                updatePcLinkDebugText()
                uiHandler.postDelayed(this, PCLINK_DEBUG_INTERVAL_MS)
            }
        }
        pcDebugTicker = tick
        uiHandler.post(tick)
    }

    private fun stopPcLinkDebugTicker() {
        pcDebugTicker?.let { uiHandler.removeCallbacks(it) }
        pcDebugTicker = null
    }

    private fun updatePcLinkDebugText() {
        val view = pcDebugView ?: return
        val client = pcLinkClient
        val decoder = pcDecoder
        val now = android.os.SystemClock.elapsedRealtime()
        val frames = decoder?.framesRendered ?: 0L
        val bytes = client?.videoBytes?.get() ?: 0L
        if (pcStatsAtMs > 0L && now > pcStatsAtMs) {
            val dt = (now - pcStatsAtMs) / 1000f
            pcStatsFps = (frames - pcStatsFrames) / dt
            pcStatsMbps = (bytes - pcStatsBytes) * 8f / dt / 1_000_000f
        }
        pcStatsAtMs = now
        pcStatsFrames = frames
        pcStatsBytes = bytes
        val config = pcLinkConfig
        view.text = buildString {
            append(config?.let { "${it.mime.removePrefix("video/")} ${it.width}x${it.height} ${it.stereo}" }
                ?: "no config")
            append("\nfps ").append("%.1f".format(pcStatsFps))
            append("  drop ").append(decoder?.droppedFrames ?: 0L)
            append("  frames ").append(frames)
            append("\nrate ").append("%.1f".format(pcStatsMbps)).append(" Mbps")
            append("  resync ").append(client?.resyncBytes?.get() ?: 0L).append(" B")
            append("\nlat +").append(pcLatencyUs / 1000L).append(" ms")
            append("  rtt ").append("%.1f".format((client?.lastRttUs ?: 0L) / 1000f)).append(" ms")
            appendPcLinkAudioDebug(client)
        }
    }

    /**
     * The audio line of the debug overlay: buffer depth, the corrections the jitter buffer has
     * had to make, and the A/V skew — which is what a "the sound is out of sync" report needs and
     * what §9's field measurement is checked against.
     *
     * Skew is (video pts rendered − audio pts at the DAC) on the server's own clock: positive
     * means audio is behind the picture, the direction the design deliberately chose.
     */
    private fun StringBuilder.appendPcLinkAudioDebug(client: PcLinkClient?) {
        val audio = pcAudio
        if (audio == null) {
            append("\naudio ").append(if (pcAudioMuted) "muted" else "none")
            return
        }
        val buffer = audio.buffer
        append("\naudio ").append(audio.format.rate / 1000).append("k/")
            .append(audio.format.channels).append("ch")
        if (audio.isMuted) append(" muted")
        append("  buf ").append(buffer.bufferedMs).append(" ms")
        // Ours (the stream ran dry) and the platform's (the track ran dry, which our silence is
        // there to prevent) — a rising second number means the feeder isn't keeping up locally.
        append("  under ").append(buffer.underruns).append("/").append(audio.platformUnderruns)
        append("\ncorr -").append(buffer.driftDrops).append("/+").append(buffer.driftInserts)
        append("  resync ").append(buffer.hardResyncs)
        append("  gap ").append(buffer.discontinuities)
        append("  drop ").append(client?.audioDropped?.get() ?: 0L)
        val videoPts = pcLastRenderedPtsUs
        val audioPts = buffer.lastPlayedPtsUs
        if (videoPts > 0L && audioPts > 0L) {
            append("  skew ").append((videoPts - audioPts) / 1000L).append(" ms")
        }
    }
}
