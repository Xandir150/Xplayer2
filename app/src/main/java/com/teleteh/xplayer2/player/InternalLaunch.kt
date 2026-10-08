package com.teleteh.xplayer2.player

import android.content.Intent
import java.security.SecureRandom

/**
 * Marks an intent as built by this app, for extras an outside caller must not be able to set.
 *
 * `PlayerActivity` is exported (it is the VIEW/SEND target for other apps and web pages), so any
 * extra it reads can be supplied by anyone. The PC Link host is one such extra: honoured from
 * outside, a web page could make the phone open a session to a host of its choosing and show that
 * host's video and sound on the glasses with no pairing.
 *
 * The token is random per process and never leaves it, so an outside caller cannot guess it. A
 * stale intent (a PendingIntent that survived a process restart) fails the check, which is the
 * safe direction: it is treated as external.
 */
object InternalLaunch {
    private const val EXTRA_TOKEN = "com.teleteh.xplayer2.extra.INTERNAL_LAUNCH_TOKEN"

    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    fun mark(intent: Intent): Intent = intent.putExtra(EXTRA_TOKEN, token)

    fun isInternal(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_TOKEN) == token
}
