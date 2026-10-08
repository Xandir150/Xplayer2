package com.teleteh.xplayer2.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class NetLimitsTest {
    @Test fun acceptsOnlyHttpUrls() {
        assertTrue(NetLimits.isHttpUrl("http://192.168.1.5:8200/desc.xml"))
        assertTrue(NetLimits.isHttpUrl("HTTPS://nas.local/x"))
        assertFalse(NetLimits.isHttpUrl("file:///sdcard/a.mp4"))
        assertFalse(NetLimits.isHttpUrl("content://media/1"))
        assertFalse(NetLimits.isHttpUrl("smb://nas/share"))
        assertFalse(NetLimits.isHttpUrl("/relative/path"))
        assertFalse(NetLimits.isHttpUrl(null))
    }

    @Test fun readsWithinLimit() {
        assertEquals("héllo", NetLimits.readTextLimited(ByteArrayInputStream("héllo".toByteArray())))
    }

    @Test fun rejectsOversizedBody() {
        try {
            NetLimits.readTextLimited(ByteArrayInputStream(ByteArray(100)), maxBytes = 50)
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }
}
