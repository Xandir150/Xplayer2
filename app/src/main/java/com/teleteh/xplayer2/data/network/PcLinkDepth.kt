package com.teleteh.xplayer2.data.network

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * 3D from the remote (`protocol.md` §2.20) and the PC's own stream figures (§2.21).
 *
 * The two 3D settings a person adjusts **by eye** — how strong the stereo is (`divergence`) and where
 * the screen plane sits (`convergence`) — lived only in the desktop window, on a screen the person
 * wearing the glasses is not looking at. This is the wire half of putting them on the phone: the
 * `depth` message the server sends (the **only** source of truth for the sliders), the `set_depth`
 * line the phone sends (a request the server clamps and acknowledges with another `depth`), and the
 * coalescing that turns a finger on a slider into at most ten of those a second.
 *
 * Sans-io and Android-free, so every rule is exercised by JVM unit tests against
 * `app/src/test/resources/pclink_depth_vectors.json` — a verbatim copy of the server's
 * `crates/xpl-proto/test-vectors/depth_vectors.json`, the same bytes the Rust and Swift suites read.
 *
 * Every quantity is an **integer in thousandths**: `20` is 2 % of the frame width. Three languages
 * do not print a float the same way, and thousandths are finer than anyone can see. The phone never
 * converts — it shows the number the window shows and sends the number the slider holds.
 */
object PcLinkDepthProtocol {

    /** `depth.setting` when the operator has switched conversion off in the window. */
    const val SETTING_OFF = "off"

    /**
     * The `set_depth` line (client → server, §2.20.3), terminated by `\n`.
     *
     * Byte-exact against the `wire` vectors, which is why it is built by hand rather than through
     * [JSONObject]: `type`, then `divergence`, then `convergence`, then `reset`, no spaces, and only
     * the fields the request carries — an absent field is "leave that slider alone", so a request
     * that moved one slider must not mention the other. A reset goes out alone: the server ignores
     * values sent alongside `reset: true` (§2.20.3), so writing them would only be noise.
     */
    fun setDepthLine(request: PcDepthRequest): String {
        val sb = StringBuilder(64)
        sb.append("{\"type\":\"set_depth\"")
        if (request.reset) {
            sb.append(",\"reset\":true")
        } else {
            request.divergence?.let { sb.append(",\"divergence\":").append(it) }
            request.convergence?.let { sb.append(",\"convergence\":").append(it) }
        }
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * `depth` (§2.20.2) → [PcDepthState], or null when the message is not one this client can act
     * on — which drops it **whole** (§2's rule for junk in a known type; the `decode` vectors pin
     * every case): a string where an integer belongs, a non-boolean `active`, missing `limits`.
     *
     * Strict about types on purpose. A `"20"` is what a server that formats numbers as text would
     * send, and a `20.0` is what one that lost the integer rule would send; accepting either here
     * would let the phone quietly disagree with the Rust and Swift decoders about the same line.
     * Unknown fields are ignored, and so is an unrecognised `setting` — a future `"hdr"` is
     * informational and changes nothing about the sliders.
     */
    fun parseDepth(obj: JSONObject): PcDepthState? {
        val setting = obj.opt("setting") as? String ?: return null
        val active = obj.opt("active") as? Boolean ?: return null
        val divergence = readInt(obj, "divergence") ?: return null
        val convergence = readInt(obj, "convergence") ?: return null
        val defaults = obj.optJSONObject("defaults")?.let { d ->
            PcDepthValues(
                divergence = readInt(d, "divergence") ?: return null,
                convergence = readInt(d, "convergence") ?: return null
            )
        } ?: return null
        val limits = obj.optJSONObject("limits")?.let { l ->
            PcDepthLimits(
                divergence = readRange(l, "divergence") ?: return null,
                convergence = readRange(l, "convergence") ?: return null
            )
        } ?: return null
        return PcDepthState(
            setting = setting,
            active = active,
            divergence = divergence,
            convergence = convergence,
            defaults = defaults,
            limits = limits
        )
    }

    /**
     * `stats` (§2.21) → [PcStreamStats], or null to drop the message whole: every one of the four
     * figures must be present and a finite JSON number. Any numeric form is accepted — `60` and
     * `59.8` alike, as the spec requires — and a string is not a number even when it spells one.
     * Unknown fields (a future `dropped`) are ignored.
     */
    fun parseStats(obj: JSONObject): PcStreamStats? {
        return PcStreamStats(
            captureFps = readNumber(obj, "capture_fps") ?: return null,
            encodeFps = readNumber(obj, "encode_fps") ?: return null,
            encodeMs = readNumber(obj, "encode_ms") ?: return null,
            wireMbps = readNumber(obj, "wire_mbps") ?: return null
        )
    }

    /**
     * An integer, and only an integer: present, and parsed by the JSON layer as one.
     *
     * Both `org.json` implementations this code runs against (Android's, and JSON-java under the
     * JVM tests) parse a bare `20` to an Integer/Long and anything with a point or an exponent to a
     * floating type, so a whole-valued `20.0` is refused here exactly as `serde_json` refuses it for
     * an `i32`. JSON `null` comes back as [JSONObject.NULL], which is neither.
     */
    private fun readInt(obj: JSONObject, key: String): Int? = when (val v = obj.opt(key)) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
        is BigInteger -> if (v.bitLength() <= 31) v.toInt() else null
        else -> null
    }

