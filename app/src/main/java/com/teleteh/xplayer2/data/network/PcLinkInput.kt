package com.teleteh.xplayer2.data.network

import org.json.JSONArray
import org.json.JSONObject

/**
 * Input passthrough, `protocol.md` §2.19 — the phone driving the PC's mouse and keyboard.
 *
 * This is the wire half: the event shapes, the exact bytes they serialize to, the capability
 * negotiation and the phone's half of the coordinate map. Sans-io and Android-free, so every rule
 * is exercised by JVM unit tests against `app/src/test/resources/pclink_input_vectors.json` — a
 * verbatim copy of the server's `crates/xpl-proto/test-vectors/input_vectors.json`, the same bytes
 * the Rust and Swift suites consume.
 *
 * Three things about this path are worth stating up front, because each is a rule that bites:
 *
 * * **Input exists only inside an encrypted session** (§2.18). Not a check this client is asked to
 *   remember: the server announces `config.input` *iff* the operator enabled input **and** the
 *   session negotiated `encryption` ≥ 2, so a plaintext session simply never hears the offer and
 *   this client never enables its UI. [PcInputOffer] is what carries that answer.
 * * **Coordinates are normalized to the content this phone displays**, letterbox already removed,
 *   `0..65535` across the picture — never desktop pixels. The server, and only the server, knows
 *   its desktop size; [viewToContent] is the phone's half and is pinned by the same vectors as the
 *   server's half, so a touch lands on the same pixel from Kotlin as from Rust.
 * * **The encoding is byte-exact**, which is why the lines here are built by hand rather than
 *   through [JSONObject]: `org.json` neither guarantees key order nor escapes the way `serde_json`
 *   does, and the vectors pin whole lines. Hand-building is also what makes a 60 Hz path cheap.
 */
object PcLinkInputProtocol {

    /** The span of a normalized coordinate: `0..ABS_MAX` covers the content edge to edge. */
    const val ABS_MAX = 65_535

    /**
     * The divisor the map uses: one past [ABS_MAX]. It is what makes [ABS_MAX] land on the last
     * pixel of the desktop rather than one past it.
     */
    const val ABS_RANGE = 65_536L

    /** One wheel notch, the classic `WHEEL_DELTA`. Finer scrolling is any smaller multiple. */
    const val WHEEL_NOTCH = 120

    /**
     * The most events one `input` message may carry (§2.19.2). A batch over the cap is dropped
     * *whole* by the server, so [PcLinkInputSender] splits rather than overflows.
     */
    const val EVENT_CAP = 512

    // Pointer modes, key paths — the strings `hello.input` / `config.input` carry.
    const val POINTER_REL = "rel"
    const val POINTER_ABS = "abs"
    const val KEYS_HID = "hid"
    const val KEYS_TEXT = "text"

    /** Wire discriminants for [PcInputEvent.Button]. */
    const val BUTTON_LEFT = 0
    const val BUTTON_RIGHT = 1
    const val BUTTON_MIDDLE = 2

    /**
     * What this client offers in `hello.input` (§2.19.1).
     *
     * Advisory only — it rides the plaintext handshake and authorizes nothing. It exists so the
     * PC's UI can offer its "let this phone control me" switch to a phone that could use it, so
     * every Android phone sends it: the remote's touchpad view is a pointer even with no mouse
     * plugged in, and the IME is the text path even with no keyboard.
     */
    val CLIENT_OFFER = PcInputOffer(
        pointer = listOf(POINTER_REL, POINTER_ABS),
        keys = listOf(KEYS_HID, KEYS_TEXT),
        wheel = true
    )

    /**
     * The `input` line (client → server, §2.19.2), terminated by `\n`.
     *
     * Byte-exact against the `wire` vectors: `type` first, then `ev`, no spaces, keys in the order
     * below, non-ASCII left literal. Events are applied by the server strictly in the order given,
     * which is what lets a move, a button-down and another move share one message and still mean
     * what they would have as three.
     */
    fun inputLine(events: List<PcInputEvent>): String {
        val sb = StringBuilder(32 + 24 * events.size)
        sb.append("{\"type\":\"input\",\"ev\":[")
        for ((i, e) in events.withIndex()) {
            if (i > 0) sb.append(',')
            e.appendTo(sb)
        }
        sb.append("]}\n")
        return sb.toString()
    }

