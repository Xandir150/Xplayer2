package com.teleteh.xplayer2.data.network

/**
 * The media keys (`protocol.md` §2.19.7): play/pause, the tracks, the volume, from the phone.
 *
 * Not a second remote, and no guess about whether a film or a game is on the glasses: these are
 * ordinary keys on the USB HID **Consumer** page (`0x0C`) — the ones on a keyboard's top row and on
 * every Bluetooth headset — and the PC's operating system already routes them to whichever
 * application is playing media. A game in the foreground simply ignores them. So a "media remote"
 * is one row of buttons sending `"c"` events down the input path that already exists, with every
 * rule of §2.19 unchanged: the encryption gate, the operator's switch, the batch cap, the letting go.
 *
 * Exactly six are injectable. Stop is deliberately absent — macOS has no system key for it, and a
 * set that works on one platform is not a set. A `"c"` outside the six is dropped by the server like
 * an unknown `"k"` (§2.19.6), so this client never sends one; `test-vectors/media_vectors.json`
 * pins the set, and [PcLinkMediaVectorsTest] holds this object to it.
 */
object PcLinkMediaKeys {

    const val PLAY_PAUSE = 0xCD
    const val NEXT = 0xB5
    const val PREVIOUS = 0xB6
    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val MUTE = 0xE2

    /** Every media usage the server will inject — this client sends nothing else. */
    val INJECTABLE: Set<Int> = setOf(PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, MUTE)

    fun isInjectable(usage: Int): Boolean = INJECTABLE.contains(usage)
}
