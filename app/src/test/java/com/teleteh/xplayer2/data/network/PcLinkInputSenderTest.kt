package com.teleteh.xplayer2.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PcLinkInputSender]: the coalescing (§2.19.5), the negotiation gate (§2.19.1), and the letting go.
 *
 * The vectors next door ([PcLinkInputVectorsTest]) prove the batching does not change what the PC
 * does. This proves the things the vectors have no opinion about, most of which are about *not*
 * sending: a mode the server never offered, a key it would refuse, and — the one that matters —
 * releasing what is held when the phone stops driving but the session does not stop.
 */
class PcLinkInputSenderTest {

    private val everything = PcLinkInputProtocol.CLIENT_OFFER

    private fun sender(offer: PcInputOffer = everything) = PcLinkInputSender(offer)

    // ---------------------------------------------------------------------------------------
    // Coalescing
    // ---------------------------------------------------------------------------------------

    /** A burst of relative samples is one delta: the sum of them, not a message each. */
    @Test
    fun `relative motion sums into one event`() {
        val s = sender()
        repeat(10) { s.move(3, -1) }
        assertEquals(listOf(PcInputEvent.Move(30, -10)), s.drain())
        assertNull("and nothing is left behind", s.drain())
    }

    /** Absolute is latest-wins: two taps in one interval are one destination, not a journey. */
    @Test
    fun `absolute motion keeps only the latest position`() {
        val s = sender()
        s.moveAbs(100, 100)
        s.moveAbs(200, 200)
        s.moveAbs(300, 300)
        assertEquals(listOf(PcInputEvent.MoveAbs(300, 300)), s.drain())
    }

    /**
     * A click in the middle of a drag cuts the motion in two, and stays in the middle.
     *
     * This is the whole reason the pending slot is flushed by non-motion events instead of being
     * summed across the batch: press-drag-release is a selection, and the same three events with
     * the motion merged into one delta before the press is a click on the wrong thing.
     */
    @Test
    fun `a button splits the motion around it, in order`() {
        val s = sender()
        s.move(10, 0)
        s.move(10, 0)
        s.button(PcLinkInputProtocol.BUTTON_LEFT, true)
        s.move(-5, 5)
        s.button(PcLinkInputProtocol.BUTTON_LEFT, false)
        assertEquals(
            listOf(
                PcInputEvent.Move(20, 0),
                PcInputEvent.Button(0, true),
                PcInputEvent.Move(-5, 5),
                PcInputEvent.Button(0, false)
            ),
            s.drain()
        )
    }

    /** Switching pointer mode mid-batch flushes rather than merges: they are different journeys. */
    @Test
    fun `relative and absolute do not merge into each other`() {
        val s = sender()
        s.move(10, 10)
        s.moveAbs(500, 500)
        s.move(4, 4)
        assertEquals(
            listOf(
                PcInputEvent.Move(10, 10),
                PcInputEvent.MoveAbs(500, 500),
                PcInputEvent.Move(4, 4)
            ),
            s.drain()
        )
    }

    /** Nothing to say is no message at all — an empty `input` would still cost an envelope. */
    @Test
    fun `an idle sender drains to nothing`() {
        val s = sender()
        assertNull(s.drain())
        s.move(0, 0)
        s.wheel(0, 0)
        s.text("")
        assertNull(s.drain())
        assertFalse(s.hasPending())
    }

    /**
     * No batch ever exceeds the cap, because the server drops an over-cap batch **whole** — every
     * keystroke in it, not just the overflow.
     */
    @Test
    fun `a long burst is split at the cap, in order`() {
        val s = sender()
        val n = PcLinkInputProtocol.EVENT_CAP + 40
        // Keys rather than motion: motion coalesces and could never reach the cap.
        repeat(n) { s.key(0x04, it % 2 == 0) }
        var total = 0
        var batches = 0
        while (true) {
            val batch = s.drain() ?: break
            assertTrue(batch.size <= PcLinkInputProtocol.EVENT_CAP)
            total += batch.size
            batches++
        }
        assertEquals(n, total)
        assertEquals(2, batches)
    }

    // ---------------------------------------------------------------------------------------
    // The negotiation gate
    // ---------------------------------------------------------------------------------------

