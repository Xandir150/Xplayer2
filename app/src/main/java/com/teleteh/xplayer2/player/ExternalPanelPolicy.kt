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
     * stand-in while DisplayPort negotiates. It is a deadline and not just a timer, so the checks
     * that display events schedule in the meantime may find a panel but must not end the session
     * early — this is the one that judges, and only once.
     *
     * What it asks at the end is [shouldEndSession], not "is there a panel": on a device whose only
     * screen is the glasses there never will be one, and the cast is fine — see
     * [glassesAreTheOnlyScreen].
     */
    const val ENTRY_GRACE_MS = 8_000L

    /**
     * Whether the glasses are this device's *own* screen rather than a second one.
     *
     * The rule above demands an external panel because a phone has one screen of its own and the
     * glasses are the other. That is not every device: on a TV box, a pocket PC in desktop mode —
     * anything with no display but the one plugged into it — the glasses ARE display 0. The app's
     * own window is what the user sees in the headset, there is no second panel to wait for, and
     * demanding one killed a perfectly good cast a few seconds in.
     *
     * So a pair known over USB HID ([com.teleteh.xplayer2.data.glasses.GlassesController.anyAttached],
     * the same signal that let the user in at the door — see `GlassesPresence`) is taken as proof
     * the picture is arriving somewhere, but **only while no panel has ever come up in this
     * session**. That second half is what keeps the fix from undoing the first one: unplugging the
     * DisplayPort side, or taking the goggles off so the proximity sensor powers the panel down,
     * leaves USB HID attached and perfectly happy. A cast that HAS had a panel and lost it is a
     * cast nobody can see, whatever the USB bus still says.
     *
     * The cost of being generous here is a device with HID glasses and no alt-mode at all: its
     * cast now runs on with the desktop flattened into the phone's own window instead of ending
     * with an explanation. That is a visible, escapable annoyance for a configuration that cannot
     * work anyway; the fault it replaces was ending a session that was working.
     *
     * @param glassesAttached a known pair answers on USB HID right now.
     * @param panelEverAlive an external panel has been up at some point during this session.
     */
    fun glassesAreTheOnlyScreen(glassesAttached: Boolean, panelEverAlive: Boolean): Boolean =
        glassesAttached && !panelEverAlive

    /**
     * @param panelAlive an external display is attached and powered.
     * @param hasPresentation we are showing through a `Presentation` on it.
     * @param isPcLink this is a PC Link session rather than a file being played.
     * @param glassesAreOwnScreen the picture reaches the glasses through this device's own screen,
     *   so there is no external panel to demand — [glassesAreTheOnlyScreen].
     */
    fun shouldEndSession(
        panelAlive: Boolean,
        hasPresentation: Boolean,
        isPcLink: Boolean,
        glassesAreOwnScreen: Boolean,
    ): Boolean = !panelAlive && (hasPresentation || (isPcLink && !glassesAreOwnScreen))
}
