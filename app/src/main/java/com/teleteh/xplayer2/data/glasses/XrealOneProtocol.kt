package com.teleteh.xplayer2.data.glasses

/**
 * Wire format of the control channel of XREAL One-series glasses (One, One Pro, 1S).
 *
 * These glasses show up on the phone's USB-C port as a network adapter (CDC-NCM). They sit at the
 * fixed link-local address [HOST] and serve a TCP control port. The format below comes from public
 * reverse-engineering work (the `xreal_one_driver` of github.com/0xcaff/xr-tools). It is not an
 * official XREAL interface and it has not been verified on real One / One Pro / 1S hardware, so
 * every reply is validated and anything unexpected counts as "this device is not controllable".
 *
 * Frame (both directions), all integers big-endian:
 *
 *     magic[2] | length u32 | transaction id u32 | body
 *
 * * `length` = body size + 4 (it counts the transaction id).
 * * Requests carry transaction id 0x80000001 and only one request is in flight, so replies are
 *   matched by `magic`.
 * * Unsolicited frames (button presses, magic 0x272E) have no transaction id: their `length` is the
 *   body size. They are skipped, which is why [XrealOneLink] reads by `length` only.
 *
 * Body is protobuf:
 *
 *     set N  ->  1A <len> 08 <varint N>     (field 3, length-delimited, holding field 1)
 *     get    ->  18 00                      (field 3, varint 0)
 *     ack    <-  22 00                      (field 4, length-delimited, empty)
 *     value  <-  22 <len> <tag> <value>     (field 4 holding one value)
 */
object XrealOneProtocol {
    const val HOST = "169.254.2.1"
    const val CONTROL_PORT = 52999

    const val HEADER_SIZE = 6

    /**
     * The biggest frame we accept. Everything we ask for is a few bytes; this only keeps a corrupted
     * length field from allocating gigabytes.
     */
    const val MAX_PAYLOAD = 1 shl 20

    private const val REQUEST_TRANSACTION_ID = 0x8000_0001L

    class Command(vararg magic: Int) {
        val magic: ByteArray = ByteArray(magic.size) { magic[it].toByte() }

        override fun equals(other: Any?) = other is Command && magic.contentEquals(other.magic)
        override fun hashCode() = magic.contentHashCode()
    }

    val GET_ID = Command(0x27, 0x29)
    val SET_BRIGHTNESS = Command(0x27, 0x1C)
    val SET_DIMMER = Command(0x27, 0x27)
    val GET_DISPLAY_CONFIGURATION = Command(0x27, 0x5E)
    val SET_DISPLAY_CONFIGURATION = Command(0x27, 0x5F)

    // region encoding

