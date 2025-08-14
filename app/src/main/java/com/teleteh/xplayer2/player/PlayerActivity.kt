package com.teleteh.xplayer2.player

import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import androidx.media3.ui.TrackSelectionDialogBuilder
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
    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var sourceUri: Uri? = null
    private var titleCenterView: android.widget.TextView? = null
    private var currentResolvedTitle: String? = null
    private var btnSbsRef: MaterialButton? = null
    // No need for reentrancy guard when we don't call show/hide inside listener

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
        val overlay = playerView.findViewById<android.widget.FrameLayout>(androidx.media3.ui.R.id.exo_overlay)
        LayoutInflater.from(this).inflate(R.layout.player_controls_overlay, overlay, true)
        overlay.visibility = View.GONE
        // Ensure overlay is above the built-in controller
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_controller)?.let { controllerView ->
            val base = if (controllerView.elevation != 0f) controllerView.elevation else 2f
            overlay.elevation = base + 2f
        }
        overlay.bringToFront()
        val btnBack = overlay.findViewById<MaterialButton>(R.id.btnBack)
        val btnSbs = overlay.findViewById<MaterialButton>(R.id.btnSbs)
        btnSbsRef = btnSbs
        titleCenterView = overlay.findViewById(R.id.tvTitleCenter)
        btnBack.setOnClickListener { navigateBackToPrimary() }
        btnSbs.isCheckable = true
        btnSbs.isChecked = getStereoSbs()
        applySbsButtonVisual(btnSbs)
        btnSbs.setOnClickListener {
            toggleStereoMode()
            applySbsButtonVisual(btnSbs)
        }
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
                val parsed = try { if (!text.isNullOrBlank()) text.toUri() else null } catch (_: Throwable) { null }
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
        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .build().also { exo ->
                // Prefer highest quality variant (e.g., for HLS master playlists)
                selector.parameters = selector.buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
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

                // Route video frames to GL surface when ready
                glView?.setOnSurfaceReadyListener { surface ->
                    exo.setVideoSurface(surface)
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
                // Initialize SBS state per item
                val dm = resources.displayMetrics
                val ultraWide = (dm.widthPixels.toFloat() / (dm.heightPixels.takeIf { it > 0 } ?: 1).toFloat()) >= 3.2f
                val initialSbs = recent?.sbsEnabled ?: ultraWide
                setStereoSbs(initialSbs)
                glView?.setSbsEnabled(initialSbs)
                // Refresh button visuals to reflect initial per-item state
                btnSbsRef?.let { applySbsButtonVisual(it) }
                exo.play()
                // Listen for metadata/title updates to reflect in UI and Recent
                exo.addListener(object : Player.Listener {
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
        updateSbsUi()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        player?.release()
        player = null
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
        } catch (_: Throwable) { null }
        val entry = RecentEntry(
            uri = uri.toString(),
            title = title,
            lastPositionMs = position,
            durationMs = duration,
            lastPlayedAt = System.currentTimeMillis(),
            framePacking = framePacking,
            sbsEnabled = getStereoSbs()
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

    private fun updateSbsUi() {
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels.takeIf { it > 0 } ?: 1
        val ultraWide = w.toFloat() / h.toFloat() >= 3.2f
        // With SbsMirrorLayout mirroring internally, no need to toggle a right-side view
        // Leave toolbar visibility controlled by controller listener
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