    /**
     * Turns a raw touch in a letterboxed view into a normalized content coordinate — the phone's
     * half of the map (§2.19.3), and the half a sender gets wrong if it guesses.
     *
     * [viewW]/[viewH] is the on-screen area the picture occupies; [contentW]/[contentH] is the
     * aspect of the picture inside it (see [contentWidthOf] for the stereo case). The content is
     * centered and scaled to fit preserving aspect, which leaves letterbox bars. A touch inside the
     * picture becomes its normalized position; a touch **on a bar returns null** — there is no
     * desktop under the bar, and the phone sends nothing rather than pinning to an edge.
     *
     * Integer arithmetic throughout, and deliberately: this must land on the same desktop pixel in
     * Kotlin, Rust and Swift, and float formatting does not survive three languages.
     */
    fun viewToContent(
        tx: Int,
        ty: Int,
        viewW: Int,
        viewH: Int,
        contentW: Int,
        contentH: Int
    ): Pair<Int, Int>? {
        if (viewW <= 0 || viewH <= 0 || contentW <= 0 || contentH <= 0) return null
        val vw = viewW.toLong()
        val vh = viewH.toLong()
        val cw = contentW.toLong()
        val ch = contentH.toLong()
        // Whichever axis is the binding constraint fills the view; the other gets the bars.
        val rectW: Long
        val rectH: Long
        if (cw * vh >= vw * ch) {
            rectW = vw
            rectH = vw * ch / cw
        } else {
            rectW = vh * cw / ch
            rectH = vh
        }
        if (rectW <= 0L || rectH <= 0L) return null
        val ox = (vw - rectW) / 2
        val oy = (vh - rectH) / 2
        val x = tx.toLong()
        val y = ty.toLong()
        if (x < ox || x >= ox + rectW || y < oy || y >= oy + rectH) return null
        val nx = ((x - ox) * ABS_RANGE / rectW).coerceIn(0L, ABS_MAX.toLong()).toInt()
        val ny = ((y - oy) * ABS_RANGE / rectH).coerceIn(0L, ABS_MAX.toLong()).toInt()
        return nx to ny
    }

    /**
     * The **server's** half of the map: a normalized coordinate onto a desktop pixel, or null when
     * it is out of range (§2.19.3).
     *
     * This client never injects anything, so it never calls this in anger. It is here because the
     * map has two halves that must agree exactly, the vectors pin both, and a sender that cannot
     * say where its coordinate lands cannot be held to them — [viewToContent] alone is only half a
     * proof. It is also what makes the interesting assertion writable: a finger at a given point of
     * a letterboxed view lands on a *named desktop pixel*, checked in one line, in the same
     * arithmetic Rust uses.
     */
    fun absToDesktop(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int>? {
        if (x !in 0..ABS_MAX || y !in 0..ABS_MAX) return null
        val px = x.toLong() * width.toLong() / ABS_RANGE
        val py = y.toLong() * height.toLong() / ABS_RANGE
        return px.coerceIn(0L, (width - 1).coerceAtLeast(0).toLong()).toInt() to
            py.coerceIn(0L, (height - 1).coerceAtLeast(0).toLong()).toInt()
    }

    /**
     * The server's half for a *delta*: a normalized delta scaled to whole desktop pixels,
     * truncating toward zero (§2.19.3). The single-step form — the receiver carries the remainder
     * across events so a drag slower than a pixel per sample is not lost to flooring.
     */
    fun scaleDelta(d: Int, span: Int): Int = (d.toLong() * span.toLong() / ABS_RANGE).toInt()

    /**
     * The width of *one eye's* picture in a stream `config` describes.
     *
     * An `sbs` stream is a double-width frame carrying left|right, and what the viewer sees — and
     * what a coordinate is a fraction of — is one of them. Getting this wrong halves or doubles
     * every horizontal position, which looks like a cursor that tracks at the wrong speed rather
     * than like a bug in a coordinate map.
     */
    fun contentWidthOf(config: PcLinkStreamConfig): Int =
        if (config.isSbs) config.width / 2 else config.width

    /**
     * Scales a normalized delta into the units a *sender* thinks in — the inverse of the server's
     * half, used to turn a touch or mouse delta in view pixels into normalized units.
     *
     * `norm = px * ABS_RANGE / span`, with no rounding: a sub-pixel-per-sample drag is carried by
     * [PcLinkInputSender]'s accumulator, not by rounding here.
     */
    fun normalizeDelta(px: Int, span: Int): Int {
        if (span <= 0) return 0
        val n = px.toLong() * ABS_RANGE / span.toLong()
        return n.coerceIn(-ABS_RANGE, ABS_RANGE).toInt()
    }

    /**
     * Appends [s] as a JSON string literal, escaping exactly what `serde_json` escapes and nothing
     * else.
     *
     * The "nothing else" is the load-bearing half. `org.json` escapes `/` after `<`, and some
     * builds escape non-ASCII into `\uXXXX`; the vectors carry a literal `я`, so either would put
     * different bytes on the wire than the fixture pins — and the text path exists precisely for
     * the characters no key position produces, which are all non-ASCII.
     */
    internal fun appendJsonString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        val hex = Integer.toHexString(c.code)
                        repeat(4 - hex.length) { sb.append('0') }
                        sb.append(hex)
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }
}

