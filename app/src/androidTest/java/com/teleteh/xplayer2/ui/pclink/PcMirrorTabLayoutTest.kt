package com.teleteh.xplayer2.ui.pclink

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.teleteh.xplayer2.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That the PC-Mirror tab's layout inflates, carries every view the fragment binds, and starts in
 * the state it is supposed to.
 *
 * Same reasoning as the remote's layout test — `PcMirrorFragment` binds `lateinit` fields, so a
 * renamed id is a crash rather than a compile error. The starting visibilities are worth pinning
 * too: the fragment shows the session card only once it has read a live session, so a card left
 * visible in the XML would flash "streaming" at a user who has never paired with anything.
 */
@RunWith(AndroidJUnit4::class)
class PcMirrorTabLayoutTest {

    private val root: View by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val themed = android.view.ContextThemeWrapper(context, R.style.Theme_XPlayer2)
        LayoutInflater.from(themed).inflate(R.layout.fragment_pc_mirror, null)
    }

    @Test
    fun everyViewTheTabBindsIsThere() {
        for (id in listOf(
            R.id.cardSession,
            R.id.boxIdle,
            R.id.tvServerName,
            R.id.tvLinkState,
            R.id.tvPairedLabel,
            R.id.tvEmpty,
            R.id.rvPairedPcs,
            R.id.btnFindPc,
            R.id.btnOpenRemote,
            R.id.btnDisconnect
        )) {
            assertNotNull(
                "fragment_pc_mirror is missing a view PcMirrorFragment binds",
                root.findViewById<View>(id)
            )
        }
    }

    @Test
    fun itOpensOnTheWayInNotOnAClaimThatSomethingIsStreaming() {
        assertEquals(View.GONE, root.findViewById<View>(R.id.cardSession).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.boxIdle).visibility)
        // "No computers yet" is the answer to a question the store has not been asked yet:
        // showPairings() turns it on once the read comes back, so nobody with paired PCs sees it
        // flash over their list.
        assertEquals(View.GONE, root.findViewById<View>(R.id.tvEmpty).visibility)
    }
}
