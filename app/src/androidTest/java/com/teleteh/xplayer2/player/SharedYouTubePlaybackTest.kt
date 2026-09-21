package com.teleteh.xplayer2.player

import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.teleteh.xplayer2.R
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class SharedYouTubePlaybackTest {
    @Test fun appIsAvailableForSharedLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (mime in listOf("text/plain", "text/uri-list")) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                setPackage(context.packageName)
                putExtra(Intent.EXTRA_TEXT, "https://youtu.be/aqz-KE-bpKQ")
            }
            val matches = context.packageManager.queryIntentActivities(intent, 0)
            assertTrue("Share target missing for $mime", matches.any {
                it.activityInfo.name == PlayerActivity::class.java.name && it.activityInfo.exported
            })
        }
    }

    // Explicit opt-in: depends on YouTube availability and downloads real video.
    @Test fun sharedYouTubeLinkPlaysWithTitleAndAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("networkSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(context.packageName)
            putExtra(Intent.EXTRA_TEXT, "Big Buck Bunny — watch this: https://youtu.be/aqz-KE-bpKQ")
            putExtra(PlayerActivity.EXTRA_START_POSITION_MS, 0L)
        }
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            val deadline = SystemClock.elapsedRealtime() + 90_000
            var advanced = false
            var failure: String? = null
            while (!advanced && failure == null && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { activity ->
                    val player = activity.findViewById<PlayerView>(R.id.playerView).player
                    failure = player?.playerError?.toString()
                    if (player != null && player.playbackState == Player.STATE_READY && player.currentPosition > 30_000) {
                        assertTrue(activity.getCurrentTitle().contains("Big Buck Bunny"))
                        assertTrue("Audio track must be selected", player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
                        assertTrue("Video track must be selected", player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                        advanced = true
                    }
                }
                if (!advanced && failure == null) SystemClock.sleep(250)
            }
            assertNull("Playback error", failure)
            assertTrue("Shared YouTube video must play beyond 30 seconds", advanced)
        }
    }
}