    /** A finite JSON number of any form — never a string, never `null`, never NaN/Infinity. */
    private fun readNumber(obj: JSONObject, key: String): Double? {
        val v = obj.opt(key) as? Number ?: return null
        val d = v.toDouble()
        return if (d.isNaN() || d.isInfinite()) null else d
    }

    /** `[min, max]`: exactly two integers, inclusive, in order. Anything else is a content error. */
    private fun readRange(obj: JSONObject, key: String): PcDepthRange? {
        val arr = obj.optJSONArray(key) ?: return null
        if (arr.length() != 2) return null
        val min = intAt(arr, 0) ?: return null
        val max = intAt(arr, 1) ?: return null
        if (min > max) return null
        return PcDepthRange(min, max)
    }

    private fun intAt(arr: JSONArray, index: Int): Int? = when (val v = arr.opt(index)) {
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) v.toInt() else null
        is BigInteger -> if (v.bitLength() <= 31) v.toInt() else null
        else -> null
    }
}

/** The inclusive `[min, max]` the server accepts for one slider; a value outside is clamped there. */
data class PcDepthRange(val min: Int, val max: Int) {
    fun clamp(value: Int): Int = value.coerceIn(min, max)

    /** How far the slider travels. Zero means a slider with nowhere to go. */
    val span: Int get() = max - min
}

/** One pair of the two settings — the current values, or what a reset restores. */
data class PcDepthValues(val divergence: Int, val convergence: Int)

/** The ranges the two sliders are drawn from — carried in every `depth` so they never go stale. */
data class PcDepthLimits(val divergence: PcDepthRange, val convergence: PcDepthRange)

/**
 * The server's `depth` message (§2.20.2): the only source of truth for the phone's 3D controls.
 *
 * A client renders what the last one of these said and assumes nothing about its own `set_depth`
 * until the `depth` acknowledging it arrives — the server clamps, and the window may have moved the
 * same slider a moment earlier.
 *
 * @param setting the operator's mode in the window — `"off"`, `"auto"` (follow the glasses), `"on"`,
 *   or anything a future server says. Informational; the phone cannot change it.
 * @param active whether the server is converting **now**. This, and nothing else, is the cue to show
 *   the sliders: an `"auto"` server whose glasses are in 2D is not converting.
 * @param divergence peak per-eye shift in thousandths of the frame width; `20` is the shipped default.
 * @param convergence where the screen plane sits, in thousandths; `0` leaves the estimator in charge.
 */
data class PcDepthState(
    val setting: String,
    val active: Boolean,
    val divergence: Int,
    val convergence: Int,
    val defaults: PcDepthValues,
    val limits: PcDepthLimits
) {
    /** Whether a reset would change anything — what the Reset button's enabled state follows. */
    val atDefaults: Boolean
        get() = divergence == defaults.divergence && convergence == defaults.convergence

    /** The operator has switched conversion off in the window: the reason there is nothing to adjust. */
    val switchedOff: Boolean get() = !active && setting == PcLinkDepthProtocol.SETTING_OFF
}

/**
 * The PC's own figures for the stream it is sending (§2.21), by the arithmetic its window uses.
 *
 * Informational only — it must not change the client's behaviour beyond what it displays; §3 already
 * has the congestion signal. What it adds is the one number the phone cannot measure: how long the
 * PC's encoder takes, which used to have to be guessed.
 *
 * @param captureFps frames captured from the screen per second. A desktop nobody is touching captures few.
 * @param encodeFps encoded access units sent per second — what the phone should be receiving.
 * @param encodeMs mean encoder time per unit, milliseconds: the PC's own contribution to latency.
 * @param wireMbps bytes sent per second, as megabits.
 */
data class PcStreamStats(
    val captureFps: Double,
    val encodeFps: Double,
    val encodeMs: Double,
    val wireMbps: Double
)

/**
 * One `set_depth` (§2.20.3): a slider or two to move, or a reset. An absent value leaves that slider
 * alone; [reset] restores both to the server's defaults and makes any values moot.
 */
