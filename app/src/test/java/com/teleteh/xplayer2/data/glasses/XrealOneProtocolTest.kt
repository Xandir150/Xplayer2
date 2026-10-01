package com.teleteh.xplayer2.data.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XrealOneProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun assertViolation(block: () -> Unit) {
        try {
            block()
            fail("expected a protocol violation")
        } catch (_: XrealOneError.ProtocolViolation) {
        }
    }

    // region encoding

    @Test
    fun `set brightness 5 is the documented frame`() {
        val frame = XrealOneProtocol.frame(XrealOneProtocol.SET_BRIGHTNESS, XrealOneProtocol.setBody(5))
        assertArrayEquals(bytes(0x27, 0x1C, 0x00, 0x00, 0x00, 0x08, 0x80, 0x00, 0x00, 0x01, 0x1A, 0x02, 0x08, 0x05), frame)
    }

    @Test
    fun `get id is the documented frame`() {
        val frame = XrealOneProtocol.frame(XrealOneProtocol.GET_ID, XrealOneProtocol.GET_BODY)
        assertArrayEquals(bytes(0x27, 0x29, 0x00, 0x00, 0x00, 0x06, 0x80, 0x00, 0x00, 0x01, 0x18, 0x00), frame)
    }

    @Test
    fun `set body of a value above 127 uses a two byte varint`() {
        assertArrayEquals(bytes(0x1A, 0x03, 0x08, 0x80, 0x01), XrealOneProtocol.setBody(128))
    }

    @Test
    fun `command magics are the documented ones`() {
        assertArrayEquals(bytes(0x27, 0x5E), XrealOneProtocol.GET_DISPLAY_CONFIGURATION.magic)
        assertArrayEquals(bytes(0x27, 0x5F), XrealOneProtocol.SET_DISPLAY_CONFIGURATION.magic)
        assertArrayEquals(bytes(0x27, 0x27), XrealOneProtocol.SET_DIMMER.magic)
    }

    // endregion

    // region decoding

    @Test
    fun `ack is 22 00 and nothing else`() {
        XrealOneProtocol.requireAck(bytes(0x22, 0x00))
        assertViolation { XrealOneProtocol.requireAck(bytes(0x22, 0x02, 0x10, 0x05)) }
        assertViolation { XrealOneProtocol.requireAck(bytes(0x22, 0x00, 0x00)) }
        assertViolation { XrealOneProtocol.requireAck(ByteArray(0)) }
    }

    @Test
    fun `number reply`() {
        assertEquals(5L, XrealOneProtocol.numberFromReply(bytes(0x22, 0x02, 0x10, 0x05)))
    }

    @Test
    fun `number reply accepts any field number with the varint wire type`() {
        assertEquals(5L, XrealOneProtocol.numberFromReply(bytes(0x22, 0x02, 0x18, 0x05)))
    }

    @Test
    fun `multi byte number reply`() {
        assertEquals(300L, XrealOneProtocol.numberFromReply(bytes(0x22, 0x03, 0x10, 0xAC, 0x02)))
    }

    @Test
    fun `string reply`() {
        assertEquals("ABC", XrealOneProtocol.stringFromReply(bytes(0x22, 0x05, 0x12, 0x03, 0x41, 0x42, 0x43)))
    }

    @Test
    fun `a string where a number is expected is rejected, and the other way round`() {
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x22, 0x05, 0x12, 0x03, 0x41, 0x42, 0x43)) }
        assertViolation { XrealOneProtocol.stringFromReply(bytes(0x22, 0x02, 0x10, 0x05)) }
    }

    @Test
    fun `malformed replies are rejected`() {
        // not field 4
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x1A, 0x02, 0x10, 0x05)) }
        // length longer than the data
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x22, 0x05, 0x10, 0x05)) }
        // trailing bytes after the field
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x22, 0x02, 0x10, 0x05, 0x00)) }
        // trailing bytes inside the field
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x22, 0x03, 0x10, 0x05, 0x00)) }
        // empty field
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x22, 0x00)) }
        // truncated varint
        assertViolation { XrealOneProtocol.numberFromReply(bytes(0x22, 0x02, 0x10, 0x80)) }
        // empty input
        assertViolation { XrealOneProtocol.numberFromReply(ByteArray(0)) }
        // text length does not match
        assertViolation { XrealOneProtocol.stringFromReply(bytes(0x22, 0x05, 0x12, 0x02, 0x41, 0x42, 0x43)) }
        assertViolation { XrealOneProtocol.stringFromReply(bytes(0x22, 0x05, 0x12, 0x04, 0x41, 0x42, 0x43)) }
        // not UTF-8
        assertViolation { XrealOneProtocol.stringFromReply(bytes(0x22, 0x03, 0x12, 0x01, 0xFF)) }
    }

    @Test
    fun `body of a reply drops the transaction id`() {
        val payload = bytes(0x80, 0x00, 0x00, 0x01, 0x22, 0x00)
        assertArrayEquals(bytes(0x22, 0x00), XrealOneProtocol.bodyOfReply(payload))
        assertViolation { XrealOneProtocol.bodyOfReply(bytes(0x80, 0x00)) }
    }

    // endregion

    @Test
    fun `values map to the documented configurations`() {
        assertEquals(XrealOneDisplayConfiguration.HD_60, XrealOneDisplayConfiguration.fromValue(2))
        assertEquals(XrealOneDisplayConfiguration.HD_90, XrealOneDisplayConfiguration.fromValue(3))
        assertEquals(XrealOneDisplayConfiguration.HD_120, XrealOneDisplayConfiguration.fromValue(4))
        assertEquals(XrealOneDisplayConfiguration.SIDE_BY_SIDE_60, XrealOneDisplayConfiguration.fromValue(5))
        assertNull(XrealOneDisplayConfiguration.fromValue(0))
        assertNull(XrealOneDisplayConfiguration.fromValue(6))
        assertTrue(XrealOneDisplayConfiguration.SIDE_BY_SIDE_60.isStereo)
        assertFalse(XrealOneDisplayConfiguration.HD_120.isStereo)
        assertEquals(listOf(0, 1, 2), XrealOneDimmer.entries.map { it.value })
        assertEquals(0..9, XrealOneBrightness.RANGE)
    }

    @Test
    fun `only a wrong reply keeps the link, everything else loses it`() {
        assertFalse(XrealOneError.ProtocolViolation("x").isLinkLoss)
        assertTrue(XrealOneError.Timeout().isLinkLoss)
        assertTrue(XrealOneError.Closed().isLinkLoss)
        assertTrue(XrealOneError.Unreachable("x").isLinkLoss)
    }
}
