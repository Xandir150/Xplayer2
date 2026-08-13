package com.teleteh.xplayer2.data.network

import android.view.KeyEvent

/**
 * An Android [KeyEvent] → USB HID usage, `protocol.md` §2.19.4 — the physical *position* of a key,
 * not the letter it happens to type here.
 *
 * ## Why a position, and why this is not [KeyEvent.getKeyCode]
 *
 * The protocol carries a key as its HID usage on the Keyboard/Keypad page (`0x07`) and lets the
 * **PC's** layout decide what that position types. That is what a remote control needs: Ctrl+C,
 * Alt+Tab, the arrows and the function keys are position chords, and they have to land where the PC
 * expects no matter what the phone's keyboard is set to.
 *
 * [KeyEvent.getKeyCode] cannot supply that. It is a *label*, already resolved through the phone's
 * own layout: press the key marked `;` on a French AZERTY keyboard and Android says `KEYCODE_M`,
 * because on that layout that position is `M`. Sending the label onward would type whatever letter
 * *this* phone thinks the key is, on a PC that may well disagree — precisely the bug §2.19.4 exists
 * to avoid.
 *
 * ## What does supply it: [KeyEvent.getScanCode]
 *
 * Android reports the Linux **evdev** code of the physical key in `scanCode`, and for a USB or
 * Bluetooth HID keyboard that code was produced by the kernel from the HID usage the keyboard
 * reported, through one fixed table (`hid_keyboard[]` in `drivers/hid/hid-input.c`). Inverting that
 * table recovers the usage exactly — no layout anywhere in the path.
 *
 * [SCAN_TO_USAGE] is that inverse, restricted to the usages the server will inject. It is a
 * bijection, and the protocol's injectable set is what makes it one: HID `0x31` (Backslash) and
 * `0x32` (Non-US `#`) both map to evdev `43`, and `0x32` is deliberately *not* injectable, so no
 * evdev code is claimed twice.
 *
 * The table was checked against a second, independent artifact rather than trusted: AOSP's own
 * `/system/usr/keylayout/Generic.kl`, read off a device, maps each evdev code to an Android keycode
 * label, and for all 104 injectable usages that label is the key the protocol names. Two projects
 * that never talked to each other agree about every position. `PcLinkHidKeysTest` re-runs that
 * agreement as a unit test against the labels, so a typo cannot survive a build.
 *
 * ## And when there is no position at all
 *
 * A soft keyboard, an IME, or a synthesized event has `scanCode == 0`: there is no physical key, so
 * there is no position to send. Characters from those go down §2.19.4's **text** path (`"s"`)
 * instead, which is layout-independent on both ends. [KEYCODE_TO_USAGE] covers only the handful of
 * *non-character* keys a soft source still emits as key events — Enter, Backspace, Tab, Escape, the
 * arrows, the editing cluster — where there is nothing for the text path to insert and the label is
 * unambiguous across every layout.
 */
object PcLinkHidKeys {

    private fun k(usage: Int, scan: Int) = usage to scan

    /**
     * The injectable usages and their Linux evdev codes — the inverse of the kernel's
     * `hid_keyboard[]`, in the order `INJECTABLE_USAGES` lists them in `xpl-proto/src/input.rs`.
     *
     * 104 entries: letters, digits, editing and whitespace, punctuation, F1–F12, the navigation
     * cluster, the keypad, `Application`, and the eight modifiers. A usage outside this set is a
     * content error the server drops, so this list is also the definition of what this client may
     * send.
     */
    private val USAGE_SCAN: List<Pair<Int, Int>> = listOf(
        k(0x04, 30), k(0x05, 48), k(0x06, 46), k(0x07, 32),
        k(0x08, 18), k(0x09, 33), k(0x0A, 34), k(0x0B, 35),
        k(0x0C, 23), k(0x0D, 36), k(0x0E, 37), k(0x0F, 38),
        k(0x10, 50), k(0x11, 49), k(0x12, 24), k(0x13, 25),
        k(0x14, 16), k(0x15, 19), k(0x16, 31), k(0x17, 20),
        k(0x18, 22), k(0x19, 47), k(0x1A, 17), k(0x1B, 45),
        k(0x1C, 21), k(0x1D, 44), k(0x1E, 2), k(0x1F, 3),
        k(0x20, 4), k(0x21, 5), k(0x22, 6), k(0x23, 7),
        k(0x24, 8), k(0x25, 9), k(0x26, 10), k(0x27, 11),
        k(0x28, 28), k(0x29, 1), k(0x2A, 14), k(0x2B, 15),
        k(0x2C, 57), k(0x2D, 12), k(0x2E, 13), k(0x2F, 26),
        k(0x30, 27), k(0x31, 43), k(0x33, 39), k(0x34, 40),
        k(0x35, 41), k(0x36, 51), k(0x37, 52), k(0x38, 53),
        k(0x39, 58), k(0x3A, 59), k(0x3B, 60), k(0x3C, 61),
        k(0x3D, 62), k(0x3E, 63), k(0x3F, 64), k(0x40, 65),
        k(0x41, 66), k(0x42, 67), k(0x43, 68), k(0x44, 87),
        k(0x45, 88), k(0x46, 99), k(0x47, 70), k(0x48, 119),
        k(0x49, 110), k(0x4A, 102), k(0x4B, 104), k(0x4C, 111),
        k(0x4D, 107), k(0x4E, 109), k(0x4F, 106), k(0x50, 105),
        k(0x51, 108), k(0x52, 103), k(0x53, 69), k(0x54, 98),
        k(0x55, 55), k(0x56, 74), k(0x57, 78), k(0x58, 96),
        k(0x59, 79), k(0x5A, 80), k(0x5B, 81), k(0x5C, 75),
        k(0x5D, 76), k(0x5E, 77), k(0x5F, 71), k(0x60, 72),
        k(0x61, 73), k(0x62, 82), k(0x63, 83), k(0x65, 127),
        k(0xE0, 29), k(0xE1, 42), k(0xE2, 56), k(0xE3, 125),
        k(0xE4, 97), k(0xE5, 54), k(0xE6, 100), k(0xE7, 126)
    )

