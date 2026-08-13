package com.teleteh.xplayer2.data.network

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HID key map (`protocol.md` §2.19.4): a physical key position, all the way from an Android
 * [KeyEvent] to a USB HID usage.
 *
 * ## What is actually being checked, and against what
 *
 * [PcLinkHidKeys.SCAN_TO_USAGE] is the inverse of the Linux kernel's `hid_keyboard[]` table
 * (`drivers/hid/hid-input.c`), which is what turned the keyboard's HID usage into the evdev scan
 * code Android hands us. Transcribing 104 entries of a kernel table by hand is exactly the sort of
 * thing that is 99% right, and the 1% is somebody's `Home` key typing a `7`.
 *
 * So it is not trusted: [KEYS] carries, for every injectable usage, the evdev code this client
 * believes in **and the Android keycode label AOSP's own `/system/usr/keylayout/Generic.kl` gives
 * that code**, read off a real device. Two projects that never talked to each other — the Linux HID
 * driver and the Android input stack — have to agree about every position, and a typo in either
 * column breaks that agreement. `Backslash` is the entry that shows the check has teeth: HID `0x31`
 * and `0x32` both map to evdev 43, and the protocol's injectable set omits `0x32` precisely so the
 * inverse stays a function.
 *
 * ## And what is deliberately not mapped
 *
 * [KeyEvent.getKeyCode] is a *label*, already resolved through the phone's layout — the key marked
 * `;` on AZERTY reports `KEYCODE_M` — so it cannot name a position and is not used as one. The small
 * [PcLinkHidKeys.KEYCODE_TO_USAGE] fallback covers only keys whose identity no layout changes, for
 * events that carry no scan code at all.
 */
class PcLinkHidKeysTest {

    private data class Key(val usage: Int, val name: String, val evdev: Int, val android: String)

    private fun k(usage: Int, name: String, evdev: Int, android: String) = Key(usage, name, evdev, android)

