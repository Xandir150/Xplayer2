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
import com.teleteh.xplayer2.data.depth.DepthEstimator
import com.teleteh.xplayer2.data.depth.DepthFrameWorker
import com.teleteh.xplayer2.data.depth.DepthModelManager
import com.teleteh.xplayer2.data.glasses.GlassesController
import com.teleteh.xplayer2.ui.util.DisplayUtils
import com.teleteh.xplayer2.BuildConfig
import com.teleteh.xplayer2.util.VideoStreamExtractor
import com.teleteh.xplayer2.util.WebSourceClassifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
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

        // Current instance for remote control access
        var currentInstance: PlayerActivity? = null
            private set
    }

    private fun navigateBackToPrimary() {
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
    private var pendingDepthTick: Runnable? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this
        
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

        // Hold the glasses' USB link open for our whole session. MainActivity is stopped while
        // the player owns the goggles, so without this its onStop() would release the connection
        // and features that read from USB (the Lazy-3D head-tracking IMU) would have no link.
        // Ref-counted in GlassesController, so this composes with MainActivity's own acquire.
        MainActivity.glassesControllerForPlayback?.register()

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
        val renderersFactory = DefaultRenderersFactory(this)
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
                // Listen for metadata/title updates to reflect in UI and Recent
                exo.addListener(object : Player.Listener {
                    override fun onAudioSessionIdChanged(audioSessionId: Int) {
                        rebindLoudnessEnhancer(audioSessionId)
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
        startActivity(Intent(this, RemoteControlActivity::class.java).apply {
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
        if (currentInstance == this) {
            currentInstance = null
        }
        saveProgress()
        if (lazy3dEnabled) stopLazy3d()
        unregisterDisplayListener()
        // Release our hold on the glasses USB link (ref-counted; MainActivity keeps it alive if
        // it's in the foreground). Done after stopLazy3d() so the IMU stream is halted first.
        MainActivity.glassesControllerForPlayback?.unregister()
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

    /** Current volume-boost button label, e.g. "Boost: off" / "Boost: +12 dB". */
    fun getVolumeBoostLabel(): String = boostLabel(getVolumeBoostMb())

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
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    private fun stopPlaybackService() {
        if (serviceBound) {
            playbackService?.stopForegroundPlayback()
            try { unbindService(serviceConnection) } catch (_: Throwable) { }
            serviceBound = false
            playbackService = null
        }
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
        // Already showing on this display
        if (presentation?.display?.displayId == ext.displayId) return
        
        dismissPresentation()
        
        // Create Presentation and route video there
        val pres = ExternalPlayerPresentation(this, ext) { surface ->
            presentationSurface = surface
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
            // The Presentation's glView is now the active render target — push the full
            // current render config (SBS / source-layout / resize / aspect) and re-derive
            // the mode for this display so resize, OU/SBS and parallax all take effect.
            applyRenderMode()
            if (lazy3dEnabled) reapplyLazy3dToActiveView()
        } catch (e: Throwable) {
            android.util.Log.e("XPlayer2", "Failed to show Presentation", e)
            presentation = null
            presentationDisplayId = -1
            // Restore local rendering
            glView?.visibility = View.VISIBLE
            glSurface?.let { player?.setVideoSurface(it) }
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

    // Coalesce the hot-plug burst: both add and remove reschedule the SAME check, so it never
    // gets starved by a trailing event and fires once the dust settles.
    private fun scheduleExternalReconcile() {
        uiHandler.removeCallbacks(externalReconcile)
        uiHandler.postDelayed(externalReconcile, 1200L)
    }

    private fun reconcileExternalDisplay() {
        if (player == null) return
        val dm = getSystemService(DisplayManager::class.java)
        val ext = DisplayUtils.findUltraWideExternalDisplay(this)
        val extAlive = ext != null &&
            (dm?.getDisplay(ext.displayId)?.state ?: Display.STATE_ON) != Display.STATE_OFF
        if (extAlive) {
            // Panel present (e.g. glasses connected after starting on the phone): move the
            // picture to the goggles, push the saved mode and bring up the remote.
            val wasShowing = presentation != null
            tryShowExternalPresentation()
            if (presentation != null && !wasShowing) {
                MainActivity.glassesControllerForPlayback?.reapplySavedMode()
                showRemoteControlFront()
            }
        } else if (presentation != null) {
            onExternalPanelLost()
        }
    }

    // Goggles came off (proximity sensor cut the panel) or the external display was unplugged:
    // just stop, as if the user hit Stop. The position is saved, so they pick the clip back up
    // from Recent when ready. Far simpler and more predictable than juggling player/remote layers.
    private fun onExternalPanelLost() {
        android.util.Log.i("XPlayer2", "External panel gone -> stop playback")
        saveProgress()
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
     *   - aspect ≥ 1.95 → SBS source (wider than mono 16:9 = 1.78; covers 2:1, 21:9 cinema-SBS,
     *     32:9 Half-SBS). Anything close to but not quite 16:9 is treated as mono since pure
     *     16:9 frames are ambiguous with Full-SBS / Full-OU encoded into the same canvas.
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
            aspect >= 1.95f -> SourceLayout.Sbs
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
        when {
            stereoMode == StereoMode.Off && lazy3dEnabled -> {
                setLazy3dEnabled(false); stereoMode = StereoMode.Ou        // Lazy 3D → OU→SBS
            }
            stereoMode == StereoMode.Off && isLazy3dSupported() ->
                setLazy3dEnabled(true)                                     // 2D → Lazy 3D
            stereoMode == StereoMode.Off -> stereoMode = StereoMode.Ou     // 2D → OU→SBS (no Lazy 3D)
            stereoMode == StereoMode.Ou -> stereoMode = StereoMode.Sbs     // OU→SBS → SBS
            else -> stereoMode = StereoMode.Off                            // SBS → 2D
        }
        sbsExplicitlyConfigured = true
        Toast.makeText(this, getStereoModeLabel(), Toast.LENGTH_SHORT).show()
        applyRenderMode()
        if (lazy3dEnabled) reapplyLazy3dToActiveView()
        updateStereoControlButtons()
        btnSbsRef?.let { applySbsButtonVisual(it) }
        RemoteControlActivity.currentInstance?.syncControls()
        saveProgress()
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
    }

    fun seekRelative(deltaMs: Long) {
        player?.let { exo ->
            val newPos = (exo.currentPosition + deltaMs).coerceIn(0, exo.duration.coerceAtLeast(0))
            exo.seekTo(newPos)
        }
    }

    fun isStereoSbsEnabled(): Boolean = getStereoSbs()

    /** True only in OU→SBS mode — the vertical Shift control is meaningful only there. */
    fun isOuSbsMode(): Boolean = stereoMode == StereoMode.Ou

    /** Remote-control entry point: cycle 2D → OU→SBS → SBS. */
    fun toggleStereoSbs() = cycleStereoMode()

    /** Current stereo-mode label for the remote ("2D" / "OU→SBS" / "SBS"). */
    fun getStereoModeLabel(): String = when {
        lazy3dEnabled -> "Lazy 3D ⚠️"
        stereoMode == StereoMode.Ou -> "OU→SBS"
        stereoMode == StereoMode.Sbs -> "SBS"
        else -> "2D"
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
     * Live telemetry for the remote's temporary Lazy-3D debug overlay, or null when Lazy 3D is
     * off / nothing to report (so the remote hides the row). Shows the depth-synthesis inference
     * time. Remove the overlay once tuning is done.
     */
    fun lazy3dDebugLine(): String? {
        if (!lazy3dEnabled) return null
        val est = depthEstimator
        return if (est?.isReady() == true) "depth ${"%.0f".format(est.avgInferenceMs)}ms" else null
    }

    /**
     * Coarse status for the remote to show feedback right after the toggle is tapped, so the
     * user isn't tempted to tap again while the (slow, off-thread) startup is still spinning up.
     * "Active" means real data is flowing: IMU samples for an XREAL link, or a ready depth model
     * when there's no IMU link.
     */
    fun lazy3dStatus(): Lazy3dStatus {
        if (!lazy3dEnabled) return Lazy3dStatus.Off
        if (depthEstimator?.isReady() == true) return Lazy3dStatus.Active
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
     * Whether Lazy 3D can run now or could obtain what it needs:
     *  - somewhere to SHOW a stereo pair (see [hasLazy3dDisplay]), AND
     *  - depth model bundled in assets / already cached locally, OR
     *  - device has any network capability (model can be downloaded on first use).
     * Gates the Lazy 3D step in the stereo-mode cycle (and with it the remote's toggle).
     */
    fun isLazy3dSupported(): Boolean {
        if (!hasLazy3dDisplay()) return false
        val hasDepthModel = DepthModelManager(applicationContext).isAvailable()
        val hasNetwork = isOnAnyNetwork()
        return hasDepthModel || hasNetwork
    }

    /**
     * Lazy 3D synthesizes an SBS pair, so it needs a display that can actually SHOW one: the
     * glasses' external Presentation, or an ultrawide main display (glasses as the primary
     * screen, e.g. a Beam-style box — the same case duplicate-mono targets). On a normal phone
     * screen an SBS split is just a useless double picture, so the mode isn't offered there.
     */
    private fun hasLazy3dDisplay(): Boolean = presentation != null || activeDisplayIsUltrawide()

    private fun isOnAnyNetwork(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) { false }

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
                Toast.makeText(this, R.string.lazy3d_downloading, Toast.LENGTH_SHORT).show()
                depthDownloadJob?.cancel()
                depthDownloadJob = lifecycleScope.launch {
                    var lastPercent = -1
                    val ok = mgr.ensureAvailable { bytes, total ->
                        if (total > 0) {
                            val pct = (bytes * 100 / total).toInt()
                            if (pct != lastPercent && pct % 10 == 0) {
                                lastPercent = pct
                                android.util.Log.i("XPlayer2", "Lazy 3D model: $pct% ($bytes/$total)")
                            }
                        }
                    }
                    depthDownloadJob = null
                    if (!lazy3dEnabled) return@launch // user toggled off while downloading
                    if (ok) {
                        Toast.makeText(this@PlayerActivity, R.string.lazy3d_downloaded, Toast.LENGTH_SHORT).show()
                        startLazy3dDepth()
                    } else {
                        // Download failed — drop the toggle so the button/status don't claim an
                        // "active" Lazy 3D that will never come, and so the user can simply retry.
                        Toast.makeText(this@PlayerActivity, R.string.lazy3d_download_failed, Toast.LENGTH_LONG).show()
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
        val gen = lazy3dGen
        lifecycleScope.launch {
          try {
            // Loading the TFLite model + GPU init takes a couple of seconds — do it off the
            // main thread so the toggle doesn't freeze. GL wiring happens back on the main thread.
            val estimator = withContext(Dispatchers.IO) {
                DepthEstimator().apply { if (!init(applicationContext)) close() }
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
            activeGlView()?.setStereoParams(divergence = LAZY3D_DIVERGENCE, convergence = 0.5f)
            activeGlView()?.setOnFrameReadbackListener { pixels, w, h, ts ->
                // Called on the GL thread when a fresh source snapshot is ready.
                worker.submit(pixels, w, h, ts)
            }
            startDepthTick(worker, estimator)
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
        val depthTick = object : Runnable {
            private var ticks = 0
            override fun run() {
                val depth = worker.pollLatestDepth()
                if (depth != null) {
                    val bytes = ByteArray(depth.size)
                    for (i in depth.indices) {
                        bytes[i] = (depth[i].coerceIn(0f, 1f) * 255f).toInt().toByte()
                    }
                    activeGlView()?.setDepthMap(bytes, estimator.inputSize, estimator.inputSize)
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
            v.setStereoParams(divergence = LAZY3D_DIVERGENCE, convergence = 0.5f)
            v.setOnFrameReadbackListener { pixels, w, h, ts -> worker.submit(pixels, w, h, ts) }
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
}
