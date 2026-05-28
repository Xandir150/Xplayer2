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
import android.media.MediaRouter
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
import androidx.core.content.edit
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
import com.teleteh.xplayer2.ui.util.DisplayUtils
import com.teleteh.xplayer2.util.VideoStreamExtractor
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val EXTRA_TITLE = "title"
        
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
    private var routeCallback: MediaRouter.SimpleCallback? = null
    private var sourceUri: Uri? = null
    private var titleCenterView: TextView? = null
    private var currentResolvedTitle: String? = null
    private var btnSbsRef: MaterialButton? = null
    private var btnShiftRef: MaterialButton? = null
    private var btnResizeModeRef: MaterialButton? = null
    // 0=Auto, 1=16:9, 2=4:3, 3=21:9, 4=32:9, 5=1:1, 6=2.39:1
    private var resizeMode: Int = 0

    // Audio gain via LoudnessEnhancer (persisted globally in player_prefs)
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastAudioSessionId: Int = C.AUDIO_SESSION_ID_UNSET

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
    private var extractedTitle: String? = null
    // Flag to prevent premature player initialization during stream extraction
    private var isExtractingStream: Boolean = false

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
        btnSbsRef = btnSbs
        btnShiftRef = btnShift
        btnResizeModeRef = btnResizeMode
        titleCenterView = overlay.findViewById(R.id.tvTitleCenter)
        // Audio menu containers
        audioMenuRoot = overlay.findViewById(R.id.audioMenuRoot)
        audioMenuCenter = overlay.findViewById(R.id.audioMenuCenter)
        audioMenuLeft = overlay.findViewById(R.id.audioMenuLeft)
        audioMenuRight = overlay.findViewById(R.id.audioMenuRight)
        audioMenuRoot?.setOnClickListener { hideAudioMenu() }
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
        // Configure controllers with same behavior
        val timeoutMs = 3000
        playerView.controllerShowTimeoutMs = timeoutMs
        playerView.controllerHideOnTouch = true
        playerView.controllerAutoShow = true
        // Mirror overlay visibility to controller visibility only
        val controllerListener = ControllerVisibilityListener { visibility ->
            overlay.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }
        playerView.setControllerVisibilityListener(controllerListener)
        hideSystemBars()
        updateSbsUi()

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
        android.util.Log.i("XPlayer2", "Source URI: $uri, host=${uri.host}, isSupported=${VideoStreamExtractor.isSupported(uri)}")
        if (VideoStreamExtractor.isSupported(uri)) {
            titleCenterView?.text = getString(R.string.loading_stream)
            android.util.Log.i("XPlayer2", "Starting stream extraction for: $uri")
            isExtractingStream = true
            lifecycleScope.launch {
                try {
                    val extracted = VideoStreamExtractor.extract(uri)
                    isExtractingStream = false
                    if (extracted != null) {
                        android.util.Log.i("XPlayer2", "Stream extracted successfully: ${extracted.url}")
                        resolvedStreamUri = Uri.parse(extracted.url)
                        extractedTitle = extracted.title
                        if (!extracted.title.isNullOrBlank()) {
                            currentResolvedTitle = extracted.title
                        }
                        initializePlayer()
                        updateCenterTitle()
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
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) seekRelative(-5000L)
                else seekRelative(15000L)
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
                    seekRelative(-5000L); true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!isOverlayVisible && !isControllerVisible && !isAudioMenuVisible) {
                    seekRelative(15000L); true
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
            else -> super.onKeyDown(keyCode, event)
        }
    }

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
                exo.setMediaItem(mediaItem)

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
                        android.util.Log.d("XPlayer2", "Binding existing Presentation surface to new player")
                        exo.setVideoSurface(surface)
                    }
                }

                // Resume position if provided or stored
                // Use sourceUri for recents lookup (not resolvedStreamUri which may be different for extracted streams)
                val requestedStart = intent?.getLongExtra(EXTRA_START_POSITION_MS, -1L) ?: -1L
                val store = RecentStore(this)
                val recent = store.find((sourceUri ?: uri).toString())
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
                // Initialize SBS state per item
                val dm = resources.displayMetrics
                val ultraWide = (dm.widthPixels.toFloat() / (dm.heightPixels.takeIf { it > 0 }
                    ?: 1).toFloat()) >= 3.2f
                val initialSbs = recent?.sbsEnabled ?: ultraWide
                android.util.Log.i("XPlayer2", "Initializing SBS: recent?.sbsEnabled=${recent?.sbsEnabled}, ultraWide=$ultraWide, initialSbs=$initialSbs")
                setStereoSbs(initialSbs)
                glView?.setSbsEnabled(initialSbs)
                // If the user already configured SBS for this clip (Recent entry carries a value),
                // keep auto-detect out of the way. New clips are still eligible for auto OU→SBS.
                sbsExplicitlyConfigured = recent?.sbsEnabled != null
                // Don't set duplicateMonoToSbs here - wait for onVideoSizeChanged to know if video is stereo
                // Initially disable duplication, it will be enabled in updateSbsUi() if needed
                glView?.setDuplicateMonoToSbs(false)
                // Refresh button visuals to reflect initial per-item state
                btnSbsRef?.let { applySbsButtonVisual(it) }
                // Choose pipeline now that we know initial SBS state.
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
                        glView?.updateVideoAspectRatio(videoSize.width, videoSize.height)
                        applySourceLayoutDetection()
                        applySbsShiftIfNeeded()
                        // Update duplicate mono logic based on video aspect ratio
                        updateSbsUi()
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
                            applySourceLayoutDetection()
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

    private var remoteControlLaunched = false
    
    override fun onStart() {
        super.onStart()
        // Don't initialize player if stream extraction is in progress
        if (player == null && sourceUri != null && !isExtractingStream) initializePlayer()
        // Try to show Presentation on external display
        tryShowExternalPresentation()
        
        // If Presentation is active, launch RemoteControlActivity on phone
        if (presentation != null && !remoteControlLaunched) {
            remoteControlLaunched = true
            val intent = Intent(this, RemoteControlActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
        }
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
            val dm = getSystemService(DisplayManager::class.java)
            displayListener?.let { dm?.unregisterDisplayListener(it) }
            displayListener = null
            // Unregister MediaRouter callback
            DisplayUtils.unregisterRouteCallback(this, routeCallback)
            routeCallback = null
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
        releaseLoudnessEnhancer()
        player?.release()
        player = null
        stopPlaybackService()
    }

    // --- Audio gain (LoudnessEnhancer) ---
    private fun getVolumeBoostMb(): Int =
        getSharedPreferences("player_prefs", MODE_PRIVATE).getInt("volume_boost_mb", 0).coerceIn(0, 2400)

    private fun setVolumeBoostMb(value: Int) {
        val clamped = value.coerceIn(0, 2400)
        getSharedPreferences("player_prefs", MODE_PRIVATE).edit { putInt("volume_boost_mb", clamped) }
        if (clamped <= 0) {
            // No boost requested — detach the effect entirely. Letting an enabled=false
            // LoudnessEnhancer linger on the audio session has been observed to lock the
            // USB audio device on XREAL Air goggles, killing audio system-wide until the
            // app process is uninstalled.
            releaseLoudnessEnhancer()
            return
        }
        val enhancer = loudnessEnhancer ?: run {
            val sid = lastAudioSessionId
            if (sid == C.AUDIO_SESSION_ID_UNSET || sid == 0) return
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
        lastAudioSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private fun boostLabel(mb: Int): String {
        val db = mb / 100
        return if (mb <= 0) getString(R.string.volume_boost_off)
        else getString(R.string.volume_boost_with_value, db)
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
                        // Cycle through 0, +6, +12, +18, +24 dB (millibels)
                        val steps = intArrayOf(0, 600, 1200, 1800, 2400)
                        val cur = getVolumeBoostMb()
                        val next = steps.firstOrNull { it > cur } ?: 0
                        setVolumeBoostMb(next)
                        text = boostLabel(next)
                        Toast.makeText(this@PlayerActivity, boostLabel(next), Toast.LENGTH_SHORT).show()
                    }
                }
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
                    if (isSelected) setTypeface(typeface, Typeface.BOLD)
                    alpha = if (isSelected) 1.0f else 0.85f
                    setOnClickListener {
                        applyTrackSelection(item, trackType)
                        hideTrackMenu()
                    }
                }
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
    }

    private fun showAudioMenu() = showTrackMenu(C.TRACK_TYPE_AUDIO)
    private fun showSubtitleMenu() = showTrackMenu(C.TRACK_TYPE_TEXT)

    private fun hideTrackMenu() {
        audioMenuRoot?.visibility = View.GONE
    }

    private fun hideAudioMenu() = hideTrackMenu()

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
            android.util.Log.d("XPlayer2", "Presentation surface callback: surface=$surface, player=${player != null}")
            if (surface != null) {
                player?.let { exo ->
                    android.util.Log.d("XPlayer2", "Presentation surface ready, binding to player")
                    exo.setVideoSurface(surface)
                }
                // Hide local GL view - video goes to Presentation only
                glView?.visibility = View.GONE
            } else {
                player?.clearVideoSurface()
            }
        }
        presentation = pres
        try {
            pres.show()
            android.util.Log.d("XPlayer2", "Presentation shown on display ${ext.displayId}")
            
            // Clear main surface - video will render on Presentation
            player?.clearVideoSurface()
            glView?.visibility = View.GONE
            
            // Configure SBS for ultrawide
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            ext.getMetrics(dm)
            val ratio = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
            val isUltrawide = ratio >= 3.2f
            
            // Enable SBS on Presentation
            pres.setSbsEnabled(getStereoSbs() || isUltrawide)
            pres.setPlayer(player)
        } catch (e: Throwable) {
            android.util.Log.e("XPlayer2", "Failed to show Presentation", e)
            presentation = null
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
            presentationSurface = null
            // Restore local GL view
            glView?.visibility = View.VISIBLE
            glSurface?.let { player?.setVideoSurface(it) }
            updatePlaybackService()
        }
    }

    private fun saveProgress() {
        val uri = sourceUri ?: return
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
            uri = uri.toString(),
            title = title,
            lastPositionMs = position,
            durationMs = duration,
            lastPlayedAt = System.currentTimeMillis(),
            framePacking = framePacking,
            sbsEnabled = getStereoSbs(),
            sbsShiftEnabled = sbsShiftEnabled,
            sourceType = RecentEntry.detectSourceType(uri),
            resizeMode = resizeMode
        )
        RecentStore(this).upsert(entry)
    }

    /**
     * Decide whether the source frame is laid out side-by-side (LEFT_RIGHT) or over-under
     * (TOP_BOTTOM / mono), forward that to the GL renderer via setSourceIsSbs, and — when
     * the user has not yet picked an SBS state for this clip — auto-enable the OU→SBS
     * toggle for OU sources (the project's main feature).
     *
     * Detection only runs for offline sources (file:// / content:// / smb://). HTTP(S) streams
     * rarely carry MKV/MP4 stereo metadata and the aspect-ratio heuristic produces too many
     * false positives there, so we leave the manual SBS button as the only signal.
     *
     * Order of precedence:
     *  1. Format.stereoMode from the selected video track (read in onTracksChanged).
     *  2. Aspect-ratio heuristic on the source video size.
     */
    private fun applySourceLayoutDetection() {
        val isOnline = (resolvedStreamUri ?: sourceUri)?.scheme?.lowercase() in setOf("http", "https")
        if (isOnline) return // setSourceIsSbs is useless without reliable metadata

        val stereo = detectedSourceStereoMode
        val w = lastVideoWidth
        val h = lastVideoHeight
        val aspect = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else 0f

        // Aspect ranges for half-packed stereo content (the common XR delivery format):
        //   Half-SBS:  3840x1080 → ≈3.56,  1920x540  → ≈3.56
        //   Half-OU:   1920x2160 → ≈0.89,  3840x2160 → ≈1.78 (ambiguous with mono 16:9)
        val (sourceIsSbs, sourceIsOu) = when (stereo) {
            C.STEREO_MODE_LEFT_RIGHT -> true to false
            C.STEREO_MODE_TOP_BOTTOM -> false to true
            C.STEREO_MODE_MONO -> false to false
            else -> {
                val sbsByAspect = aspect >= 2.5f
                val ouByAspect = aspect in 0.4f..1.15f && aspect > 0f
                sbsByAspect to (ouByAspect && !sbsByAspect)
            }
        }
        glView?.setSourceIsSbs(sourceIsSbs)

        // Auto OU→SBS: kick the SBS toggle on for OU sources the first time we recognise them.
        // We only do this when the user (or a saved Recent entry) hasn't already chosen a state,
        // so subsequent manual flips stick.
        if (!sbsExplicitlyConfigured && sourceIsOu && !getStereoSbs()) {
            android.util.Log.i("XPlayer2", "Auto-enabling SBS for OU source (stereoMode=$stereo, aspect=$aspect)")
            setStereoSbs(true)
            glView?.setSbsEnabled(true)
            btnSbsRef?.let { applySbsButtonVisual(it) }
            applySbsShiftIfNeeded()
            updateSbsUi() // already calls applyVideoPipeline()
            // Don't mark sbsExplicitlyConfigured — leave room for the user to disable later
            // without losing the auto-detect chance for a different clip.
            saveProgress()
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

        val sbs = getStereoSbs()
        val dm = resources.displayMetrics
        val ultraWide = dm.widthPixels.toFloat() /
            dm.heightPixels.coerceAtLeast(1).toFloat() >= 3.2f
        val needsDuplicateMono = ultraWide && !sbs && !isVideoStereo()
        val needsGl = sbs || needsDuplicateMono
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
        btnResizeModeRef?.text = when (resizeMode) {
            1 -> "16:9"
            2 -> "4:3"
            3 -> "21:9"
            4 -> "32:9"
            5 -> "1:1"
            6 -> "2.39:1"
            else -> "Auto"
        }
        glView?.updateResizeMode(resizeMode)
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
    private fun toggleStereoMode() {
        val newSbs = !getStereoSbs()
        setStereoSbs(newSbs)
        // TODO: when XR Subspace is wired, apply here:
        // applyStereoModeXR(if (newSbs) XR StereoMode.LEFT_RIGHT else XR StereoMode.MONO)
        Toast.makeText(
            this,
            if (newSbs) getString(R.string.stereo_mode_sbs) else getString(R.string.stereo_mode_normal),
            Toast.LENGTH_SHORT
        ).show()
        glView?.setSbsEnabled(newSbs)
        presentation?.setSbsEnabled(newSbs)
        applySbsShiftIfNeeded()
        applyVideoPipeline()
        saveProgress()
    }

    private fun getStereoSbs(): Boolean {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        return prefs.getBoolean("stereo_sbs", false)
    }

    private fun setStereoSbs(value: Boolean) {
        val prefs = getSharedPreferences("player_prefs", MODE_PRIVATE)
        prefs.edit { putBoolean("stereo_sbs", value) }
    }

    // --- SBS vertical shift to approximate 16:9 without bars ---
    private fun applySbsShiftIfNeeded() {
        val gl = glView ?: return
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

    /**
     * Check if video appears to be stereo based on aspect ratio:
     * - SBS (Side-by-Side): ~2:1 aspect ratio (each eye is 1:1 or 16:9 after split)
     * - OU (Over-Under): ~1:1 aspect ratio (each eye is 2:1 or 16:9 after split)
     */
    private fun isVideoStereo(): Boolean {
        val vw = lastVideoWidth
        val vh = lastVideoHeight
        if (vw <= 0 || vh <= 0) return false
        
        val aspect = vw.toFloat() / vh.toFloat()
        // SBS: aspect ~2:1 (1.8 - 2.2) or ~32:9 (3.4 - 3.8)
        // OU: aspect ~1:1 (0.9 - 1.1) or ~8:9 (0.85 - 0.95)
        val isSbs = aspect in 1.8f..2.2f || aspect in 3.4f..3.8f
        val isOu = aspect in 0.85f..1.15f
        
        android.util.Log.d("XPlayer2", "isVideoStereo: ${vw}x${vh}, aspect=$aspect, isSbs=$isSbs, isOu=$isOu")
        return isSbs || isOu
    }
    
    private fun updateSbsUi() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels.takeIf { it > 0 } ?: 1
        val ultraWide = w.toFloat() / h.toFloat() >= 3.2f
        
        // Determine if we should duplicate mono to SBS:
        // - Only on ultrawide displays
        // - Only when SBS mode is OFF
        // - Only for non-stereo (2D) content - stereo content should stretch
        val shouldDuplicate = ultraWide && !getStereoSbs() && !isVideoStereo()
        android.util.Log.d("XPlayer2", "updateSbsUi: ultraWide=$ultraWide, getStereoSbs=${getStereoSbs()}, isVideoStereo=${isVideoStereo()}, shouldDuplicate=$shouldDuplicate")
        glView?.setDuplicateMonoToSbs(shouldDuplicate)
        // Mono-on-ultrawide can flip the pipeline (duplicate-mono needs GL); re-evaluate.
        applyVideoPipeline()
    }

    private fun applySbsButtonVisual(btn: MaterialButton) {
        val checked = getStereoSbs()
        btn.isChecked = checked
        if (!checked) {
            // When OFF -> outlined
            btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.setTextColor(Color.WHITE)
            btn.strokeColor = ColorStateList.valueOf(Color.WHITE)
            val px = (2 * resources.displayMetrics.density).toInt()
            btn.strokeWidth = px
        } else {
            // When ON -> filled
            btn.backgroundTintList = ColorStateList.valueOf("#2196F3".toColorInt())
            btn.setTextColor(Color.WHITE)
            btn.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
            btn.strokeWidth = 0
        }
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

    fun toggleStereoSbs() {
        val newValue = !getStereoSbs()
        setStereoSbs(newValue)
        glView?.setSbsEnabled(newValue)
        presentation?.setSbsEnabled(newValue)
        btnSbsRef?.let { applySbsButtonVisual(it) }
        updateSbsUi()
        applySbsShiftIfNeeded()
        saveProgress()
    }

    fun isShiftEnabled(): Boolean = sbsShiftEnabled

    fun toggleShift() {
        sbsShiftEnabled = !sbsShiftEnabled
        btnShiftRef?.isChecked = sbsShiftEnabled
        applySbsShiftIfNeeded()
        saveProgress()
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

    fun finishAndClose() {
        dismissPresentation()
        finish()
    }
}
