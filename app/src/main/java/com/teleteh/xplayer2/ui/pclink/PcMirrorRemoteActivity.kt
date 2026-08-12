package com.teleteh.xplayer2.ui.pclink

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.teleteh.xplayer2.R

/**
 * PC Link's remote, on the phone, while the desktop is on the glasses.
 *
 * The same bargain the film player makes: the picture goes to the glasses and the phone becomes
 * the thing you hold, so the player's window — which has nothing to show for a stream it does not
 * decode into its own surface — is not what the user is left staring at. `PlayerActivity` brings
 * this to the front exactly where it brings `RemoteControlActivity` up for a film, giving the same
 * stack shape either way: Main < Player < remote.
 *
 * It is deliberately **not** `RemoteControlActivity`. That one drives an ExoPlayer — transport,
 * scrubbing, track menus — none of which exists here; a desktop is not seekable and has no
 * duration. Sharing it would have meant a second mode inside a screen whose every control assumes
 * a timeline.
 *
 * The contents are [PcMirrorFragment], the same one the PC-Mirror tab shows, so there is one
 * implementation of the remote and it cannot drift between the two places it appears. The
 * fragment already reads the live session through [com.teleteh.xplayer2.player.PcLinkSession], so
 * it needs nothing passed in and works identically whichever host it finds itself in.
 */
class PcMirrorRemoteActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pc_mirror_remote)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.tab_pc_mirror)
        // Only on a cold create: a rotation must not throw away the fragment's state, and
        // `savedInstanceState != null` means the manager has already restored it.
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.pcMirrorRemoteContainer, PcMirrorFragment())
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
