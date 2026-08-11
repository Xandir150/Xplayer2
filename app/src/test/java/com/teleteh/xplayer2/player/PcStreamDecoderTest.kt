package com.teleteh.xplayer2.player

import com.teleteh.xplayer2.data.network.PcLinkProtocol
import com.teleteh.xplayer2.data.network.PcVideoFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure pieces of the PC Link decoder path: the drop-to-latest input policy
 * ([PcAuDropPolicy]) and the Annex-B parameter-set extraction ([PcAnnexB]) that builds `csd-0` /
 * `csd-1`. Neither touches MediaCodec, so both run on the JVM.
 */
class PcStreamDecoderTest {

    private var pts = 0L

    private fun au(flags: Int, size: Int = 4): PcVideoFrame =
        PcVideoFrame(flags, pts++, ByteArray(size))

    private fun p() = au(0)
    private fun idr() = au(PcLinkProtocol.FLAG_IDR)
    private fun idrWithConfig() = au(PcLinkProtocol.FLAG_IDR or PcLinkProtocol.FLAG_CODEC_CONFIG)

    // --- drop-to-latest policy --------------------------------------------------------------------

    @Test
    fun `queues normally while the decoder keeps up`() {
        val policy = PcAuDropPolicy(maxPending = 3)

        assertFalse(policy.offer(idrWithConfig()))
        assertFalse(policy.offer(p()))
        policy.poll()
        assertFalse(policy.offer(p()))

        assertEquals(2, policy.size)
        assertEquals(0L, policy.droppedFrames)
        assertFalse(policy.isWaitingForIdr)
    }

    @Test
    fun `drops the backlog and asks for an idr when nothing is being consumed`() {
        val policy = PcAuDropPolicy(maxPending = 3)
        policy.offer(p())
        policy.offer(p())
        policy.offer(p())

        val wantsIdr = policy.offer(p()) // the fourth: over the limit, no sync point to jump to

        assertTrue(wantsIdr)
        assertEquals(0, policy.size)
        assertEquals(4L, policy.droppedFrames)
        assertTrue(policy.isWaitingForIdr)
    }

    @Test
    fun `drops every non sync unit until the next idr arrives`() {
        val policy = PcAuDropPolicy(maxPending = 3)
        repeat(4) { policy.offer(p()) }
        assertTrue(policy.isWaitingForIdr)

        // A partial GOP must never be fed after a drop, however long the wait.
        repeat(10) { assertFalse(policy.offer(p())) }
        assertEquals(0, policy.size)
        assertEquals(14L, policy.droppedFrames)

        assertFalse(policy.offer(idr()))
        assertFalse(policy.isWaitingForIdr)
        assertEquals(1, policy.size)
        assertTrue(policy.poll()!!.isIdr)
    }

    @Test
    fun `a codec config unit also breaks the wait`() {
        val policy = PcAuDropPolicy(maxPending = 3)
        repeat(4) { policy.offer(p()) }

        assertFalse(policy.offer(au(PcLinkProtocol.FLAG_CODEC_CONFIG)))

        assertFalse(policy.isWaitingForIdr)
        assertEquals(1, policy.size)
    }

    @Test
    fun `jumps to the newest queued sync point instead of asking the server`() {
        val policy = PcAuDropPolicy(maxPending = 3)
        policy.offer(p())
        policy.offer(p())
        policy.offer(idr())

        val wantsIdr = policy.offer(p()) // over the limit, but a sync point is already queued

        assertFalse(wantsIdr)
        assertFalse(policy.isWaitingForIdr)
        assertEquals(2, policy.size)
        assertTrue(policy.poll()!!.isIdr) // the two stale P-frames ahead of it are gone
        assertEquals(2L, policy.droppedFrames)
    }

    @Test
    fun `a stale sync point at the head is dropped too rather than growing the queue`() {
        val policy = PcAuDropPolicy(maxPending = 3)
        policy.offer(idr())
        repeat(3) { policy.offer(p()) }

        // Head is the only sync point and we are still behind: the queue must not grow unbounded.
        assertTrue(policy.isWaitingForIdr)
        assertEquals(0, policy.size)
        repeat(20) { policy.offer(p()) }
        assertEquals(0, policy.size)
    }

