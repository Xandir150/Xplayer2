package com.teleteh.xplayer2.data.network

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// Wire types
// ---------------------------------------------------------------------------------------------

/**
 * One decoded XPV1 video frame: an Annex-B access unit plus its flags and presentation timestamp.
 *
 * Not a data class on purpose — [payload] is a ByteArray, whose generated `equals` compares
 * identity; the tests round-trip frames, so value equality is written out by hand.
 */
class PcVideoFrame(val flags: Int, val ptsUs: Long, val payload: ByteArray) {

    val isIdr: Boolean get() = (flags and PcLinkProtocol.FLAG_IDR) != 0

    val hasCodecConfig: Boolean get() = (flags and PcLinkProtocol.FLAG_CODEC_CONFIG) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcVideoFrame) return false
        return flags == other.flags && ptsUs == other.ptsUs && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int =
        (flags * 31 + ptsUs.hashCode()) * 31 + payload.contentHashCode()

    override fun toString(): String = "PcVideoFrame(flags=$flags, ptsUs=$ptsUs, len=${payload.size})"
}

/** One decoder the client advertises in `hello`, ordered by client preference. */
data class PcCodecCapability(
    val mime: String,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int
)

/**
 * The server's `config` message: the format of the video stream that follows, the geometry of the
 * virtual canvas, and the one-shot [videoToken] admitting our video connection.
 */
data class PcLinkStreamConfig(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Float,
    /** `"sbs"` (packed left|right) or `"mono"`. Anything else is a protocol error. */
    val stereo: String,
    val canvasAngularWidthDeg: Float,
    val canvasDistanceM: Float,
    /** 64 lowercase hex chars = the 32 raw token bytes of the video preamble. */
    val videoToken: String
) {
    val isSbs: Boolean get() = stereo == PcLinkProtocol.STEREO_SBS
}

/**
 * The credentials one session authenticates with: this phone's long-term identity and the stored
 * pairings to offer the server's proof against, most-likely-first.
 *
 * Resolved fresh for every connection attempt (see [PcLinkClient]'s `authProvider`) because the
 * store can change underneath a reconnect loop — the user forgetting the PC, or re-pairing it from
 * the connect screen, must take effect on the next attempt rather than at the next app launch.
 */
class PcLinkAuth(
    val identity: PcLinkPairingCrypto.Identity,
    val candidates: List<PcLinkPairing>
)

/** One desktop window on the canvas, from the server's `windows` message. */
data class PcLinkWindow(
    val id: Long,
    val title: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val depth: Float
)

/** A control-channel message we understand. Everything else parses to [Unknown] and is ignored. */
sealed class PcControlMessage {
    data class Config(val config: PcLinkStreamConfig) : PcControlMessage()
    data class Windows(val windows: List<PcLinkWindow>) : PcControlMessage()
    data class Ping(val tUs: Long) : PcControlMessage()
    data class Pong(val tUs: Long) : PcControlMessage()

    /** A known-syntax line whose `type` we don't implement (or a known type with bad fields). */
    object Unknown : PcControlMessage()
}

// ---------------------------------------------------------------------------------------------
// Pure protocol layer (no sockets, no Android UI — directly unit-testable)
// ---------------------------------------------------------------------------------------------

/**
 * Encoding/decoding for the XPlayer PC Link wire protocol v1 (`protocol.md`), kept deliberately
 * free of sockets so every rule below is exercised by JVM unit tests.
 *
 * Mirrors the reference Rust implementation (`crates/xpl-proto`): the same 8 MiB payload cap, the
 * same resync-by-rescanning behaviour ([PcVideoFrameParser]), the same 256 KiB control-line cap
 * ([PcLinkLineSplitter]), the same permissive hex token decode, and the same forward-compatibility
 * rule — unknown message types and unknown fields inside known types are ignored, never fatal.
 */
object PcLinkProtocol {

    /** The only wire protocol version this client speaks. */
    const val PROTOCOL_VERSION = 1

    // --- video framing (§3) ---

    /** Frame magic, "XPV1". */
    val MAGIC = byteArrayOf(0x58, 0x50, 0x56, 0x31)

    /** magic + flags + len + pts_us. */
    const val HEADER_LEN = 17

    /** Payload is an IDR (sync) frame. */
    const val FLAG_IDR = 0x01

    /** Payload starts with codec configuration (VPS/SPS/PPS). */
    const val FLAG_CODEC_CONFIG = 0x02

    /** Maximum accepted payload: frames declaring more are corruption (protocol v1 cap). */
    const val MAX_PAYLOAD_LEN = 8 * 1024 * 1024

    // --- video preamble (§3.1) ---

    /** Video-connection preamble magic, "XPVT" — deliberately distinct from [MAGIC]. */
    val PREAMBLE_MAGIC = byteArrayOf(0x58, 0x50, 0x56, 0x54)

    const val TOKEN_LEN = 32
    const val TOKEN_HEX_LEN = 2 * TOKEN_LEN
    const val PREAMBLE_LEN = 4 + TOKEN_LEN

    // --- control channel (§2) ---

    /** Reference implementation's control line cap; longer lines are dropped. */
    const val MAX_LINE_LEN = 256 * 1024

    const val STEREO_SBS = "sbs"
    const val STEREO_MONO = "mono"

