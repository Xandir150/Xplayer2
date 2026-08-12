package com.teleteh.xplayer2.ui

/**
 * The pages of the main screen, by the number [MainPagerAdapter] hands them out under.
 *
 * Worth a name of its own because more than one place has to agree on it — the tab titles, the
 * glasses menu's heading, and anything that sends the user to a particular tab — and because the
 * numbers are otherwise scattered literals that a reordering would silently invalidate. The test
 * beside this reads the adapter and checks they still line up.
 */
object MainPages {
    const val RECENT = 0
    const val SOURCES = 1
    const val PC_MIRROR = 2
}
