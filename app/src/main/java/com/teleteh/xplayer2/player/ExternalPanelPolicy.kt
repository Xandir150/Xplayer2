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
     * How long a hot-plug burst is given to settle before the panel is judged. Taking the goggles
     * off (or switching them 2D↔3D) fires a storm of add/remove/change events, often with brand-new
     * display ids, so the answer is only meaningful once they stop.
     */
    const val RECONCILE_DEBOUNCE_MS = 1_200L

    /**
     * How long a *starting* cast is given for its panel to come up before the same judgement.
     *
     * The door into PC Link proves a pair of glasses is plugged in, not that its screen is up: HID
     * enumerates seconds before DisplayPort, and on a phone or hub with no alt-mode it never comes
     * up at all. Without a deadline that case has no transition to react to, so nothing ever asks
     * the question and the cast runs on with the desktop flattened into the phone's own window, the
     * PC's speakers held silent, and no remote.
     *
     * Deliberately far longer than [RECONCILE_DEBOUNCE_MS]: tapping a PC with the glasses still on
     * the desk is an ordinary way in, and ending that session a second later would be a worse fault
     * than the one this exists to catch. A few seconds of the desktop on the phone is a harmless
     * stand-in while DisplayPort negotiates.
     */
    const val ENTRY_GRACE_MS = 8_000L

    /**
     * @param panelAlive an external display is attached and powered.
     * @param hasPresentation we are showing through a `Presentation` on it.
     * @param isPcLink this is a PC Link session rather than a file being played.
     */
    fun shouldEndSession(panelAlive: Boolean, hasPresentation: Boolean, isPcLink: Boolean): Boolean =
        !panelAlive && (hasPresentation || isPcLink)
}