    /**
     * A mode the server did not offer is refused here rather than sent and ignored.
     *
     * §2.19.1 makes the offer a list "so each side learns what the other will act on rather than
     * discovering it by having an event ignored" — this is the sender's half of taking that
     * seriously.
     */
    @Test
    fun `only the offered modes are sent`() {
        val pointerOnly = PcInputOffer(
            pointer = listOf(PcLinkInputProtocol.POINTER_REL),
            keys = emptyList(),
            wheel = false
        )
        val s = sender(pointerOnly)
        s.move(10, 0)
        s.moveAbs(100, 100)
        s.wheel(0, -120)
        s.key(0x04, true)
        s.text("hi")
        assertEquals(listOf(PcInputEvent.Move(10, 0)), s.drain())
    }

    /** A future pointer mode in the offer is neither refused nor acted on. */
    @Test
    fun `an unknown mode in the offer changes nothing`() {
        val withPen = PcInputOffer(
            pointer = listOf(PcLinkInputProtocol.POINTER_REL, "pen"),
            keys = listOf(PcLinkInputProtocol.KEYS_HID),
            wheel = true
        )
        val s = sender(withPen)
        s.move(5, 5)
        s.key(0x2C, true)
        assertEquals(listOf(PcInputEvent.Move(5, 5), PcInputEvent.Key(0x2C, true)), s.drain())
    }

    /**
     * A key the server would drop is never sent — and, more to the point, never *remembered* as
     * held.
     *
     * If it were, the release on teardown would send a key-up for a key the PC never saw go down,
     * which the PC would dutifully inject: a phantom release into whatever the user is doing.
     */
    @Test
    fun `a key outside the injectable set is refused and not remembered`() {
        val s = sender()
        s.key(0x01, true) // ErrorRollOver — a real HID usage, not an injectable one
        s.key(0xFF, true)
        assertNull(s.drain())
        assertEquals(0, s.heldCount())
    }

    /** An out-of-range coordinate is a content error the server drops; this never produces one. */
    @Test
    fun `an out of range absolute position is refused`() {
        val s = sender()
        s.moveAbs(-1, 0)
        s.moveAbs(0, PcLinkInputProtocol.ABS_MAX + 1)
        assertNull(s.drain())
        s.moveAbs(0, PcLinkInputProtocol.ABS_MAX)
        assertNotNull(s.drain())
    }

    /** A fourth mouse button is not guessed at as one of the three the protocol has. */
    @Test
    fun `an unknown button is refused`() {
        val s = sender()
        s.button(3, true)
        s.button(-1, true)
        assertNull(s.drain())
        assertEquals(0, s.heldCount())
    }

    // ---------------------------------------------------------------------------------------
    // Letting go — the worst bug this file exists to prevent
    // ---------------------------------------------------------------------------------------

    /**
     * Everything held is released, buttons first and then keys in ascending usage order.
     *
     * The order is the reference server's and it is not arbitrary: releasing `A` before the `Ctrl`
     * that was holding it means the PC never sees a moment of `Ctrl` alone, which on some desktops
     * opens a menu.
     */
    @Test
    fun `release lets go of everything, modifiers last`() {
        val s = sender()
        s.key(0xE0, true) // LeftControl
        s.key(0x04, true) // A
        s.button(PcLinkInputProtocol.BUTTON_RIGHT, true)
        s.button(PcLinkInputProtocol.BUTTON_LEFT, true)
        assertEquals(4, s.heldCount())
        s.drain()

        s.releaseAll()
        assertEquals(
            listOf(
                PcInputEvent.Button(0, false),
                PcInputEvent.Button(1, false),
                PcInputEvent.Key(0x04, false),
                PcInputEvent.Key(0xE0, false)
            ),
            s.drain()
        )
        assertEquals(0, s.heldCount())
    }

    /** What was already let go of is not let go of twice. */
    @Test
    fun `release only names what is still down`() {
        val s = sender()
        s.key(0xE0, true)
        s.key(0x04, true)
        s.key(0x04, false)
        s.drain()
        s.releaseAll()
        assertEquals(listOf(PcInputEvent.Key(0xE0, false)), s.drain())
    }

