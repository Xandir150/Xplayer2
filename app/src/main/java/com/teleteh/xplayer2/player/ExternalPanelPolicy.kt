package com.teleteh.xplayer2.player

/**
 * When losing the external panel has to end what is running on it.
 *
 * One line of policy, pulled out of `PlayerActivity.reconcileExternalDisplay` because it has been
 * wrong twice in the same way: written for a film, it kept asking a question only a film could
 * answer.
 *
 * A film that reaches the glasses always goes through a `Presentation`, so "is a presentation up"
 * was a perfectly good stand-in for "is the picture on the glasses" — and the check read
 * `else if (presentation != null)`. PC Link breaks that equivalence. With the glasses in 2D they
 * are an ordinary 1920x1080 external display and the phone's own window is mirrored into them, so
 * there is no presentation at all; the panel could then go away with nothing noticing, leaving a
 * session streaming a desktop to a pair of glasses that are off the user's face — still costing
 * the PC its bitrate, and still holding its speakers silent.
 *
 * So a PC Link session ends on panel loss whether or not it was showing through a presentation.
 * There is nothing to fall back to: unlike a film, which can keep playing on the phone, the whole
 * of PC Link is "the computer's screen, on the glasses".
 */
object ExternalPanelPolicy {

    /**
     * @param panelAlive an external display is attached and powered.
     * @param hasPresentation we are showing through a `Presentation` on it.
     * @param isPcLink this is a PC Link session rather than a file being played.
     */
    fun shouldEndSession(panelAlive: Boolean, hasPresentation: Boolean, isPcLink: Boolean): Boolean =
        !panelAlive && (hasPresentation || isPcLink)
}
