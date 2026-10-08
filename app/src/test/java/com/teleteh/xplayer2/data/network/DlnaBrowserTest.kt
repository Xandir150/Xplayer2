package com.teleteh.xplayer2.data.network

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** DlnaBrowser against a real local HTTP server that speaks just enough ContentDirectory. */
class DlnaBrowserTest {
    private lateinit var server: HttpServer
    private val requests = CopyOnWriteArrayList<Pair<Int, Int>>() // (start, count) per request
    private var handler: (HttpExchange, Int, Int) -> Unit = { _, _, _ -> }

    private val url get() = "http://127.0.0.1:${server.address.port}/ctl"

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ctl") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val start = Regex("<StartingIndex>(\\d+)<").find(body)!!.groupValues[1].toInt()
            val count = Regex("<RequestedCount>(\\d+)<").find(body)!!.groupValues[1].toInt()
            requests += start to count
            handler(ex, start, count)
            ex.close()
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun reply(ex: HttpExchange, code: Int, body: String) {
        val b = body.toByteArray()
        ex.sendResponseHeaders(code, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun page(from: Int, n: Int, total: Int): String {
        val didl = StringBuilder("<DIDL-Lite>")
        for (i in from until from + n) {
            didl.append("<item id=\"$i\" parentID=\"0\"><dc:title>T$i</dc:title>")
                .append("<res protocolInfo=\"http-get:*:video/mp4:*\">http://h/$i.mp4?a=1&amp;b=2</res></item>")
        }
        didl.append("</DIDL-Lite>")
        val escaped = didl.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<s:Envelope><s:Body><u:BrowseResponse><Result>$escaped</Result>" +
            "<NumberReturned>$n</NumberReturned><TotalMatches>$total</TotalMatches>" +
            "</u:BrowseResponse></s:Body></s:Envelope>"
    }

    @Test fun followsPagingUntilTotalMatches() {
        handler = { ex, start, count ->
            val n = minOf(count, 450 - start)
            reply(ex, 200, page(start, n, 450))
        }
        val res = runBlocking { DlnaBrowser().browse(url, "0") }
        assertEquals(450, res.items.size)
        assertFalse(res.truncated)
        assertEquals(listOf(0 to 200, 200 to 200, 400 to 200), requests.toList())
        assertEquals("T0", res.items.first().title)
        assertEquals("T449", res.items.last().title)
    }

    @Test fun decodesDidlEntitiesInUrls() {
        handler = { ex, _, _ -> reply(ex, 200, page(0, 1, 1)) }
        val res = runBlocking { DlnaBrowser().browse(url, "0") }
        assertEquals("http://h/0.mp4?a=1&b=2", res.items.single().resUrl)
    }

    @Test fun stopsOnShortPageWhenServerReportsNoTotal() {
        handler = { ex, start, _ -> reply(ex, 200, page(start, 30, 0)) }
        val res = runBlocking { DlnaBrowser().browse(url, "0") }
        assertEquals(30, res.items.size)
        assertEquals(1, requests.size)
    }

    @Test fun anEmptyFolderIsNotAnError() {
        handler = { ex, _, _ -> reply(ex, 200, page(0, 0, 0)) }
        val res = runBlocking { DlnaBrowser().browse(url, "0") }
        assertTrue(res.items.isEmpty() && res.containers.isEmpty())
    }

    @Test fun soapFaultIsReportedNotHiddenAsEmpty() {
        handler = { ex, _, _ ->
            reply(ex, 500, "<s:Envelope><s:Body><s:Fault><detail><UPnPError>" +
                "<errorCode>701</errorCode><errorDescription>No such object</errorDescription>" +
                "</UPnPError></detail></s:Fault></s:Body></s:Envelope>")
        }
        try {
            runBlocking { DlnaBrowser().browse(url, "bad") }
            fail("expected DlnaBrowseException")
        } catch (e: DlnaBrowser.DlnaBrowseException) {
            assertTrue(e.message, e.message!!.contains("No such object") && e.message!!.contains("701"))
        }
    }

    @Test fun failureOnALaterPageFailsTheWholeBrowse() {
        handler = { ex, start, count ->
            if (start == 0) reply(ex, 200, page(0, count, 450)) else reply(ex, 500, "boom")
        }
        try {
            runBlocking { DlnaBrowser().browse(url, "0") }
            fail("a half-listed folder must not look complete")
        } catch (_: DlnaBrowser.DlnaBrowseException) {
        }
    }

    @Test fun unreachableServerThrows() {
        val dead = url
        server.stop(0)
        try {
            runBlocking { DlnaBrowser().browse(dead, "0") }
            fail("expected DlnaBrowseException")
        } catch (_: DlnaBrowser.DlnaBrowseException) {
        }
    }

    @Test fun endlessServerIsCutOffAndFlagged() {
        handler = { ex, start, count -> reply(ex, 200, page(start, count, 10_000_000)) }
        val res = runBlocking { DlnaBrowser().browse(url, "0") }
        assertTrue(res.truncated)
        assertTrue(res.items.size <= DlnaBrowser.MAX_ENTRIES)
        assertTrue(requests.size <= DlnaBrowser.MAX_PAGES)
    }

    @Test fun rejectsNonHttpControlUrl() {
        try {
            runBlocking { DlnaBrowser().browse("file:///etc/passwd", "0") }
            fail("expected DlnaBrowseException")
        } catch (_: DlnaBrowser.DlnaBrowseException) {
        }
    }
}
