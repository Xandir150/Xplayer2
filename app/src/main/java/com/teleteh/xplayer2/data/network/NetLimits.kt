package com.teleteh.xplayer2.data.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI

/**
 * Guards for data that comes from a LAN peer nobody has authenticated (SSDP replies, UPnP device
 * descriptions, SOAP answers). A real device answers with a few KB; a hostile one can stream
 * forever (a per-read timeout never fires on a trickle) or point a URL at a scheme this app should
 * never open from the network, such as `file://` or `content://`.
 */
internal object NetLimits {
    /** Far above any real device description or one page of a Browse answer. */
    const val MAX_XML_BYTES = 2 * 1024 * 1024

    /** Reads the whole stream as UTF-8 text, or throws [IOException] once it passes [maxBytes]. */
    fun readTextLimited(input: InputStream, maxBytes: Int = MAX_XML_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IOException("Response larger than $maxBytes bytes")
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** True only for an absolute http or https URL with a host. */
    fun isHttpUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = URI(url.trim())
            (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }
}
