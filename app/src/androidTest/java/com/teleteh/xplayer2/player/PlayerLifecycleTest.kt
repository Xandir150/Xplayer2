package com.teleteh.xplayer2.player

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.teleteh.xplayer2.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerLifecycleTest {
    private fun launch(): ActivityScenario<PlayerActivity> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "player-regression.mp4")
        instrumentation.context.assets.open("player-regression.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return ActivityScenario.launch<PlayerActivity>(Intent(context, PlayerActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            setDataAndType(Uri.fromFile(file), "video/mp4")
            putExtra(PlayerActivity.EXTRA_TITLE, "Lifecycle regression")
            putExtra(PlayerActivity.EXTRA_START_POSITION_MS, 0L)
        }).also { awaitReady(it) }
    }

    private fun awaitReady(scenario: ActivityScenario<PlayerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = it.findViewById<PlayerView>(R.id.playerView).player?.playbackState == Player.STATE_READY }
            if (!ready) SystemClock.sleep(100)
        }
        assertTrue("Fixture must reach STATE_READY", ready)
    }

    private fun pauseAndSeek(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity {
            it.findViewById<PlayerView>(R.id.playerView).player!!.pause()
            it.seekTo(8_000)
        }
        awaitReady(scenario)
    }

    private fun assertPausedAtSeek(scenario: ActivityScenario<PlayerActivity>) {
        scenario.onActivity {
            assertFalse(it.findViewById<PlayerView>(R.id.playerView).player!!.playWhenReady)
            assertEquals(8_000L, it.getCurrentPosition())
        }
    }

    @Test fun pausedPlaybackSurvivesStopAndStart() {
        launch().use { scenario ->
            pauseAndSeek(scenario)
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitReady(scenario)
            assertPausedAtSeek(scenario)
        }
    }

    @Test fun pausedPlaybackSurvivesRecreation() {
        launch().use { scenario ->
            pauseAndSeek(scenario)
            scenario.recreate()
            awaitReady(scenario)
            assertPausedAtSeek(scenario)
        }
    }

    @Test fun notificationReopensSamePlayerWithoutChangingPauseOrSource() {
        launch().use { scenario ->
            pauseAndSeek(scenario)
            var original: Player? = null
            scenario.onActivity {
                original = it.findViewById<PlayerView>(R.id.playerView).player
                it.startActivity(Intent(it, PlayerActivity::class.java).apply {
                    action = PlaybackService.ACTION_REOPEN
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertSame(original, it.findViewById<PlayerView>(R.id.playerView).player)
                assertEquals("Lifecycle regression", it.getCurrentTitle())
            }
            assertPausedAtSeek(scenario)
        }
    }
}