    fun frame(command: Command, body: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + 4 + body.size)
        command.magic.copyInto(out)
        bigEndian(body.size + 4L).copyInto(out, 2)
        bigEndian(REQUEST_TRANSACTION_ID).copyInto(out, HEADER_SIZE)
        body.copyInto(out, HEADER_SIZE + 4)
        return out
    }

    val GET_BODY: ByteArray = byteArrayOf(0x18, 0x00)

    fun setBody(value: Int): ByteArray {
        require(value in 0..255)
        val encoded = varint(value.toLong())
        return byteArrayOf(0x1A) + varint(encoded.size + 1L) + byteArrayOf(0x08) + encoded
    }

    // endregion

    // region decoding

    /** [payload] is everything after the 6-byte header (transaction id included). */
    fun bodyOfReply(payload: ByteArray): ByteArray {
        if (payload.size < 4) throw XrealOneError.ProtocolViolation("short reply")
        return payload.copyOfRange(4, payload.size)
    }

    /** A set command is answered with an empty field 4, and with nothing else. */
    fun requireAck(body: ByteArray) {
        if (!body.contentEquals(byteArrayOf(0x22, 0x00))) {
            throw XrealOneError.ProtocolViolation("unexpected set reply ${hex(body)}")
        }
    }

    fun numberFromReply(body: ByteArray): Long =
        Reader(body).readValueField(WIRE_VARINT).number

    fun stringFromReply(body: ByteArray): String {
        val bytes = Reader(body).readValueField(WIRE_LENGTH_DELIMITED).bytes
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw XrealOneError.ProtocolViolation("reply text is not UTF-8")
        }
    }

    private const val WIRE_VARINT = 0
    private const val WIRE_LENGTH_DELIMITED = 2

    private class Value(val number: Long, val bytes: ByteArray)

    private class Reader(private val bytes: ByteArray) {
        private var index = 0

        /**
         * `22 <len> <tag> <value>` and nothing after it. The inner field number is not checked:
         * only its wire type is, so a renumbered field cannot be mistaken for a different type.
         */
        fun readValueField(wire: Int): Value {
            if (readByte() != 0x22) throw XrealOneError.ProtocolViolation("reply is not field 4")
            val length = readVarint()
            if (length <= 0 || length > bytes.size - index) {
                throw XrealOneError.ProtocolViolation("reply length does not match")
            }
            if (index + length != bytes.size.toLong()) {
                throw XrealOneError.ProtocolViolation("reply length does not match")
            }
            val tag = readByte()
            if (tag and 0x07 != wire) throw XrealOneError.ProtocolViolation("reply value has wrong type")
            return when (wire) {
                WIRE_VARINT -> {
                    val number = readVarint()
                    if (index != bytes.size) throw XrealOneError.ProtocolViolation("trailing bytes")
                    Value(number, ByteArray(0))
                }
                else -> {
                    val size = readVarint()
                    if (size < 0 || size != (bytes.size - index).toLong()) {
                        throw XrealOneError.ProtocolViolation("text length does not match")
                    }
                    Value(0, bytes.copyOfRange(index, bytes.size))
                }
            }
        }

        private fun readByte(): Int {
            if (index >= bytes.size) throw XrealOneError.ProtocolViolation("reply is truncated")
            return bytes[index++].toInt() and 0xFF
        }

        private fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = readByte()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift >= 64) throw XrealOneError.ProtocolViolation("varint is too long")
            }
        }
    }

    // endregion

    private fun varint(value: Long): ByteArray {
        var rest = value
        val out = ArrayList<Byte>()
        while (rest >= 0x80) {
            out.add(((rest and 0x7F) or 0x80).toByte())
            rest = rest ushr 7
        }
        out.add(rest.toByte())
        return out.toByteArray()
    }

    private fun bigEndian(value: Long): ByteArray =
        byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte())

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }
}

/**
 * What the glasses' display chain outputs. The panel is 1920x1080 per eye; the 3840x1080 entry
 * presents both eyes side by side, which is the layout a 3D movie needs.
 */
enum class XrealOneDisplayConfiguration(val value: Int, val isStereo: Boolean = false) {
    HD_60(2),
    HD_90(3),
    HD_120(4),
    SIDE_BY_SIDE_60(5, isStereo = true);

    companion object {
        fun fromValue(value: Long): XrealOneDisplayConfiguration? = entries.firstOrNull { it.value.toLong() == value }
    }
}

/** Strength of the electrochromic shade in front of the lenses. */
enum class XrealOneDimmer(val value: Int) {
    LIGHTEST(0),
    MIDDLE(1),
    DIMMEST(2);

    companion object {
        fun fromValue(value: Int): XrealOneDimmer? = entries.firstOrNull { it.value == value }
    }
}

object XrealOneBrightness {
    val RANGE = 0..9
}

sealed class XrealOneError(message: String) : Exception(message) {
    /** No route or connection refused: these glasses are not a controllable XREAL One. */
    class Unreachable(message: String) : XrealOneError(message)
    class Timeout : XrealOneError("timeout")
    class Closed : XrealOneError("closed")
    class ProtocolViolation(message: String) : XrealOneError(message)

    /**
     * The link is gone (as opposed to one reply being wrong), so the caller should reconnect.
     * A timeout counts: the link closes itself after one, to keep requests and replies paired.
     */
    val isLinkLoss: Boolean get() = this !is ProtocolViolation
}