    @Test
    fun `reset drops everything and can demand a sync frame`() {
        val policy = PcAuDropPolicy(maxPending = 3)
        policy.offer(idr())
        policy.offer(p())

        policy.reset(requireIdr = true)

        assertEquals(0, policy.size)
        assertEquals(2L, policy.droppedFrames)
        assertTrue(policy.isWaitingForIdr)
        assertFalse(policy.offer(p()))
        assertEquals(0, policy.size)

        policy.reset(requireIdr = false)
        assertFalse(policy.isWaitingForIdr)
        assertFalse(policy.offer(p()))
        assertEquals(1, policy.size)
    }

    @Test
    fun `poll drains in arrival order`() {
        val policy = PcAuDropPolicy(maxPending = 8)
        val first = idrWithConfig()
        val second = p()
        policy.offer(first)
        policy.offer(second)

        assertEquals(first, policy.poll())
        assertEquals(second, policy.poll())
        assertNull(policy.poll())
    }

    // --- Annex-B / csd ------------------------------------------------------------------------------

    private fun nal(startCode4: Boolean, header: ByteArray, body: ByteArray = byteArrayOf(0x11)): ByteArray {
        val start = if (startCode4) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)
        return start + header + body
    }

    @Test
    fun `splits annex-b into nals keeping their start codes`() {
        val a = nal(true, byteArrayOf(0x67))
        val b = nal(false, byteArrayOf(0x68))
        val c = nal(true, byteArrayOf(0x65))

        val nals = PcAnnexB.splitNals(a + b + c)

        assertEquals(3, nals.size)
        assertArrayEquals(a, nals[0])
        assertArrayEquals(b, nals[1])
        assertArrayEquals(c, nals[2])
        assertEquals(4, PcAnnexB.startCodeLen(nals[0]))
        assertEquals(3, PcAnnexB.startCodeLen(nals[1]))
    }

    @Test
    fun `h264 csd splits sps into csd-0 and pps into csd-1`() {
        val sps = nal(true, byteArrayOf(0x67))        // nal_unit_type 7
        val pps = nal(true, byteArrayOf(0x68))        // nal_unit_type 8
        val slice = nal(true, byteArrayOf(0x65), ByteArray(32)) // IDR slice — must not be included

        val csd = PcAnnexB.extractCsd(sps + pps + slice, "video/avc")

        assertNotNull(csd)
        assertArrayEquals(sps, csd!!.csd0)
        assertArrayEquals(pps, csd.csd1)
    }

    @Test
    fun `h265 csd concatenates vps sps and pps into csd-0`() {
        val vps = nal(true, byteArrayOf(0x40, 0x01))  // (0x40 >> 1) & 0x3F = 32
        val sps = nal(true, byteArrayOf(0x42, 0x01))  // 33
        val pps = nal(true, byteArrayOf(0x44, 0x01))  // 34
        val slice = nal(true, byteArrayOf(0x26, 0x01), ByteArray(32)) // 19 = IDR_W_RADL

        val csd = PcAnnexB.extractCsd(vps + sps + pps + slice, "video/hevc")

        assertNotNull(csd)
        assertArrayEquals(vps + sps + pps, csd!!.csd0)
        assertNull(csd.csd1)
    }

    @Test
    fun `skips leading aud and sei before the parameter sets`() {
        val aud = nal(true, byteArrayOf(0x09), byteArrayOf(0x10))
        val sei = nal(true, byteArrayOf(0x06), byteArrayOf(0x01, 0x02, 0x80.toByte()))
        val sps = nal(true, byteArrayOf(0x67))
        val pps = nal(true, byteArrayOf(0x68))

        val csd = PcAnnexB.extractCsd(aud + sei + sps + pps, "video/avc")

        assertArrayEquals(sps, csd!!.csd0)
        assertArrayEquals(pps, csd.csd1)
    }

    @Test
    fun `an access unit with no parameter sets yields no csd`() {
        val slice = nal(true, byteArrayOf(0x41), ByteArray(16)) // non-IDR slice

        assertNull(PcAnnexB.extractCsd(slice, "video/avc"))
        assertNull(PcAnnexB.extractCsd(ByteArray(8), "video/avc"))
    }
}