    /** Every usage the server will inject — this client sends nothing else. */
    val INJECTABLE: Set<Int> = USAGE_SCAN.map { it.first }.toSet()

    /** Linux evdev scan code → HID usage. The primary, layout-free path. */
    val SCAN_TO_USAGE: Map<Int, Int> = USAGE_SCAN.associate { (usage, scan) -> scan to usage }

    /**
     * Android keycode → HID usage, for events with **no scan code**.
     *
     * Only keys whose identity does not depend on a layout: an IME's Enter is Enter on every
     * keyboard in the world, and none of these is a character the text path could carry instead.
     * Letters and digits are deliberately absent — for those, the label *is* layout-dependent and
     * §2.19.4's answer is the text path, not a guess at a position.
     */
    val KEYCODE_TO_USAGE: Map<Int, Int> = mapOf(
        KeyEvent.KEYCODE_ENTER to 0x28,
        KeyEvent.KEYCODE_NUMPAD_ENTER to 0x58,
        KeyEvent.KEYCODE_ESCAPE to 0x29,
        KeyEvent.KEYCODE_DEL to 0x2A,
        KeyEvent.KEYCODE_FORWARD_DEL to 0x4C,
        KeyEvent.KEYCODE_TAB to 0x2B,
        KeyEvent.KEYCODE_SPACE to 0x2C,
        KeyEvent.KEYCODE_DPAD_UP to 0x52,
        KeyEvent.KEYCODE_DPAD_DOWN to 0x51,
        KeyEvent.KEYCODE_DPAD_LEFT to 0x50,
        KeyEvent.KEYCODE_DPAD_RIGHT to 0x4F,
        KeyEvent.KEYCODE_MOVE_HOME to 0x4A,
        KeyEvent.KEYCODE_MOVE_END to 0x4D,
        KeyEvent.KEYCODE_PAGE_UP to 0x4B,
        KeyEvent.KEYCODE_PAGE_DOWN to 0x4E,
        KeyEvent.KEYCODE_INSERT to 0x49
    )

    /** Whether [usage] is one the server will inject (§2.19.6 drops anything else). */
    fun isInjectable(usage: Int): Boolean = INJECTABLE.contains(usage)

    /**
     * The HID usage of one key event, or null when there is no position to send.
     *
     * Null is a normal answer, not a failure: it means either "this key is not in the injectable
     * set" or "this event came from a soft source", and in both cases the caller's next move is the
     * text path or nothing at all — never a guessed key on someone else's desktop.
     */
    fun usageOf(scanCode: Int, keyCode: Int): Int? {
        if (scanCode != 0) {
            SCAN_TO_USAGE[scanCode]?.let { return it }
        }
        return KEYCODE_TO_USAGE[keyCode]
    }

    /**
     * Whether this event came from a real key on a real keyboard.
     *
     * Two independent tells, and both must hold: a virtual device id (what an IME, an accessibility
     * service or `adb shell input` reports) and a zero scan code each mean there was no physical
     * key. Anything else is a soft press, and soft presses belong to the text path.
     */
    fun isPhysical(event: KeyEvent): Boolean =
        event.deviceId > 0 &&
            event.scanCode != 0 &&
            (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) == 0
}