/**
 * One input event (§2.19.2). A `input` message carries a list of them, applied in order.
 *
 * [appendTo] is the whole encoding: field order is normative — the `wire` vectors pin every line —
 * and each subclass writes its own, which keeps the order next to the fields it orders.
 */
sealed class PcInputEvent {

    internal abstract fun appendTo(sb: StringBuilder)

    /**
     * Relative pointer motion in normalized content units, the **primary path**: a physical mouse
     * produces deltas, and a finger on the glass is a touchpad — you slide to nudge and lift to
     * reposition. Neither knows nor wants an absolute position.
     */
    data class Move(val dx: Int, val dy: Int) : PcInputEvent() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("{\"t\":\"m\",\"dx\":").append(dx).append(",\"dy\":").append(dy).append('}')
        }
    }

    /**
     * Absolute pointer position, `0..`[PcLinkInputProtocol.ABS_MAX] across the content — the tablet
     * gesture a touchpad cannot express: tap a spot on the picture to put the cursor *there*.
     * Out of range is dropped by the server, so senders map through
     * [PcLinkInputProtocol.viewToContent], which cannot produce one.
     */
    data class MoveAbs(val x: Int, val y: Int) : PcInputEvent() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("{\"t\":\"a\",\"x\":").append(x).append(",\"y\":").append(y).append('}')
        }
    }

    /**
     * A button transition: [b] is `0` left, `1` right, `2` middle, [d] true for press. Press and
     * release are independent so a drag can be expressed — and so whoever is holding one can let go
     * of it when the session ends.
     */
    data class Button(val b: Int, val d: Boolean) : PcInputEvent() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("{\"t\":\"b\",\"b\":").append(b).append(",\"d\":").append(d).append('}')
        }
    }

    /**
     * A wheel movement in [PcLinkInputProtocol.WHEEL_NOTCH] units; `dy` positive scrolls the content
     * up (away from the user), `dx` positive scrolls it right — the Windows sign convention, which
     * is the opposite of the direction an Android scroll axis reports.
     */
    data class Wheel(val dx: Int, val dy: Int) : PcInputEvent() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("{\"t\":\"w\",\"dx\":").append(dx).append(",\"dy\":").append(dy).append('}')
        }
    }

    /**
     * A physical key by its **USB HID usage** (Keyboard/Keypad page `0x07`) — a position, not a
     * letter, so the PC's own layout decides what it types. [PcLinkHidKeys] is how an Android
     * [android.view.KeyEvent] becomes one; a usage outside
     * [PcLinkHidKeys.INJECTABLE] is dropped by the server, so this client never sends one.
     */
    data class Key(val u: Int, val d: Boolean) : PcInputEvent() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("{\"t\":\"k\",\"u\":").append(u).append(",\"d\":").append(d).append('}')
        }
    }

    /**
     * A literal Unicode string, layout-independent — the IME / on-screen-keyboard path, and the only
     * way to type a character no key position produces. Atomic: no press, no release, so it cannot
     * express a chord and cannot leave anything held.
     */
    data class Text(val s: String) : PcInputEvent() {
        override fun appendTo(sb: StringBuilder) {
            sb.append("{\"t\":\"s\",\"s\":")
            PcLinkInputProtocol.appendJsonString(sb, s)
            sb.append('}')
        }
    }
}