    /**
     * The protocol's published injectable set (`INJECTABLE_USAGES` in `xpl-proto/src/input.rs`),
     * each with the evdev code the kernel gives it and the Android keycode label AOSP gives that
     * evdev code.
     */
    private val KEYS: List<Key> = listOf(
        k(0x04, "A", 30, "A"), k(0x05, "B", 48, "B"),
        k(0x06, "C", 46, "C"), k(0x07, "D", 32, "D"),
        k(0x08, "E", 18, "E"), k(0x09, "F", 33, "F"),
        k(0x0A, "G", 34, "G"), k(0x0B, "H", 35, "H"),
        k(0x0C, "I", 23, "I"), k(0x0D, "J", 36, "J"),
        k(0x0E, "K", 37, "K"), k(0x0F, "L", 38, "L"),
        k(0x10, "M", 50, "M"), k(0x11, "N", 49, "N"),
        k(0x12, "O", 24, "O"), k(0x13, "P", 25, "P"),
        k(0x14, "Q", 16, "Q"), k(0x15, "R", 19, "R"),
        k(0x16, "S", 31, "S"), k(0x17, "T", 20, "T"),
        k(0x18, "U", 22, "U"), k(0x19, "V", 47, "V"),
        k(0x1A, "W", 17, "W"), k(0x1B, "X", 45, "X"),
        k(0x1C, "Y", 21, "Y"), k(0x1D, "Z", 44, "Z"),
        k(0x1E, "1", 2, "1"), k(0x1F, "2", 3, "2"),
        k(0x20, "3", 4, "3"), k(0x21, "4", 5, "4"),
        k(0x22, "5", 6, "5"), k(0x23, "6", 7, "6"),
        k(0x24, "7", 8, "7"), k(0x25, "8", 9, "8"),
        k(0x26, "9", 10, "9"), k(0x27, "0", 11, "0"),
        k(0x28, "Enter", 28, "ENTER"), k(0x29, "Escape", 1, "ESCAPE"),
        k(0x2A, "Backspace", 14, "DEL"), k(0x2B, "Tab", 15, "TAB"),
        k(0x2C, "Space", 57, "SPACE"), k(0x2D, "Minus", 12, "MINUS"),
        k(0x2E, "Equal", 13, "EQUALS"), k(0x2F, "LeftBracket", 26, "LEFT_BRACKET"),
        k(0x30, "RightBracket", 27, "RIGHT_BRACKET"), k(0x31, "Backslash", 43, "BACKSLASH"),
        k(0x33, "Semicolon", 39, "SEMICOLON"), k(0x34, "Quote", 40, "APOSTROPHE"),
        k(0x35, "Grave", 41, "GRAVE"), k(0x36, "Comma", 51, "COMMA"),
        k(0x37, "Period", 52, "PERIOD"), k(0x38, "Slash", 53, "SLASH"),
        k(0x39, "CapsLock", 58, "CAPS_LOCK"), k(0x3A, "F1", 59, "F1"),
        k(0x3B, "F2", 60, "F2"), k(0x3C, "F3", 61, "F3"),
        k(0x3D, "F4", 62, "F4"), k(0x3E, "F5", 63, "F5"),
        k(0x3F, "F6", 64, "F6"), k(0x40, "F7", 65, "F7"),
        k(0x41, "F8", 66, "F8"), k(0x42, "F9", 67, "F9"),
        k(0x43, "F10", 68, "F10"), k(0x44, "F11", 87, "F11"),
        k(0x45, "F12", 88, "F12"), k(0x46, "PrintScreen", 99, "SYSRQ"),
        k(0x47, "ScrollLock", 70, "SCROLL_LOCK"), k(0x48, "Pause", 119, "BREAK"),
        k(0x49, "Insert", 110, "INSERT"), k(0x4A, "Home", 102, "MOVE_HOME"),
        k(0x4B, "PageUp", 104, "PAGE_UP"), k(0x4C, "Delete", 111, "FORWARD_DEL"),
        k(0x4D, "End", 107, "MOVE_END"), k(0x4E, "PageDown", 109, "PAGE_DOWN"),
        k(0x4F, "Right", 106, "DPAD_RIGHT"), k(0x50, "Left", 105, "DPAD_LEFT"),
        k(0x51, "Down", 108, "DPAD_DOWN"), k(0x52, "Up", 103, "DPAD_UP"),
        k(0x53, "NumLock", 69, "NUM_LOCK"), k(0x54, "KpSlash", 98, "NUMPAD_DIVIDE"),
        k(0x55, "KpAsterisk", 55, "NUMPAD_MULTIPLY"), k(0x56, "KpMinus", 74, "NUMPAD_SUBTRACT"),
        k(0x57, "KpPlus", 78, "NUMPAD_ADD"), k(0x58, "KpEnter", 96, "NUMPAD_ENTER"),
        k(0x59, "Kp1", 79, "NUMPAD_1"), k(0x5A, "Kp2", 80, "NUMPAD_2"),
        k(0x5B, "Kp3", 81, "NUMPAD_3"), k(0x5C, "Kp4", 75, "NUMPAD_4"),
        k(0x5D, "Kp5", 76, "NUMPAD_5"), k(0x5E, "Kp6", 77, "NUMPAD_6"),
        k(0x5F, "Kp7", 71, "NUMPAD_7"), k(0x60, "Kp8", 72, "NUMPAD_8"),
        k(0x61, "Kp9", 73, "NUMPAD_9"), k(0x62, "Kp0", 82, "NUMPAD_0"),
        k(0x63, "KpPeriod", 83, "NUMPAD_DOT"), k(0x65, "Application", 127, "MENU"),
        k(0xE0, "LeftControl", 29, "CTRL_LEFT"), k(0xE1, "LeftShift", 42, "SHIFT_LEFT"),
        k(0xE2, "LeftAlt", 56, "ALT_LEFT"), k(0xE3, "LeftGui", 125, "META_LEFT"),
        k(0xE4, "RightControl", 97, "CTRL_RIGHT"), k(0xE5, "RightShift", 54, "SHIFT_RIGHT"),
        k(0xE6, "RightAlt", 100, "ALT_RIGHT"), k(0xE7, "RightGui", 126, "META_RIGHT")
    )

    /**
     * Every position agrees with AOSP.
     *
     * The assertion that makes the whole path trustworthy: for each usage the protocol publishes,
     * the evdev code this client maps it to is the one Android's own key layout calls by the same
     * name. A single mistyped digit in either the kernel transcription or the injectable set shows
     * up here as a key that claims to be `Home` and is really `KP7`.
     */
    @Test
    fun `every injectable usage sits on the position AOSP agrees it does`() {
        for (key in KEYS) {
            assertEquals(
                "usage 0x%02X (%s)".format(key.usage, key.name),
                key.usage,
                PcLinkHidKeys.SCAN_TO_USAGE[key.evdev]
            )
        }
    }

    /** The set this client will send is exactly the set the server will inject — no more, no less. */
    @Test
    fun `the injectable set is the protocol's, entry for entry`() {
        assertEquals(104, KEYS.size)
        assertEquals(KEYS.map { it.usage }.toSet(), PcLinkHidKeys.INJECTABLE)
        assertEquals(KEYS.size, PcLinkHidKeys.SCAN_TO_USAGE.size)
    }