    /**
     * The `hello` line (client → server), terminated by `\n`.
     *
     * Field names are normative — `clientName`, `protocolVersion`, `codecs[].mime/maxWidth/
     * maxHeight/maxFps` — and [codecs] is ordered most-preferred-first.
     */
    fun helloLine(
        clientName: String,
        codecs: List<PcCodecCapability>,
        protocolVersion: Int = PROTOCOL_VERSION
    ): String {
        val arr = JSONArray()
        for (c in codecs) {
            arr.put(
                JSONObject()
                    .put("mime", c.mime)
                    .put("maxWidth", c.maxWidth)
                    .put("maxHeight", c.maxHeight)
                    .put("maxFps", c.maxFps)
            )
        }
        return JSONObject()
            .put("type", "hello")
            .put("clientName", clientName)
            .put("protocolVersion", protocolVersion)
            .put("codecs", arr)
            .toString() + "\n"
    }

    /** The `idr` line (client → server): "make the next frame a sync frame". */
    fun idrLine(): String = JSONObject().put("type", "idr").toString() + "\n"

    /** The `ping` line. [tUs] is our own monotonic clock; the peer echoes it back verbatim. */
    fun pingLine(tUs: Long): String =
        JSONObject().put("type", "ping").put("t_us", tUs).toString() + "\n"

    /** The `pong` reply, echoing the peer's [tUs] unchanged (it is never interpreted). */
    fun pongLine(tUs: Long): String =
        JSONObject().put("type", "pong").put("t_us", tUs).toString() + "\n"

    /**
     * Parses one control line. Malformed JSON returns null (a protocol error the caller may treat
     * as fatal); a well-formed line whose `type` we don't know — or a known type with missing or
     * non-numeric fields — returns [PcControlMessage.Unknown], which callers ignore.
     */
    fun parseControlLine(line: String): PcControlMessage? {
        val obj = try { JSONObject(line.trim()) } catch (_: Exception) { return null }
        return when (obj.optString("type")) {
            "config" -> parseConfig(obj)?.let { PcControlMessage.Config(it) } ?: PcControlMessage.Unknown
            "windows" -> parseWindows(obj)?.let { PcControlMessage.Windows(it) }
                ?: PcControlMessage.Unknown
            "ping" -> readU64(obj, "t_us")?.let { PcControlMessage.Ping(it) } ?: PcControlMessage.Unknown
            "pong" -> readU64(obj, "t_us")?.let { PcControlMessage.Pong(it) } ?: PcControlMessage.Unknown
            else -> PcControlMessage.Unknown
        }
    }

    /**
     * `config` → [PcLinkStreamConfig], or null if a required field is missing/invalid. Required:
     * a non-empty `mime`, positive `width`/`height`, a finite `fps`, a known `stereo`, and a
     * syntactically valid `videoToken` (§2.2 makes the token mandatory). Canvas geometry is
     * optional and defaults when *absent* — but a canvas field that is present and not a finite
     * number fails the message like any other, per §2.
     */
    fun parseConfig(obj: JSONObject): PcLinkStreamConfig? {
        val mime = obj.optString("mime").trim()
        if (mime.isEmpty()) return null
        val width = obj.optInt("width", -1)
        val height = obj.optInt("height", -1)
        if (width <= 0 || height <= 0) return null
        val fps = readFinite(obj, "fps") ?: return null
        val stereo = obj.optString("stereo").trim().lowercase()
        if (stereo != STEREO_SBS && stereo != STEREO_MONO) return null
        val token = obj.optString("videoToken").trim()
        if (decodeToken(token) == null) return null
        // Absent is fine and defaults; present-but-unusable is not (§2 forward compatibility).
        val canvasWidthDeg = readOptionalFinite(obj, "canvasAngularWidthDeg", 45f) ?: return null
        val canvasDistanceM = readOptionalFinite(obj, "canvasDistanceM", 3f) ?: return null
        return PcLinkStreamConfig(
            mime = mime,
            width = width,
            height = height,
            fps = fps,
            stereo = stereo,
            canvasAngularWidthDeg = canvasWidthDeg,
            canvasDistanceM = canvasDistanceM,
            videoToken = token
        )
    }