    /** Safe on every exit path, which is how it ends up on all of them. */
    @Test
    fun `release with nothing held sends nothing`() {
        val s = sender()
        s.releaseAll()
        assertNull(s.drain())
        s.releaseAll()
        assertNull(s.drain())
    }

    /**
     * A release still pending when the drag continues does not resurrect the button: the state is
     * the sender's, and it is emptied at the moment the release is queued, not when it is written.
     */
    @Test
    fun `release empties the held state immediately`() {
        val s = sender()
        s.button(PcLinkInputProtocol.BUTTON_LEFT, true)
        s.releaseAll()
        assertEquals(0, s.heldCount())
        // Drained later, but the answer above was already true.
        assertNotNull(s.drain())
    }

    /**
     * [PcLinkInputSender.discard] is for a session that has already died, where §2.19.5 makes the
     * *receiver* release everything on its own teardown — so these releases have nowhere to go.
     */
    @Test
    fun `discard drops the queue and forgets what was held`() {
        val s = sender()
        s.key(0xE0, true)
        s.move(10, 10)
        s.discard()
        assertNull(s.drain())
        assertEquals(0, s.heldCount())
        assertFalse(s.hasPending())
    }

    /**
     * Anything worth sending nudges the writer out of its idle sleep — motion included.
     *
     * This is not a hole in the coalescing. The writer ignores the nudge while it is already
     * running at frame rate, so the only thing a nudge can do is end a 200 ms idle wait, which is
     * exactly what the *first* sample of a gesture needs: without it a drag opens with a fifth of a
     * second of nothing and then a jump.
     */
    @Test
    fun `anything worth sending nudges the writer`() {
        var wakes = 0
        val s = PcLinkInputSender(everything) { wakes++ }
        s.move(10, 10)
        s.moveAbs(100, 100)
        s.button(PcLinkInputProtocol.BUTTON_LEFT, true)
        s.key(0x04, true)
        s.wheel(0, -120)
        s.text("x")
        assertEquals(6, wakes)
    }

    /** What is not worth sending does not wake anything either. */
    @Test
    fun `a refused event does not nudge the writer`() {
        var wakes = 0
        val s = PcLinkInputSender(everything) { wakes++ }
        s.move(0, 0)
        s.wheel(0, 0)
        s.text("")
        s.key(0x01, true)
        s.moveAbs(-1, 0)
        s.button(9, true)
        assertEquals(0, wakes)
    }

    // ---------------------------------------------------------------------------------------
    // Scaling
    // ---------------------------------------------------------------------------------------

    /**
     * A drag slower than one unit per sample still moves.
     *
     * Truncating each sample on its own is how a touchpad ends up ignoring careful, slow movement —
     * exactly the movement someone makes when they are trying to hit something small.
     */
    @Test
    fun `the pointer scaler carries its remainder`() {
        val scaler = PcPointerScaler()
        // A 65536-unit span means one view pixel is one unit; a tenth of a pixel per sample must
        // still add up to one unit after ten of them.
        var total = 0
        repeat(10) { total += scaler.scale(0.1f, 0f, 65536, 65536, 1f).first }
        assertEquals(1, total)
    }

    /** A reset drops the carry, so the end of one gesture cannot nudge the start of the next. */
    @Test
    fun `resetting the scaler drops the carry`() {
        val scaler = PcPointerScaler()
        repeat(9) { scaler.scale(0.1f, 0f, 65536, 65536, 1f) }
        scaler.reset()
        assertEquals(0, scaler.scale(0.1f, 0f, 65536, 65536, 1f).first)
    }

    /** The wheel accumulates the same way, and emits whole notches of 120. */
    @Test
    fun `the wheel accumulates into whole notches`() {
        val wheel = PcWheelAccumulator(pixelsPerNotch = 48f)
        assertEquals(0, wheel.fromPixels(20f))
        assertEquals(PcLinkInputProtocol.WHEEL_NOTCH, wheel.fromPixels(30f))
        assertEquals(-PcLinkInputProtocol.WHEEL_NOTCH, wheel.fromPixels(-60f))
    }