data class PcDepthRequest(
    val divergence: Int? = null,
    val convergence: Int? = null,
    val reset: Boolean = false
)

/**
 * The client half of §2.20.3: turns a finger dragging a slider into `set_depth` lines, coalesced to
 * the latest value at no more than about ten a second.
 *
 * Pure and sans-io, like [PcLinkInputSender] next door — the UI thread calls the verbs, the control
 * writer coroutine calls [drain] on its own clock — so the rules are unit tests rather than things
 * to try on a desktop and hope.
 *
 * ## What is coalesced, and how
 *
 * A drag is a stream of values, and only the latest matters: the server MAY apply only the last of a
 * burst anyway. So values are **latest-wins per field** into one pending entry, and a message carries
 * only the fields that moved — one slider dragged means one field on the wire, never the other
 * slider's value sent back at the server as if the finger had touched it (the window may have moved
 * that one a moment earlier, and re-sending a stale copy would undo it).
 *
 * A **reset is an ordering barrier**: it discards whatever values are still waiting (a reset makes
 * them moot) and goes out as a message of its own, and a value arriving after it queues up *behind*
 * it — so "reset, then nudge" reaches the PC in that order and ends with the nudge, which is what the
 * finger did.
 *
 * ## The rate
 *
 * At most one message per [MIN_INTERVAL_MS]. The first value of a drag goes out at once (the throttle
 * is measured from the previous send, not from the start of the gesture), the rest ride the interval,
 * and the value under the finger at release goes out within one interval of it. The writer asks
 * [dueInMs] how long it may sleep, so nothing waits out its idle tick.
 */
class PcLinkDepthSender(
    /** Nudges the control writer out of its idle sleep; ignored while it is already ticking fast. */
    private val wake: () -> Unit = {},
    private val minIntervalMs: Long = MIN_INTERVAL_MS
) {

    private sealed class Entry {
        class Values(var divergence: Int?, var convergence: Int?) : Entry()
        object Reset : Entry()
    }

    private val lock = Any()
    private val queue = ArrayDeque<Entry>(2)
    private var lastSentAtMs: Long? = null

    /** The strength slider moved: send this value, replacing any the writer has not taken yet. */
    fun divergence(value: Int) {
        synchronized(lock) { values().divergence = value }
        wake()
    }

    /** The convergence slider moved: same rule. */
    fun convergence(value: Int) {
        synchronized(lock) { values().convergence = value }
        wake()
    }

    /**
     * Put both sliders back to the server's defaults. Whatever values were waiting are dropped — the
     * reset supersedes them — and the reset itself is sent alone, as §2.20.3 pins it.
     */
    fun reset() {
        synchronized(lock) {
            queue.clear()
            queue.addLast(Entry.Reset)
        }
        wake()
    }

    /**
     * The next request to put on the wire, or null when nothing is waiting **or** the last send was
     * less than [minIntervalMs] ago — in which case [dueInMs] says how long to wait.
     */
    fun drain(nowMs: Long): PcDepthRequest? {
        synchronized(lock) {
            val head = queue.firstOrNull() ?: return null
            val last = lastSentAtMs
            if (last != null && nowMs - last < minIntervalMs) return null
            queue.removeFirst()
            lastSentAtMs = nowMs
            return when (head) {
                is Entry.Values -> PcDepthRequest(head.divergence, head.convergence)
                Entry.Reset -> PcDepthRequest(reset = true)
            }
        }
    }

    /**
     * How long until something waiting may go out: `0` when [drain] would return it now, or null when
     * nothing is waiting at all — so the writer can sleep its full idle tick.
     */
    fun dueInMs(nowMs: Long): Long? {
        synchronized(lock) {
            if (queue.isEmpty()) return null
            val last = lastSentAtMs ?: return 0L
            return (last + minIntervalMs - nowMs).coerceAtLeast(0L)
        }
    }

    fun hasPending(): Boolean = synchronized(lock) { queue.isNotEmpty() }

    /**
     * Forgets everything waiting *and* the throttle — for a session that has ended. The values were
     * meant for a server that is gone, and the next session's first send should not wait on a clock
     * that belonged to the last one.
     */
    fun discard() {
        synchronized(lock) {
            queue.clear()
            lastSentAtMs = null
        }
    }

    /** The entry a new value coalesces into: the tail if it holds values, else a fresh one behind it. */
    private fun values(): Entry.Values {
        val tail = queue.lastOrNull()
        if (tail is Entry.Values) return tail
        return Entry.Values(null, null).also { queue.addLast(it) }
    }

    companion object {
        /** §2.20.3: "no more than about ten per second". */
        const val MIN_INTERVAL_MS = 100L
    }
}