    /** Null when any window carries an unusable `depth` — the whole message is then dropped. */
    private fun parseWindows(obj: JSONObject): List<PcLinkWindow>? {
        val arr = obj.optJSONArray("windows") ?: return emptyList()
        val out = ArrayList<PcLinkWindow>(arr.length())
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            out += PcLinkWindow(
                id = w.optLong("id", -1L),
                title = w.optString("title"),
                x = w.optInt("x", 0),
                y = w.optInt("y", 0),
                w = w.optInt("w", 0),
                h = w.optInt("h", 0),
                depth = readOptionalFinite(w, "depth", 0.5f) ?: return null
            )
        }
        return out
    }

    /** A number field that must be present and finite (§2: senders must not emit NaN/Infinity). */
    private fun readFinite(obj: JSONObject, key: String): Float? {
        if (!obj.has(key)) return null
        val v = obj.optDouble(key, Double.NaN)
        if (v.isNaN() || v.isInfinite()) return null
        return v.toFloat()
    }

    /**
     * An optional number: [fallback] when the field is absent, its value when present and finite,
     * and null when present but not a finite number.
     *
     * That last case is the point. §2 makes a non-finite or non-numeric value where a number is
     * specified a protocol error whose message MUST be dropped — so it cannot be quietly replaced
     * by the default, which is what a plain `readFinite(...) ?: fallback` did: a `config` claiming
     * `"canvasDistanceM": "NaN"` would have been rendered at 3 m as though the server had never
     * mentioned it. Only *absence* may default.
     */
    private fun readOptionalFinite(obj: JSONObject, key: String, fallback: Float): Float? {
        if (!obj.has(key)) return fallback
        return readFinite(obj, key)
    }

    private fun readU64(obj: JSONObject, key: String): Long? {
        if (!obj.has(key)) return null
        val v = obj.opt(key)
        return when (v) {
            is Number -> v.toLong()
            else -> null
        }
    }

    /**
     * Decodes a `videoToken` from its 64-char hex form into the 32 raw bytes carried by the video
     * preamble, or null when it isn't exactly [TOKEN_HEX_LEN] hex digits. Uppercase digits are
     * accepted (the reference decoder accepts `[0-9a-fA-F]`) even though servers must emit
     * lowercase.
     */
    fun decodeToken(hex: String): ByteArray? {
        if (hex.length != TOKEN_HEX_LEN) return null
        val out = ByteArray(TOKEN_LEN)
        for (i in 0 until TOKEN_LEN) {
            val hi = nibble(hex[2 * i]) ?: return null
            val lo = nibble(hex[2 * i + 1]) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun nibble(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> null
    }

    /**
     * The 36 bytes a video connection must open with: `"XPVT"` + the raw token bytes. Returns null
     * for a token that isn't valid hex — nothing should be sent on the video socket then.
     */
    fun videoPreamble(tokenHex: String): ByteArray? {
        val token = decodeToken(tokenHex) ?: return null
        val out = ByteArray(PREAMBLE_LEN)
        System.arraycopy(PREAMBLE_MAGIC, 0, out, 0, PREAMBLE_MAGIC.size)
        System.arraycopy(token, 0, out, PREAMBLE_MAGIC.size, token.size)
        return out
    }

    /** Encodes one frame (used by tests and any future sender). */
    fun encodeFrame(flags: Int, ptsUs: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LEN + payload.size)
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.size)
        out[4] = flags.toByte()
        val len = payload.size
        out[5] = (len ushr 24).toByte()
        out[6] = (len ushr 16).toByte()
        out[7] = (len ushr 8).toByte()
        out[8] = len.toByte()
        for (i in 0 until 8) out[9 + i] = (ptsUs ushr (56 - 8 * i)).toByte()
        System.arraycopy(payload, 0, out, HEADER_LEN, payload.size)
        return out
    }
}

/**
 * Incremental XPV1 frame parser — a byte-for-byte port of the reference `FrameDecoder`
 * (`xpl-proto/src/framing.rs`), which is the normative behaviour:
 *
 * * feed arbitrary chunks (TCP splits frames anywhere, including mid-magic) and drain with
 *   [nextFrame] until it returns null;
 * * bytes that aren't part of a well-formed frame are skipped while scanning forward for the next
 *   magic, keeping a partial magic at the buffer's tail in case the rest arrives next;
 * * a header whose `len` exceeds the cap is corruption: skip exactly ONE byte and rescan (so a
 *   valid frame later in the stream is still found), and never buffer for the claimed length;
 * * [skippedBytes] counts everything discarded, for diagnostics.
 *
 * Pure: no sockets, no Android types.
 */
class PcVideoFrameParser(private val maxPayloadLen: Int = PcLinkProtocol.MAX_PAYLOAD_LEN) {

    private var buf = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var pos = 0

    /** Total bytes discarded so far while resynchronizing. */
    var skippedBytes: Long = 0L
        private set

    /** Bytes buffered but not yet consumed. */
    val pendingBytes: Int get() = size - pos

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) return
        if (pos > 0) {
            // Drop the consumed prefix (the Rust decoder's `buf.drain(..pos)`).
            System.arraycopy(buf, pos, buf, 0, size - pos)
            size -= pos
            pos = 0
            if (size == 0 && buf.size > INITIAL_CAPACITY) buf = ByteArray(INITIAL_CAPACITY)
        }
        ensureCapacity(size + length)
        System.arraycopy(data, offset, buf, size, length)
        size += length
    }

    /** The next complete frame, or null when more bytes are needed. Call until it returns null. */
    fun nextFrame(): PcVideoFrame? {
        while (true) {
            val avail = size - pos
            if (avail < PcLinkProtocol.MAGIC.size) return null

            if (!magicAt(pos)) {
                val offset = findMagic(avail)
                if (offset >= 0) {
                    skip(offset)
                    continue
                }
                // Everything up to a possible partial magic at the very end is garbage.
                skip(avail - partialMagicSuffix(avail))
                return null
            }

            if (avail < PcLinkProtocol.HEADER_LEN) return null

            val len = readU32(pos + 5)
            if (len > maxPayloadLen.toLong()) {
                // Corrupt or hostile length: drop this magic byte and rescan.
                skip(1)
                continue
            }
            val payloadLen = len.toInt()
            if (avail < PcLinkProtocol.HEADER_LEN + payloadLen) return null

            val flags = buf[pos + 4].toInt() and 0xFF
            val ptsUs = readU64(pos + 9)
            val start = pos + PcLinkProtocol.HEADER_LEN
            val payload = buf.copyOfRange(start, start + payloadLen)
            pos = start + payloadLen
            return PcVideoFrame(flags, ptsUs, payload)
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buf.size) return
        var cap = buf.size
        while (cap < needed) cap = cap shl 1
        buf = buf.copyOf(cap)
    }

    private fun magicAt(index: Int): Boolean {
        val m = PcLinkProtocol.MAGIC
        for (i in m.indices) if (buf[index + i] != m[i]) return false
        return true
    }

    /** Index (relative to [pos], always >= 1) of the next full magic, or -1. */
    private fun findMagic(avail: Int): Int {
        val m = PcLinkProtocol.MAGIC
        var i = 1
        val last = avail - m.size
        while (i <= last) {
            if (magicAt(pos + i)) return i
            i++
        }
        return -1
    }

    /** Length (0..3) of the longest strict suffix that is a prefix of the magic — keep those. */
    private fun partialMagicSuffix(avail: Int): Int {
        val m = PcLinkProtocol.MAGIC
        for (keep in m.size - 1 downTo 1) {
            if (avail < keep) continue
            var ok = true
            for (i in 0 until keep) {
                if (buf[pos + avail - keep + i] != m[i]) { ok = false; break }
            }
            if (ok) return keep
        }
        return 0
    }

    private fun skip(n: Int) {
        pos += n
        skippedBytes += n.toLong()
    }

    private fun readU32(index: Int): Long =
        ((buf[index].toLong() and 0xFF) shl 24) or
            ((buf[index + 1].toLong() and 0xFF) shl 16) or
            ((buf[index + 2].toLong() and 0xFF) shl 8) or
            (buf[index + 3].toLong() and 0xFF)

    private fun readU64(index: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[index + i].toLong() and 0xFF)
        return v
    }

    private companion object {
        const val INITIAL_CAPACITY = 64 * 1024
    }
}