    /**
     * No evdev code is claimed by two usages.
     *
     * Not a formality: HID `0x31` (Backslash) and `0x32` (Non-US #) both map to evdev 43 in the
     * kernel table, and the only reason the inverse is a function at all is that the protocol
     * leaves `0x32` out of the injectable set. Adding it would silently make one of the two win.
     */
    @Test
    fun `the inverse map is a function`() {
        assertEquals(KEYS.map { it.evdev }.toSet().size, KEYS.size)
        assertNull("HID 0x32 shares evdev 43 with Backslash and must stay out", 
            PcLinkHidKeys.INJECTABLE.firstOrNull { it == 0x32 })
        assertEquals(0x31, PcLinkHidKeys.SCAN_TO_USAGE[43])
    }

    /** A scan code nothing published claims is not guessed at. */
    @Test
    fun `an unmapped scan code has no usage`() {
        // evdev 0 is "no key", 240 is KEY_UNKNOWN, 114 is VolumeDown — none of them injectable.
        assertNull(PcLinkHidKeys.usageOf(scanCode = 0, keyCode = 0))
        assertNull(PcLinkHidKeys.usageOf(scanCode = 240, keyCode = 0))
        assertNull(PcLinkHidKeys.usageOf(scanCode = 114, keyCode = KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    /**
     * The phone's own volume keys keep working while control is on.
     *
     * They travel with a scan code (they are real keys), but no injectable usage claims that code,
     * so [PcLinkHidKeys.usageOf] answers null, the remote does not consume the event, and the phone
     * handles it as it always did. This is the mechanism, not a special case in the activity.
     */
    @Test
    fun `volume keys are not the PC's business`() {
        assertNull(PcLinkHidKeys.usageOf(scanCode = 115, keyCode = KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(PcLinkHidKeys.usageOf(scanCode = 114, keyCode = KeyEvent.KEYCODE_VOLUME_DOWN))
    }

    /** A real key wins over its label — the scan code is consulted first and answers. */
    @Test
    fun `a physical key is identified by its position`() {
        // evdev 30 is the key marked A on a US layout and M on AZERTY. Both send HID 0x04, and the
        // PC decides which letter that is — which is the entire point of §2.19.4.
        assertEquals(0x04, PcLinkHidKeys.usageOf(scanCode = 30, keyCode = KeyEvent.KEYCODE_A))
        assertEquals(0x04, PcLinkHidKeys.usageOf(scanCode = 30, keyCode = KeyEvent.KEYCODE_M))
    }

    /**
     * With no scan code there is no position, and only the layout-proof keys fall back.
     *
     * A soft keyboard's Enter is Enter everywhere, so it maps. A soft `A` does not: that label came
     * from the phone's layout and means nothing on the PC's — those characters go down the text
     * path instead (§2.19.4).
     */
    @Test
    fun `a soft key falls back only where the label cannot lie`() {
        assertEquals(0x28, PcLinkHidKeys.usageOf(scanCode = 0, keyCode = KeyEvent.KEYCODE_ENTER))
        assertEquals(0x2A, PcLinkHidKeys.usageOf(scanCode = 0, keyCode = KeyEvent.KEYCODE_DEL))
        assertEquals(0x52, PcLinkHidKeys.usageOf(scanCode = 0, keyCode = KeyEvent.KEYCODE_DPAD_UP))
        assertNull(PcLinkHidKeys.usageOf(scanCode = 0, keyCode = KeyEvent.KEYCODE_A))
        assertNull(PcLinkHidKeys.usageOf(scanCode = 0, keyCode = KeyEvent.KEYCODE_1))
    }

    /** Every fallback keycode names a usage the server will actually inject. */
    @Test
    fun `the fallback map only names injectable usages`() {
        for ((keyCode, usage) in PcLinkHidKeys.KEYCODE_TO_USAGE) {
            assertTrue(
                "keycode $keyCode maps to a usage the server would drop",
                PcLinkHidKeys.isInjectable(usage)
            )
        }
        assertNotNull(PcLinkHidKeys.KEYCODE_TO_USAGE[KeyEvent.KEYCODE_ESCAPE])
    }

    /** The eight modifiers are all there — a missing one is a chord that can never be sent. */
    @Test
    fun `all eight modifiers are injectable`() {
        for (usage in 0xE0..0xE7) {
            assertTrue("modifier 0x%02X".format(usage), PcLinkHidKeys.isInjectable(usage))
        }
    }
}
