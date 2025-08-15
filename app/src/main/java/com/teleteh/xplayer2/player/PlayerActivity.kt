    package com.teleteh.xplayer2.player

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.Surface
import android.widget.Toast
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
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
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_POSITION_MS = "start_position_ms"
        const val EXTRA_TITLE = "title"
    }

    private fun navigateBackToPrimary() {
        // Bring MainActivity to foreground on primary display then finish this player
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        DisplayUtils.startOnPrimaryDisplay(this, intent)
        finish()
    }

    private lateinit var playerView: PlayerView
    private var glView: OuToSbsGlView? = null
    private var glSurface: Surface? = null
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var presentation: ExternalPlayerPresentation? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var routeCallback: MediaRouter.SimpleCallback? = null
    private var sourceUri: Uri? = null
    private var titleCenterView: android.widget.TextView? = null
    private var currentResolvedTitle: String? = null
    private var btnSbsRef: MaterialButton? = null
    private var btnShiftRef: MaterialButton? = null
    private var audioMenuRoot: android.widget.FrameLayout? = null
    private var audioMenuCenter: LinearLayout? = null
    private var audioMenuLeft: LinearLayout? = null
    private var audioMenuRight: LinearLayout? = null
    // Debug flag: whether vertical SBS shift is enabled (not persisted)
    private var sbsShiftEnabled: Boolean = false
    // No need for reentrancy guard when we don't call show/hide inside listener
    private var lastVideoWidth: Int = 0
    private var lastVideoHeight: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // Ensure PlayerView's internal Surface/Texture view doesn't render over GL
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
        val btnAudio = overlay.findViewById<ImageButton>(R.id.btnAudio)
        btnSbsRef = btnSbs
        btnShiftRef = btnShift
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
            toggleStereoMode()
            applySbsButtonVisual(btnSbs)
        }
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

        // Resolve source from ACTION_VIEW or ACTION_SEND
        val action = intent?.action
        sourceUri = when (action) {
            Intent.ACTION_SEND -> {
                // Try URL in EXTRA_TEXT first
                val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
                val parsed = try {
                    if (!text.isNullOrBlank()) text.toUri() else null
                } catch (_: Throwable) {
                    null
                }
                val stream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                parsed ?: stream
            }

            else -> intent?.data
        }
        if (sourceUri == null) {
            finish()
            return
        }

        initializePlayer()
        // Set initial title in UI if possible
        updateCenterTitle()
        // Try to extract container title (e.g., MKV Title) early
        tryProbeTitleFromRetriever()
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

    private fun initializePlayer() {
        val uri = sourceUri ?: return
        if (player != null) return
        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .build().also { exo ->
                // Prefer highest quality variant (e.g., for HLS master playlists)
                val ffmpegAvailableForPrefs = try { FfmpegLibrary.isAvailable() } catch (_: Throwable) { false }
                selector.parameters = selector.buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
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

                // Route video frames: On API <= 30 prefer Presentation only when truly available
                // - On Android 11 (API 30): prefer Presentation ONLY if MediaRouter provides a route display
                // - On Android 10 and below: allow either MediaRouter route OR external display scan
                val preferPresentation = when {
                    Build.VERSION.SDK_INT == Build.VERSION_CODES.R ->
                        DisplayUtils.getRoutePresentationDisplay(this) != null
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                        (DisplayUtils.getRoutePresentationDisplay(this) != null
                                || DisplayUtils.findUltraWideExternalDisplay(this) != null)
                    else -> false
                }
                if (preferPresentation) {
                    tryShowExternalPresentation()
                }
                // Also wire GL surface as a fallback (used when no Presentation or no external display)
                glView?.setOnSurfaceReadyListener { surface ->
                    glSurface = surface
                    // If preferring Presentation and external exists, don't attach main GL surface yet
                    if (!preferPresentation && presentation == null) {
                        exo.setVideoSurface(surface)
                    }
                }
                exo.prepare()

                // Resume position if provided or stored
                val requestedStart = intent?.getLongExtra(EXTRA_START_POSITION_MS, -1L) ?: -1L
                val store = RecentStore(this)
                val recent = store.find(uri.toString())
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
                // Initialize SBS state per item
                val dm = resources.displayMetrics
                val ultraWide = (dm.widthPixels.toFloat() / (dm.heightPixels.takeIf { it > 0 }
                    ?: 1).toFloat()) >= 3.2f
                val initialSbs = recent?.sbsEnabled ?: ultraWide
                setStereoSbs(initialSbs)
                glView?.setSbsEnabled(initialSbs)
                // Refresh button visuals to reflect initial per-item state
                btnSbsRef?.let { applySbsButtonVisual(it) }
                exo.play()
                // Listen for metadata/title updates to reflect in UI and Recent
                exo.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        lastVideoWidth = videoSize.width
                        lastVideoHeight = videoSize.height
                        applySbsShiftIfNeeded()
                    }
                    override fun onTracksChanged(tracks: Tracks) {
                        // Log selected audio track info for debugging multi-track issues
                        val groups = tracks.groups
                        for (i in 0 until groups.size) {
                            val g = groups[i]
                            if (g.type == C.TRACK_TYPE_AUDIO) {
                                for (j in 0 until g.length) {
                                    val info = g.getTrackFormat(j)
                                    val selected = g.isTrackSelected(j)
                                    if (selected) {
                                        android.util.Log.i(
                                            "XPlayer2",
                                            "Selected audio: mime=${info.sampleMimeType} codecs=${info.codecs} ch=${info.channelCount} hz=${info.sampleRate} lang=${info.language}"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
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
                        // Extract ID3 title (e.g., HLS ID3 tags)
                        var foundTitle: String? = null
                        for (i in 0 until metadata.length()) {
                            val entry = metadata[i]
                            if (entry is TextInformationFrame) {
                                if (entry.id.equals("TIT2", ignoreCase = true)) {
                                    val t = entry.values[0].trim()
                                    if (t.isNotBlank()) {
                                        foundTitle = t
                                        break
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
        if (player == null && sourceUri != null) initializePlayer()
        // Listen for display changes to (re)show Presentation on API <= 30 (Android 10 & 11)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val dm = getSystemService(DisplayManager::class.java)
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    tryShowExternalPresentation()
                }
                override fun onDisplayRemoved(displayId: Int) {
                    // Dismiss if the presentation's display is gone
                    val d = presentation?.display
                    if (d != null && d.displayId == displayId) dismissPresentation()
                }
                override fun onDisplayChanged(displayId: Int) {}
            }
            dm?.registerDisplayListener(listener, null)
            displayListener = listener
            // MediaRouter route changes (like HDMI docks / wireless displays)
            routeCallback = DisplayUtils.registerRouteCallback(this) {
                // On any route change, try to show/dismiss Presentation accordingly
                val routeDisplay = DisplayUtils.getRoutePresentationDisplay(this)
                if (routeDisplay != null) {
                    tryShowExternalPresentation()
                } else {
                    // If no route presentation and current presentation targets a non-route display, leave it
                    // Otherwise dismiss to return to primary
                    if (presentation != null) dismissPresentation()
                }
            }
            // Attempt once on start, in case display was already connected
            tryShowExternalPresentation()
        }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        glView?.onPause()
        player?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        player?.clearVideoSurface()
        player?.release()
        player = null
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val dm = getSystemService(DisplayManager::class.java)
            displayListener?.let { dm?.unregisterDisplayListener(it) }
            displayListener = null
            // Unregister MediaRouter callback
            DisplayUtils.unregisterRouteCallback(this, routeCallback)
            routeCallback = null
            dismissPresentation()
        }
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
                ).build().show()
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
        // Re-attempt showing Presentation on API <= 30 in case display connected while paused
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            tryShowExternalPresentation()
        }
        updateSbsUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        player?.release()
        player = null
    }

    // --- Custom Audio Menu (SBS-aware) ---
    private data class AudioMenuItem(
        val label: String,
        val isAuto: Boolean,
        val group: Tracks.Group?,
        val trackIndexInGroup: Int?
    )

    private fun buildAudioMenuItems(): List<AudioMenuItem> {
        val items = mutableListOf<AudioMenuItem>()
        // Auto item first
        items += AudioMenuItem(label = "Auto", isAuto = true, group = null, trackIndexInGroup = null)
        val tracks = player?.currentTracks ?: return items
        val nameProvider = DefaultTrackNameProvider(resources)
        for (i in 0 until tracks.groups.size) {
            val g = tracks.groups[i]
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (j in 0 until g.length) {
                // List only supported tracks
                if (!g.isTrackSupported(j)) continue
                val f = g.getTrackFormat(j)
                val pretty = nameProvider.getTrackName(f)
                val label = pretty
                items += AudioMenuItem(label = label, isAuto = false, group = g, trackIndexInGroup = j)
            }
        }
        return items
    }

    private fun showAudioMenu() {
        val root = audioMenuRoot ?: return
        val center = audioMenuCenter ?: return
        val left = audioMenuLeft ?: return
        val right = audioMenuRight ?: return
        // Clear previous content
        center.removeAllViews()
        left.removeAllViews()
        right.removeAllViews()
        val items = buildAudioMenuItems()
        val sbs = getStereoSbs()
        // Build views
        fun addItemsTo(container: LinearLayout) {
            container.removeAllViews()
            fun dp(value: Int): Int =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value.toFloat(),
                    resources.displayMetrics
                ).toInt()
            val title = TextView(this).apply {
                text = getString(R.string.select_audio_track)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            // Build scrollable content
            val scroll = ScrollView(this).apply {
                // Cap height to ~60% of the root overlay height
                val h = (audioMenuRoot?.height ?: 0)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    if (h > 0) (h * 0.6f).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val items = buildAudioMenuItems()
            inner.addView(title)
            // Determine current selection for highlighting
            items.forEach { item ->
                val isSelected = when {
                    item.isAuto -> false
                    item.group != null && item.trackIndexInGroup != null -> {
                        try {
                            item.group.isTrackSelected(item.trackIndexInGroup)
                        } catch (e: Exception) { false }
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
                        applyAudioSelection(item)
                        hideAudioMenu()
                    }
                }
                inner.addView(tv)
            }
            scroll.addView(inner)
            container.addView(scroll)
        }
        if (sbs) {
            // Show RIGHT panel to match button position; mirrored rendering shows in both eyes
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

    private fun hideAudioMenu() {
        audioMenuRoot?.visibility = View.GONE
    }

    private fun applyAudioSelection(item: AudioMenuItem) {
        val selector = trackSelector ?: return
        val exo = player ?: return
        val builder = selector.buildUponParameters()
        // Always ensure audio is enabled
        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        // Clear previous audio overrides
        builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (!item.isAuto) {
            val group = item.group ?: return
            val index = item.trackIndexInGroup ?: return
            val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(index))
            builder.addOverride(override)
        }
        selector.parameters = builder.build()
        // Nudge to apply immediately
        exo.playWhenReady = exo.playWhenReady
        Toast.makeText(this, if (item.isAuto) "Audio: Auto" else item.label, Toast.LENGTH_SHORT).show()
    }

    private fun tryShowExternalPresentation() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) return
        // Resolve target display depending on API level
        val ext = when {
            Build.VERSION.SDK_INT == Build.VERSION_CODES.R ->
                DisplayUtils.getRoutePresentationDisplay(this)
            else ->
                DisplayUtils.getRoutePresentationDisplay(this)
                    ?: DisplayUtils.findUltraWideExternalDisplay(this)
        } ?: run {
            dismissPresentation()
            return
        }
        if (presentation?.display?.displayId == ext.displayId) return
        dismissPresentation()
        // Create presentation and route player surface there
        val pres = ExternalPlayerPresentation(this, ext) { surface ->
            player?.let { exo ->
                if (surface != null) {
                    exo.setVideoSurface(surface)
                } else {
                    exo.clearVideoSurface()
                }
            }
        }
        presentation = pres
        try {
            pres.show()
            // While waiting for Presentation GL surface, clear the main GL surface so frames don't render on primary
            player?.clearVideoSurface()
            // Mirror current SBS state to external GL, but auto-enable if external is ultrawide
            val current = getStereoSbs()
            val d = pres.display
            var ultra = false
            try {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                d?.getMetrics(dm)
                val ratio = (dm.widthPixels.toFloat() / (dm.heightPixels.takeIf { it > 0 } ?: 1))
                ultra = ratio >= 3.2f
            } catch (_: Throwable) { }
            pres.setSbsEnabled(current || ultra)
        } catch (_: Throwable) {
            presentation = null
        }
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
        // Restore rendering back to GL surface if available
        val surface = glSurface
        if (surface != null) {
            player?.setVideoSurface(surface)
        } else {
            player?.clearVideoSurface()
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
            sbsShiftEnabled = sbsShiftEnabled
        )
        RecentStore(this).upsert(entry)
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

    private fun updateSbsUi() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels.takeIf { it > 0 } ?: 1
        val ultraWide = w.toFloat() / h.toFloat() >= 3.2f
        // With SbsMirrorLayout mirroring internally, no need to toggle a right-side view
        // Leave toolbar visibility controlled by controller listener
        // Duplicate mono video into left/right halves on ultrawide stereo displays when SBS is OFF
        glView?.setDuplicateMonoToSbs(ultraWide && !getStereoSbs())
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
}