/**
 * Incremental newline splitter for the NDJSON control stream — port of the reference
 * `LineSplitter`. Lines longer than [maxLineLen] are discarded whole (and counted as soon as they
 * are known to be over-long) rather than buffered, so a peer that never sends a newline can't grow
 * our heap; invalid UTF-8 lines are dropped the same way. Pure.
 */
class PcLinkLineSplitter(private val maxLineLen: Int = PcLinkProtocol.MAX_LINE_LEN) {

    private var buf = ByteArray(4096)
    private var size = 0
    private var discarding = false

    var droppedLines: Long = 0L
        private set

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        if (length <= 0) return
        if (size + length > buf.size) {
            var cap = buf.size
            while (cap < size + length) cap = cap shl 1
            buf = buf.copyOf(cap)
        }
        System.arraycopy(data, offset, buf, size, length)
        size += length
    }

    /** The next complete line without its terminator, or null when one hasn't arrived yet. */
    fun nextLine(): String? {
        while (true) {
            if (discarding) {
                val nl = indexOfNewline()
                if (nl < 0) { size = 0; return null }
                drain(nl + 1)
                discarding = false
                continue
            }
            val nl = indexOfNewline()
            if (nl < 0) {
                if (size > maxLineLen) {
                    // Already over the cap with no newline in sight: it is lost from here on, so
                    // count it now rather than when (or if) its newline ever arrives.
                    size = 0
                    discarding = true
                    droppedLines++
                }
                return null
            }
            if (nl > maxLineLen) {
                drain(nl + 1)
                droppedLines++
                continue
            }
            val raw = buf.copyOfRange(0, nl + 1)
            drain(nl + 1)
            val text = try {
                String(raw, Charsets.UTF_8).let {
                    // String(UTF_8) substitutes U+FFFD instead of failing, so validate explicitly:
                    // a line that doesn't round-trip wasn't valid UTF-8.
                    if (it.toByteArray(Charsets.UTF_8).contentEquals(raw)) it else null
                }
            } catch (_: Exception) {
                null
            }
            if (text == null) { droppedLines++; continue }
            return text.trimEnd('\n', '\r')
        }
    }

    private fun indexOfNewline(): Int {
        for (i in 0 until size) if (buf[i] == '\n'.code.toByte()) return i
        return -1
    }

    private fun drain(count: Int) {
        System.arraycopy(buf, count, buf, 0, size - count)
        size -= count
    }
}

// ---------------------------------------------------------------------------------------------
// Connection orchestrator
// ---------------------------------------------------------------------------------------------

/** What the UI needs to know about the link, reported on [Dispatchers.Main]. */
sealed class PcLinkState {
    /** Opening the control connection / waiting for `config`. */
    object Connecting : PcLinkState()

    /** Video is flowing (or about to: the preamble was accepted). */
    data class Streaming(val config: PcLinkStreamConfig) : PcLinkState()

    /** The session dropped; another attempt starts in [retryInMs]. */
    data class Reconnecting(val attempt: Int, val retryInMs: Long, val reason: String) : PcLinkState()

    /** Gave up after [PcLinkClient.MAX_RETRY_WINDOW_MS] of failures. Terminal. */
    data class Failed(val reason: String) : PcLinkState()

    /**
     * The PC refused our stored pairing. Terminal *without* retrying: another TCP connection would
     * present the same key and be refused the same way, and each attempt spends the server's
     * per-IP auth budget (design §8.5).
     *
     * [reason] is what the UI must branch on, and only two branches matter:
     * [PairingFailure.UNKNOWN_TO_PC] may offer a fresh ceremony behind an explicit tap, everything
     * else — [PairingFailure.AUTH_FAILED] above all — must not (§8.4).
     */
    data class AuthFailed(val reason: PairingFailure) : PcLinkState()
}

/**
 * The PC Link client: owns the control (NDJSON) and video (XPV1) TCP connections for one session
 * and keeps re-establishing them while it runs.
 *
 * Shape of a session (protocol.md §4): connect control → `hello` → [authenticate, when paired] →
 * wait for `config` → connect video → 36-byte `"XPVT"` + token preamble → frames. Because the token
 * is single-use and bound to the control connection that issued it, losing *either* connection
 * restarts the whole session (fresh control handshake ⇒ fresh token) rather than reconnecting the
 * video socket alone — and, on a paired PC, that means a fresh authentication too: the protocol
 * allows exactly one attempt per TCP connection.
 *
 * Threading: everything runs on [Dispatchers.IO] under the scope passed to [connect], so
 * cancelling that scope (or calling [close]) tears the session down. [Listener.onState] is
 * dispatched on [Dispatchers.Main]; [Listener.onVideoFrame] is deliberately NOT — it is called on
 * the video reader coroutine so an access unit reaches the decoder without a main-thread hop.
 */
