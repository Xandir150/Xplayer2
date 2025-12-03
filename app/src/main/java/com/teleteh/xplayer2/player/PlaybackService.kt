package com.teleteh.xplayer2.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.teleteh.xplayer2.R

/**
 * Foreground service for media playback on external display.
 * Uses MediaSession for proper system integration and energy efficiency.
 */
@UnstableApi
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "xplayer2_playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.teleteh.xplayer2.STOP_PLAYBACK"
    }

    private var mediaSession: MediaSession? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopPlayback()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.playback_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    fun startForegroundPlayback(player: Player, title: String?) {
        // Release existing session before creating new one
        mediaSession?.release()
        
        // Create MediaSession for system integration with unique ID
        mediaSession = MediaSession.Builder(this, player)
            .setId("XPlayer2_${System.currentTimeMillis()}")
            .build()

        val notification = buildNotification(title)
        
        // Start foreground with proper type for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification(title: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(title))
    }

    private fun buildNotification(title: String?): Notification {
        // Intent to open PlayerActivity
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            setClass(this@PlaybackService, PlayerActivity::class.java)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop playback
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(getString(R.string.playing_on_external_display))
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.stop),
                stopPendingIntent
            )

        // Add MediaStyle if session available
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0)
            )
        }

        return builder.build()
    }

    private fun stopPlayback() {
        // Notify PlayerActivity to stop
        PlayerActivity.currentInstance?.finish()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopForegroundPlayback() {
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