    // ---------------------------------------------------------------------------------------
    // Availability
    // ---------------------------------------------------------------------------------------

    /**
     * The two refusals stay tellable apart, because they have different owners: one is answered by
     * re-pairing this phone, the other by a switch on the PC.
     */
    @Test
    fun `an absent offer says why, and the reason depends on the envelope`() {
        assertEquals(
            PcInputAvailability.Off(PcInputUnavailable.NOT_ENCRYPTED),
            PcInputAvailability.of(null, encrypted = false)
        )
        assertEquals(
            PcInputAvailability.Off(PcInputUnavailable.OPERATOR_OFF),
            PcInputAvailability.of(null, encrypted = true)
        )
        assertEquals(
            PcInputAvailability.Live(everything),
            PcInputAvailability.of(everything, encrypted = true)
        )
    }

    /**
     * A `config.input` that arrived on an unencrypted session would be a server breaking §2.19, and
     * the client still reads it as live: the encryption state is only ever consulted to *explain an
     * absence*, never to second-guess a grant. Second-guessing it would mean two gates that can
     * disagree, and the structural one is the server's.
     */
    @Test
    fun `the offer is authoritative when it is present`() {
        assertEquals(
            PcInputAvailability.Live(everything),
            PcInputAvailability.of(everything, encrypted = false)
        )
    }

    /**
     * `hello` carries the hint, on every session, and it authorizes nothing.
     *
     * It rides the plaintext handshake, so it is only ever a hint — but a *necessary* one: without
     * it the PC has no reason to offer its "let this phone control me" switch for this phone at
     * all, and the operator never gets the chance to turn input on.
     */
    @Test
    fun `hello offers what this phone could drive the PC with`() {
        val obj = org.json.JSONObject(
            PcLinkProtocol.helloLine("Pixel 9 Pro", listOf(PcCodecCapability("video/hevc", 3840, 2160, 60))).trim()
        )
        val input = PcInputOffer.parse(obj.getJSONObject("input"))!!
        assertTrue(input.hasRelative)
        assertTrue(input.hasAbsolute)
        assertTrue(input.hasHidKeys)
        assertTrue(input.hasText)
        assertTrue(input.wheel)
    }

    /**
     * A `config` with no `input` is the ordinary case and must parse exactly as it always did.
     *
     * This is §7 in one assertion: every server built before §2.19, every plaintext session and
     * every session with the operator's switch off sends this, and it has to keep meaning what it
     * meant — a working picture with no input, not a `config` this client refuses.
     */
    @Test
    fun `a config without input is unchanged and still a valid stream`() {
        val config = PcLinkProtocol.parseConfig(
            org.json.JSONObject(
                """{"type":"config","mime":"video/hevc","width":1920,"height":1080,"fps":60,
                   "stereo":"mono","videoToken":"${"ab".repeat(32)}"}"""
            )
        )
        assertNotNull(config)
        assertNull("no input is not an error", config!!.input)
        assertEquals(1920, config.width)
    }

    /** And a `config` that grants input parses the grant. */
    @Test
    fun `a config with input carries the grant`() {
        val config = PcLinkProtocol.parseConfig(
            org.json.JSONObject(
                """{"type":"config","mime":"video/hevc","width":1920,"height":1080,"fps":60,
                   "stereo":"mono","videoToken":"${"ab".repeat(32)}",
                   "input":{"pointer":["rel"],"keys":["hid"],"wheel":false}}"""
            )
        )!!
        val input = config.input!!
        assertTrue(input.hasRelative)
        assertFalse(input.hasAbsolute)
        assertFalse(input.wheel)
    }

    /** `config.input` parses out of the JSON the server sends, and its absence is not an error. */
    @Test
    fun `the offer parses from config`() {
        val parsed = PcInputOffer.parse(
            org.json.JSONObject(
                """{"pointer":["rel","abs"],"keys":["hid","text"],"wheel":true}"""
            )
        )
        assertEquals(everything, parsed)
        assertNull(PcInputOffer.parse(null))
        val bare = PcInputOffer.parse(org.json.JSONObject("{}"))!!
        assertFalse(bare.hasRelative)
        assertFalse(bare.wheel)
    }
}