class PcLinkClient(
    context: Context?,
    private val host: String,
    private val controlPort: Int,
    private val videoPort: Int,
    private val listener: Listener,
    private val clientName: String = Build.MODEL ?: "XPlayer2",
    /** Injectable for tests; defaults to what this device's MediaCodecs can actually decode. */
    private val codecs: List<PcCodecCapability> = deviceCodecs(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    /**
     * The credentials for the next connection attempt, or null to speak the unauthenticated M1
     * flow. Called on [Dispatchers.IO] immediately after `hello`, once per attempt — so it may
     * touch the pairing store, and a pairing that appeared or was forgotten since the last attempt
     * is honoured by the next one.
     */
    private val authProvider: () -> PcLinkAuth? = { null }
) {

    interface Listener {
        /** Connection lifecycle, on the main thread. */
        fun onState(state: PcLinkState)

        /** A new stream format. Main thread, always before the first frame that uses it. */
        fun onConfig(config: PcLinkStreamConfig)

        /** One access unit, on the video reader coroutine (NOT the main thread). */
        fun onVideoFrame(frame: PcVideoFrame)
    }

    private val appContext: Context? = context?.applicationContext

    /** Total payload+header bytes read off the video socket, for the bitrate readout. */
    val videoBytes = AtomicLong(0)

    /** Frames handed to [Listener.onVideoFrame]. */
    val videoFrames = AtomicLong(0)

    /** Bytes discarded by the frame parser resyncing — non-zero means a corrupt stream. */
    val resyncBytes = AtomicLong(0)

    /** Round-trip time of the most recent ping/pong pair, microseconds (0 = none yet). */
    @Volatile var lastRttUs: Long = 0L
        private set

    private var job: Job? = null

    // Sockets of the session in flight. Held so close()/cancellation can break a blocked read
    // instead of waiting out its timeout.
    @Volatile private var controlSocket: Socket? = null
    @Volatile private var videoSocket: Socket? = null

    // Set by requestIdr(); the control writer drains it on its next tick. A flag rather than a
    // queue: five "please resync" requests in a row still mean one IDR.
    @Volatile private var idrRequested = false

    // Whatever pong we owe the peer (its t_us, echoed verbatim), or null.
    @Volatile private var pendingPong: Long? = null

    /** Starts the session loop in [scope]. Call once. */
    fun connect(scope: CoroutineScope): Job {
        val started = scope.launch(Dispatchers.IO) { runSessions() }
        job = started
        return started
    }

    /** Tears everything down; the client is not reusable afterwards. */
    fun close() {
        job?.cancel()
        job = null
        closeSockets()
    }

    /** Asks the server for a sync frame (after a resync, a decoder reset, or a fresh surface). */
    fun requestIdr() {
        idrRequested = true
    }

    private fun closeSockets() {
        try { controlSocket?.close() } catch (_: Throwable) { }
        try { videoSocket?.close() } catch (_: Throwable) { }
        controlSocket = null
        videoSocket = null
    }

    /**
     * Reconnect policy: retry with exponential backoff (0.5 s → 5 s) for as long as failures keep
     * coming, and surface a terminal error once they have been continuous for
     * [MAX_RETRY_WINDOW_MS]. A session that actually streamed resets both the backoff and the
     * window, so a PC that drops once an hour never accumulates its way to "failed".
     */
    private suspend fun runSessions() {
        var attempt = 0
        var failingSince = 0L
        while (currentCoroutineContext().isActive) {
            emitState(PcLinkState.Connecting)
            var reason = "disconnected"
            try {
                runSession()
            } catch (ce: CancellationException) {
                throw ce
            } catch (a: AuthRefusedException) {
                // Not a connectivity problem: the PC looked at our key and said no. Retrying can
                // only repeat the answer, so this ends the client and the UI takes over.
                emitState(PcLinkState.AuthFailed(a.failure))
                return
            } catch (t: Throwable) {
                reason = t.message ?: t.javaClass.simpleName
            } finally {
                closeSockets()
            }
            if (!currentCoroutineContext().isActive) return

            // "It worked" means frames actually arrived AND the session lasted: a server that
            // accepts the video connection and drops it a moment later (spent token, another
            // client holding the stream) must still reach the error state instead of retrying
            // twice a second forever.
            if (sessionStreamed && nowMs() - sessionStartMs >= MIN_GOOD_SESSION_MS) {
                attempt = 0
                failingSince = 0L
            }
            if (failingSince == 0L) failingSince = nowMs()
            if (nowMs() - failingSince >= MAX_RETRY_WINDOW_MS) {
                emitState(PcLinkState.Failed(reason))
                return
            }
            val backoff = min(
                RECONNECT_MAX_DELAY_MS,
                RECONNECT_BASE_DELAY_MS shl min(attempt, 8)
            )
            attempt++
            emitState(PcLinkState.Reconnecting(attempt, backoff, reason))
            delay(backoff)
        }
    }

    /**
     * One full session. Sets [sessionStreamed] once video actually started (which resets the
     * backoff); throws on any failure, and returns normally only when the caller cancelled us.
     */
    private suspend fun runSession() {
        sessionStreamed = false
        sessionStartMs = nowMs()
        idrRequested = false
        pendingPong = null
        unansweredPings = 0
        lastRxMs = nowMs()

        // Resolved BEFORE the socket, not after `hello`: this reads the pairing store, and on the
        // first call of a session that can mean unsealing a Keystore key. A server running with
        // `--allow-unpaired` only holds its unpaired grace open briefly after `hello`, so a slow
        // store read used to be enough to land us on the interim unpaired path — where our late
        // `auth_challenge` is ignored and we spend the full authentication timeout waiting for a
        // reply that isn't coming. Whether we authenticate must be decided before we say anything.
        val auth = authProvider()

        val control = openSocket(controlPort, CONTROL_READ_TIMEOUT_MS)
        controlSocket = control
        try {
            val input = control.getInputStream()
            val output = control.getOutputStream()
            writeLine(output, PcLinkProtocol.helloLine(clientName, effectiveCodecs()))

            val splitter = PcLinkLineSplitter()

            // Paired PC: authenticate before anything else. A server implementing §2.6 sends no
            // `config` at all until it has, and §2.2 forbids opening the video connection first.
            val authToken = if (auth == null) null else authenticate(auth, input, output, splitter)

            val config = awaitConfig(input, splitter)
            withContext(Dispatchers.Main) { listener.onConfig(config) }

            // §2.2's rule is "whichever message most recently carried one". This reads as "config
            // wins", and the two coincide: `config` always follows `auth_ok` and its token field is
            // mandatory, and the only other way to be issued one — asking with `video_token`
            // (§2.15) — is something this client never does. So `config`'s token IS the most recent
            // one, whether the server repeated the still-unspent `auth_ok` token (the same token,
            // not a reuse) or minted a fresh one that supersedes it. `authToken` covers only the
            // case of a `config` arriving without a token of its own. Should this client ever send
            // `video_token`, that reply becomes the most recent and this has to become a slot the
            // later message overwrites rather than a two-way fallback.
            val videoToken = config.videoToken.ifEmpty { authToken.orEmpty() }
            val preamble = PcLinkProtocol.videoPreamble(videoToken)
                ?: throw IOException("server sent an invalid videoToken")
            val video = openSocket(videoPort, VIDEO_READ_TIMEOUT_MS)
            videoSocket = video
            video.getOutputStream().apply { write(preamble); flush() }

            lastRxMs = nowMs()
            emitState(PcLinkState.Streaming(config))

            // Any child ending (EOF throws) cancels the siblings and unwinds the session.
            coroutineScope {
                launch { readControl(input, splitter) }
                launch { writeControl(output) }
                launch { readVideo(video.getInputStream()) }
            }
        } finally {
            closeSockets()
        }
    }

    /**
     * Runs the reconnect-authentication exchange (§2.11–§2.14) on the control socket and returns
     * the `auth_ok` video token (null when the server sent none — `config` still carries one).
     *
     * Drives the very same [PairingSession] the pairing screen uses, in its `authenticate()` mode:
     * [PairingEffect.Send] effects become lines, received lines go to [PairingSession.onLine], and
     * the socket's poll timeout is what lets [PairingSession.onTick] fire the step deadlines. There
     * is no user in this branch, so there is no [PairingEffect.ShowSas] or [PairingEffect.Persist]
     * to service — which is exactly why the player can do this behind a spinner.
     *
     * Failures split by whether another connection could plausibly do better: a dropped link or a
     * silent server is an [IOException] and goes back through the normal reconnect backoff, while
     * an answer — `unknown_client`, `bad_proof`, a proof that didn't verify — is an
     * [AuthRefusedException] that ends the client.
     */
    private suspend fun authenticate(
        auth: PcLinkAuth,
        input: InputStream,
        output: OutputStream,
        splitter: PcLinkLineSplitter
    ): String? {
        val session = PairingSession.authenticate(
            identity = auth.identity,
            candidates = auth.candidates,
            protocolVersion = PcLinkProtocol.PROTOCOL_VERSION,
            clock = nowMs
        )
        val buf = ByteArray(READ_BUFFER)
        var outcome = applyAuthEffects(session.start(), output)

        while (outcome == null && currentCoroutineContext().isActive) {
            // Lines the previous read left buffered first — the server may well have packed
            // `auth_response` and everything after it into one TCP segment.
            outcome = drainAuthLines(session, splitter, output)
            if (outcome != null) break
            outcome = applyAuthEffects(session.onTick(), output)
            if (outcome != null) break

            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                // Just the poll interval expiring: loop round so onTick() sees the clock.
                continue
            }
            if (n < 0) {
                outcome = applyAuthEffects(session.onDisconnected(), output)
                    ?: PairingOutcome.Failure(PairingFailure.CONNECTION_LOST)
                break
            }
            splitter.feed(buf, 0, n)
        }

        return when (val result = outcome) {
            is PairingOutcome.Success -> result.videoToken
            is PairingOutcome.Failure -> throw authFailure(result.reason)
            // Only reachable by cancellation, which unwinds through the scope anyway.
            null -> throw CancellationException("cancelled")
        }
    }

    private fun drainAuthLines(
        session: PairingSession,
        splitter: PcLinkLineSplitter,
        output: OutputStream
    ): PairingOutcome? {
        while (true) {
            val line = splitter.nextLine() ?: return null
            if (line.isBlank()) continue
            applyAuthEffects(session.onLine(line), output)?.let { return it }
        }
    }

    /** Performs one batch of effects in order; returns the outcome once the session is done. */
    private fun applyAuthEffects(effects: List<PairingEffect>, output: OutputStream): PairingOutcome? {
        var outcome: PairingOutcome? = null
        for (effect in effects) {
            when (effect) {
                is PairingEffect.Send -> writeLine(output, effect.line + "\n")
                is PairingEffect.Finished -> outcome = effect.outcome
                // The caller's `finally` closes both sockets on the way out; closing here as well
                // would only race it. ShowSas/Persist belong to the pairing ceremony, which this
                // client never runs.
                else -> Unit
            }
        }
        return outcome
    }

    /**
     * Which failures end the client and which go back through the reconnect loop. Only the two
     * "nothing answered" cases are worth another TCP connection; every reason the PC actually
     * *gave* us would be given again.
     */
    private fun authFailure(reason: PairingFailure): Throwable = when (reason) {
        PairingFailure.CONNECTION_LOST -> IOException("control connection closed during authentication")
        PairingFailure.TIMEOUT -> IOException("no authentication reply within " +
            "${PairingSession.AUTH_TIMEOUT_MS} ms")
        else -> AuthRefusedException(reason)
    }

    /** A terminal authentication answer, carried out to [runSessions] to become an error state. */
    private class AuthRefusedException(val failure: PairingFailure) :
        IOException("authentication refused: $failure")

    /** Waits out the `hello` → `config` exchange, ignoring anything else the server says first. */
    private suspend fun awaitConfig(input: InputStream, splitter: PcLinkLineSplitter): PcLinkStreamConfig {
        val buf = ByteArray(READ_BUFFER)
        val deadline = nowMs() + HANDSHAKE_TIMEOUT_MS
        while (currentCoroutineContext().isActive) {
            // Before reading: on an authenticated session `config` follows `auth_ok` closely enough
            // to share a TCP segment, so it is often already sitting in the splitter by now.
            while (true) {
                val line = splitter.nextLine() ?: break
                when (val msg = PcLinkProtocol.parseControlLine(line)) {
                    is PcControlMessage.Config -> return msg.config
                    // Malformed JSON is a protocol error (§2) — the server is not speaking v1.
                    null -> throw IOException("malformed control JSON")
                    // ping before config is legal; answer it on the next writer tick.
                    is PcControlMessage.Ping -> pendingPong = msg.tUs
                    else -> Unit
                }
            }
            if (nowMs() > deadline) throw IOException("no config within ${HANDSHAKE_TIMEOUT_MS} ms")
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) throw IOException("control connection closed during handshake")
            splitter.feed(buf, 0, n)
        }
        throw CancellationException("cancelled")
    }

    private suspend fun readControl(input: InputStream, splitter: PcLinkLineSplitter) {
        val buf = ByteArray(READ_BUFFER)
        while (currentCoroutineContext().isActive) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) throw IOException("control connection closed")
            lastRxMs = nowMs()
            splitter.feed(buf, 0, n)
            while (true) {
                val line = splitter.nextLine() ?: break
                when (val msg = PcLinkProtocol.parseControlLine(line)) {
                    null -> throw IOException("malformed control JSON")
                    is PcControlMessage.Ping -> pendingPong = msg.tUs
                    is PcControlMessage.Pong -> {
                        lastRttUs = (nowMs() * 1000L - msg.tUs).coerceAtLeast(0L)
                        unansweredPings = 0
                    }
                    is PcControlMessage.Config -> {
                        // A format change mid-session: the server keeps our video connection and
                        // follows this with a codec-config frame, so we only re-arm the decoder.
                        withContext(Dispatchers.Main) { listener.onConfig(msg.config) }
                        emitState(PcLinkState.Streaming(msg.config))
                    }
                    // `windows` (per-window depth) is M2 — ignored for now, like any unknown type.
                    // When M2 starts rendering these: `title` is peer-supplied, but it arrives only
                    // after `auth_ok`, so it comes from the PC the user approved against a 6-digit
                    // code — not a spoofable string like an unauthenticated discovery reply, and
                    // distrusting it would only mangle legitimate window titles. What it does want
                    // is rendering hygiene: a length cap, control characters stripped, no markup.
                    else -> Unit
                }
            }
        }
    }

    /**
     * Keep-alive + outbound requests. Pings every [PING_INTERVAL_MS], answers the peer's pings,
     * flushes IDR requests, and declares the peer dead per §2.5 (no traffic for
     * [LIVENESS_TIMEOUT_MS] with at least two unanswered pings).
     */
    private suspend fun writeControl(output: OutputStream) {
        var nextPingAt = nowMs() + PING_INTERVAL_MS
        while (currentCoroutineContext().isActive) {
            pendingPong?.let { t ->
                pendingPong = null
                writeLine(output, PcLinkProtocol.pongLine(t))
            }
            if (idrRequested) {
                idrRequested = false
                writeLine(output, PcLinkProtocol.idrLine())
            }
            if (nowMs() >= nextPingAt) {
                nextPingAt = nowMs() + PING_INTERVAL_MS
                unansweredPings++
                writeLine(output, PcLinkProtocol.pingLine(nowMs() * 1000L))
            }
            if (nowMs() - lastRxMs > LIVENESS_TIMEOUT_MS && unansweredPings >= 2) {
                throw IOException("server stopped responding")
            }
            delay(WRITER_TICK_MS)
        }
    }

    /** Reads the video socket into the frame parser and pushes access units to the listener. */
    private suspend fun readVideo(input: InputStream) {
        val parser = PcVideoFrameParser()
        val buf = ByteArray(VIDEO_READ_BUFFER)
        var lastSkipped = 0L
        while (currentCoroutineContext().isActive) {
            val n = try {
                input.read(buf)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (n < 0) throw IOException("video connection closed")
            lastRxMs = nowMs()
            videoBytes.addAndGet(n.toLong())
            parser.feed(buf, 0, n)
            while (true) {
                val frame = parser.nextFrame() ?: break
                videoFrames.incrementAndGet()
                sessionStreamed = true
                listener.onVideoFrame(frame)
            }
            if (parser.skippedBytes != lastSkipped) {
                // Resynced past corruption: whatever follows is likely a broken GOP, so ask for a
                // sync frame and let the decoder reject the rest (protocol.md §3, receiver rules).
                resyncBytes.addAndGet(parser.skippedBytes - lastSkipped)
                lastSkipped = parser.skippedBytes
                requestIdr()
            }
        }
    }

    // Session bookkeeping, reset by runSession(): when the peer last sent anything (either
    // channel), how many pings it hasn't answered, and whether video ever started (which is what
    // resets the reconnect backoff).
    @Volatile private var lastRxMs = 0L
    @Volatile private var unansweredPings = 0
    @Volatile private var sessionStreamed = false
    @Volatile private var sessionStartMs = 0L

    private fun effectiveCodecs(): List<PcCodecCapability> =
        codecs.ifEmpty { listOf(PcCodecCapability(MIME_AVC, 1920, 1080, 60)) }

    private suspend fun emitState(state: PcLinkState) {
        withContext(Dispatchers.Main) { listener.onState(state) }
    }

    private fun writeLine(output: OutputStream, line: String) {
        output.write(line.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /**
     * Opens one TCP connection, pinned to the LAN network for the same reason discovery pins its
     * UDP socket (an always-on VPN or an internet-less Wi-Fi otherwise routes us off the LAN).
     * [readTimeoutMs] keeps blocking reads interruptible so cancellation lands promptly.
     */
    private fun openSocket(port: Int, readTimeoutMs: Int): Socket {
        val socket = Socket()
        try {
            bindToLocalNetwork(socket)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = readTimeoutMs
            return socket
        } catch (t: Throwable) {
            try { socket.close() } catch (_: Throwable) { }
            throw t
        }
    }

    /** Best-effort; see PcLinkDiscovery.bindToLocalNetwork for the full why. */
    private fun bindToLocalNetwork(socket: Socket) {
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val network = pickLanNetwork(cm) ?: return
        try {
            network.bindSocket(socket)
        } catch (_: Exception) {
            // Network vanished between the snapshot and the bind — the unbound socket still works
            // on a normal single-network phone.
        }
    }

    @Suppress("DEPRECATION") // allNetworks: a synchronous snapshot is what a one-shot bind needs
    private fun pickLanNetwork(cm: ConnectivityManager): Network? {
        val networks = try { cm.allNetworks } catch (_: Exception) { return null }
        var ethernet: Network? = null
        for (network in networks) {
            val caps = try { cm.getNetworkCapabilities(network) } catch (_: Exception) { null } ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network
            if (ethernet == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                ethernet = network
            }
        }
        return ethernet
    }

    companion object {
        const val MIME_HEVC = "video/hevc"
        const val MIME_AVC = "video/avc"

        const val CONNECT_TIMEOUT_MS = 5000
        const val HANDSHAKE_TIMEOUT_MS = 5000L

        /** §2.5: ping every few seconds so a silent link is noticed. */
        const val PING_INTERVAL_MS = 3000L
        const val LIVENESS_TIMEOUT_MS = 10_000L

        const val RECONNECT_BASE_DELAY_MS = 500L
        const val RECONNECT_MAX_DELAY_MS = 5000L

        /** How long failures may run back-to-back before the error is surfaced to the user. */
        const val MAX_RETRY_WINDOW_MS = 30_000L

        /** Shorter than this (even with frames) counts as a failure, not a working session. */
        const val MIN_GOOD_SESSION_MS = 3000L

        private const val CONTROL_READ_TIMEOUT_MS = 1000
        private const val VIDEO_READ_TIMEOUT_MS = 1000
        private const val WRITER_TICK_MS = 200L
        private const val READ_BUFFER = 8 * 1024
        private const val VIDEO_READ_BUFFER = 128 * 1024

        /**
         * What this device can actually decode, most-preferred first: HEVC before AVC (half the
         * bitrate for the same picture on a wireless link), each reported with the maxima of its
         * best hardware decoder. Software-only decoders are ignored — a 3840x1080 desktop stream
         * decoded on the CPU would melt the phone — unless nothing else exists.
         */
        fun deviceCodecs(): List<PcCodecCapability> =
            listOfNotNull(capabilityFor(MIME_HEVC), capabilityFor(MIME_AVC))

        private fun capabilityFor(mime: String): PcCodecCapability? {
            val infos = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            } catch (_: Throwable) {
                return null
            }
            var best: MediaCodecInfo.VideoCapabilities? = null
            var bestIsHardware = false
            for (info in infos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val hardware = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        info.isHardwareAccelerated && !info.isSoftwareOnly
                    } else true
                } catch (_: Throwable) { false }
                if (best != null && bestIsHardware && !hardware) continue
                val video = try {
                    info.getCapabilitiesForType(mime).videoCapabilities
                } catch (_: Throwable) { null } ?: continue
                if (best == null || (hardware && !bestIsHardware)) {
                    best = video
                    bestIsHardware = hardware
                }
            }
            val caps = best ?: return null
            return try {
                PcCodecCapability(
                    mime = mime,
                    maxWidth = caps.supportedWidths.upper,
                    maxHeight = caps.supportedHeights.upper,
                    maxFps = caps.supportedFrameRates.upper
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}