/**
 * The `input` object of `hello` (what this client offers) and of `config` (what the server grants),
 * §2.19.1. The two have the same shape and opposite authority: the client's is a hint, the server's
 * is the fact.
 *
 * Unrecognized entries are kept rather than dropped — a future `"pen"` pointer mode is additive, and
 * a receiver MUST ignore what it does not know rather than refuse the message over it.
 */
data class PcInputOffer(
    val pointer: List<String>,
    val keys: List<String>,
    val wheel: Boolean
) {
    val hasRelative: Boolean get() = pointer.contains(PcLinkInputProtocol.POINTER_REL)
    val hasAbsolute: Boolean get() = pointer.contains(PcLinkInputProtocol.POINTER_ABS)
    val hasHidKeys: Boolean get() = keys.contains(PcLinkInputProtocol.KEYS_HID)
    val hasText: Boolean get() = keys.contains(PcLinkInputProtocol.KEYS_TEXT)

    /** The JSON object as `hello`/`config` carry it. */
    fun toJson(): JSONObject = JSONObject()
        .put("pointer", JSONArray().also { a -> pointer.forEach(a::put) })
        .put("keys", JSONArray().also { a -> keys.forEach(a::put) })
        .put("wheel", wheel)

    companion object {
        /**
         * Parses `config.input` / `hello.input`, or null when the field is absent — which is the
         * default and the answer for every plaintext session, every session with the operator
         * switch off, and every server built before §2.19. Absence is not an error; it is the cue
         * to send no input at all.
         *
         * A present-but-empty offer parses to an offer with nothing in it, and reads as "input is
         * live but this server injects nothing" — a distinction the UI never has to make, because
         * every send path checks the specific mode it needs.
         */
        fun parse(obj: JSONObject?): PcInputOffer? {
            if (obj == null) return null
            return PcInputOffer(
                pointer = strings(obj.optJSONArray("pointer")),
                keys = strings(obj.optJSONArray("keys")),
                wheel = obj.optBoolean("wheel", false)
            )
        }

        private fun strings(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotEmpty()) out += s
            }
            return out
        }
    }
}

/**
 * Why this session has no input, for a UI that has to tell the user something they can act on.
 *
 * The two live branches are genuinely different problems with different fixes, and merging them
 * into "input unavailable" is what makes a support thread take three rounds: [NOT_ENCRYPTED] means
 * *re-pair* (the PC is running a build, or a pairing, that predates the sealed control channel), and
 * [OPERATOR_OFF] means *go flip a switch on the PC*. Nothing the phone can do fixes either one, so
 * saying which is the entire help this screen can give.
 */
enum class PcInputUnavailable {
    /** §2.18 encryption was not negotiated, so §2.19 forbids input on this session outright. */
    NOT_ENCRYPTED,

    /** Encrypted, but the PC's operator has not enabled input for this phone. */
    OPERATOR_OFF
}

/** Whether input is live on this session, and if not, why not. */
sealed class PcInputAvailability {
    /** The server granted input; [offer] is what it will act on. */
    data class Live(val offer: PcInputOffer) : PcInputAvailability()

    /** No input on this session; [reason] is what to tell the user. */
    data class Off(val reason: PcInputUnavailable) : PcInputAvailability()

    companion object {
        /**
         * The whole client-side rule, in one place: the server's `config.input` decides, and this
         * phone's own knowledge of whether the link is sealed explains an absence.
         *
         * Deliberately reads `encrypted` from the link rather than from anything the server said:
         * the server does not report why it withheld the offer, and it must not have to — the phone
         * already knows whether its own envelope is engaged (§2.18.7 layer 1), and that is exactly
         * the fact that separates the two answers.
         */
        fun of(offer: PcInputOffer?, encrypted: Boolean): PcInputAvailability = when {
            offer != null -> Live(offer)
            !encrypted -> Off(PcInputUnavailable.NOT_ENCRYPTED)
            else -> Off(PcInputUnavailable.OPERATOR_OFF)
        }
    }
}
