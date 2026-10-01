package com.teleteh.xplayer2.data.glasses

import com.teleteh.xplayer2.data.glasses.GlassesController.Brand
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrealOneEligibilityTest {

    @Test
    fun `nothing attached is never probed`() {
        assertFalse(XrealOneController.isEligible(false, emptyMap()))
        assertFalse(XrealOneController.isEligible(false, mapOf(Brand.XREAL to "One")))
    }

    @Test
    fun `an unreadable identity is still probed`() {
        assertTrue(XrealOneController.isEligible(true, emptyMap()))
    }

    @Test
    fun `XREAL One-series is probed, Air series and other brands are not`() {
        assertTrue(XrealOneController.isEligible(true, mapOf(Brand.XREAL to "One Pro")))
        assertTrue(XrealOneController.isEligible(true, mapOf(Brand.XREAL to "One S")))
        assertFalse(XrealOneController.isEligible(true, mapOf(Brand.XREAL to "Air 2 Pro")))
        assertFalse(XrealOneController.isEligible(true, mapOf(Brand.VITURE to "Luma")))
        assertFalse(XrealOneController.isEligible(true, mapOf(Brand.ROKID to "Air/Max")))
    }

    @Test
    fun `the probe covers about thirty seconds`() {
        assertEquals(30_000L, XrealOneController.PROBE_SCHEDULE_MS.sum())
    }

    @Test
    fun `link-local only, and not on Wi-Fi interfaces`() {
        val linkLocal = listOf(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 2, 2)))
        val lan = listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
        // without capabilities the address alone decides
        assertTrue(XrealOneNetwork.isGlassesLink(null, linkLocal))
        assertFalse(XrealOneNetwork.isGlassesLink(null, lan))
        assertFalse(XrealOneNetwork.isGlassesLink(null, emptyList()))
        assertTrue(XrealOneNetwork.isCarrierInterface("wlan0"))
        assertTrue(XrealOneNetwork.isCarrierInterface("rmnet_data1"))
        assertFalse(XrealOneNetwork.isCarrierInterface("eth0"))
        assertFalse(XrealOneNetwork.isCarrierInterface("usb0"))
    }
}
