package com.teleteh.xplayer2.player

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.teleteh.xplayer2.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class FoldPlayerLayoutTest {
    @get:Rule val layoutInfo = WindowLayoutInfoPublisherRule()

    @Test fun tabletopBookAndUnfoldKeepVideoAndControlsOutsideHinge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val video = File(context.cacheDir, "fold-regression.mp4")
        instrumentation.context.assets.open("player-regression.mp4").use { input ->
            video.outputStream().use { input.copyTo(it) }
        }
        val intent = Intent(context, PlayerActivity::class.java).apply {
            setDataAndType(Uri.fromFile(video), "video/mp4")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            waitFor(scenario) { it.findViewById<View>(R.id.foldPlayerRoot).height > 0 }
            waitFor(scenario) { it.getDuration() > 0 }
            for (horizontal in listOf(true, false)) {
                var hinge = Rect()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<View>(R.id.foldPlayerRoot)
                    val origin = IntArray(2)
                    root.getLocationInWindow(origin)
                    hinge = if (horizontal) Rect(origin[0], origin[1] + root.height / 2,
                        origin[0] + root.width, origin[1] + root.height / 2 + 12)
                    else Rect(origin[0] + root.width / 2, origin[1],
                        origin[0] + root.width / 2 + 12, origin[1] + root.height)
                }
                val feature = object : FoldingFeature {
                    override val bounds = hinge
                    override val orientation = if (horizontal) FoldingFeature.Orientation.HORIZONTAL else FoldingFeature.Orientation.VERTICAL
                    override val state = FoldingFeature.State.HALF_OPENED
                    override val isSeparating = true
                    override val occlusionType = FoldingFeature.OcclusionType.FULL
                }
                layoutInfo.overrideWindowLayoutInfo(WindowLayoutInfo(listOf(feature)))
                waitFor(scenario) { activity ->
                    val controller = activity.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
                    val xy = IntArray(2)
                    controller.getLocationInWindow(xy)
                    if (horizontal) xy[1] == hinge.bottom else xy[0] == hinge.right
                }
                scenario.onActivity { activity ->
                    val content = activity.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame)
                    val pane = content.parent as View
                    val xy = IntArray(2)
                    pane.getLocationInWindow(xy)
                    assertEquals(if (horizontal) hinge.top else hinge.left,
                        if (horizontal) xy[1] + pane.height else xy[0] + pane.width)
                    content.getLocationInWindow(xy)
                    assertTrue("Video must stay before the hinge",
                        if (horizontal) xy[1] + content.height <= hinge.top else xy[0] + content.width <= hinge.left)
                    assertEquals(0, activity.findViewById<PlayerView>(R.id.playerView).controllerShowTimeoutMs)
                }
                capture(if (horizontal) "tabletop" else "book")
            }
            layoutInfo.overrideWindowLayoutInfo(WindowLayoutInfo(emptyList()))
            waitFor(scenario) { activity ->
                (activity.findViewById<View>(androidx.media3.ui.R.id.exo_content_frame).parent as View).height ==
                    activity.findViewById<View>(R.id.foldPlayerRoot).height
            }
            scenario.onActivity {
                assertEquals(3000, it.findViewById<PlayerView>(R.id.playerView).controllerShowTimeoutMs)
            }
            capture("flat")
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(instrumentation.targetContext.cacheDir, "fold-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitFor(scenario: ActivityScenario<PlayerActivity>, condition: (PlayerActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        var success = false
        while (!success && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { success = condition(it) }
            if (!success) SystemClock.sleep(30)
        }
        assertTrue("Layout did not reach the expected fold state", success)
    }
}
