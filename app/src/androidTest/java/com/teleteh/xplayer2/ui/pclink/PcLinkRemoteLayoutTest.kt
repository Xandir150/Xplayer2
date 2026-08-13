package com.teleteh.xplayer2.ui.pclink

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.teleteh.xplayer2.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the PC Link remote's layout inflates, and carries every view the activity binds by hand.
 *
 * `PcLinkRemoteActivity` reads its views into `lateinit` fields in `onCreate`, so a renamed or
 * dropped id is not a compile error — it is a crash on the screen the user opens while wearing the
 * glasses, which is the worst possible place to find out. This is the cheapest thing that catches
 * it, and it needs no session, no glasses and no PC: just an inflater.
 *
 * The stat chips are `<include>`s, so their inner ids are looked up through the included root — the
 * same way the activity's `Chip` does.
 */
@RunWith(AndroidJUnit4::class)
class PcLinkRemoteLayoutTest {

    private val root: View by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The activity's own theme: the layout uses ?attr/selectableItemBackground and Material
        // styles, which a bare application context cannot resolve.
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_XPlayer2)
        LayoutInflater.from(themed).inflate(R.layout.activity_pc_link_remote, null)
    }

    @Test
    fun everyViewTheRemoteBindsIsThere() {
        for (id in listOf(
            R.id.tvTitle,
            R.id.tvLinkState,
            R.id.tvSurfaceFeedback,
            R.id.tvSurfaceHint,
            R.id.tvAudioHint,
            R.id.btnAudioRoute,
            R.id.btnRecenter,
            R.id.btnStop,
            R.id.ivDetailsChevron,
            R.id.boxDetails,
            R.id.rowDetails,
            R.id.surface,
            R.id.chipFps,
            R.id.chipBitrate,
            // Driving the PC's mouse and keyboard (protocol.md 2.19). The whole block starts GONE
            // and only appears once the PC has answered about input, so nothing on screen would
            // ever reveal a missing id here until someone with input enabled opened this screen.
            R.id.boxInput,
            R.id.btnInputControl,
            R.id.swInputControl,
            R.id.btnInputAbsolute,
            R.id.swInputAbsolute,
            R.id.btnInputKeyboard,
            R.id.tvInputHint
        )) {
            assertNotNull(
                "activity_pc_link_remote is missing a view PcLinkRemoteActivity binds",
                root.findViewById<View>(id)
            )
        }
    }

    @Test
    fun bothStatChipsCarryTheirOwnValueAndSparkline() {
        for (chipId in listOf(R.id.chipFps, R.id.chipBitrate)) {
            val chip = root.findViewById<View>(chipId)
            assertNotNull("chip $chipId has no title", chip.findViewById<View>(R.id.tvChipTitle))
            assertNotNull("chip $chipId has no value", chip.findViewById<View>(R.id.tvChipValue))
            assertNotNull("chip $chipId has no sparkline", chip.findViewById<View>(R.id.sparkline))
        }
    }
}
