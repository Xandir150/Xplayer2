package com.teleteh.xplayer2.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class DlnaBrowser {
    companion object {
        const val PAGE_SIZE = 200
        /** Hard stops, so a server that never ends its paging cannot loop or exhaust memory. */
        const val MAX_PAGES = 50
        const val MAX_ENTRIES = 10_000
    }

    suspend fun resolveContentDirectoryControlUrl(deviceDescriptionUrl: String): String? =
        withContext(Dispatchers.IO) {
            if (!NetLimits.isHttpUrl(deviceDescriptionUrl)) return@withContext null
            try {
                val xml = fetchText(deviceDescriptionUrl) ?: return@withContext null
                // Extract optional base URL (URLBase/baseURL)
                val base = extractTagCI(xml, "URLBase") ?: extractTagCI(xml, "baseURL")
                // Iterate all <service> blocks and find ContentDirectory
                val servicePattern = Pattern.compile(
                    "<service[\\s\\S]*?</service>",
                    Pattern.CASE_INSENSITIVE or Pattern.DOTALL
                )
                val m = servicePattern.matcher(xml)
                while (m.find()) {
                    val block = m.group()
                    val type = extractTagCI(block, "serviceType")
                    val serviceId = extractTagCI(block, "serviceId")
                    val isContentDir =
                        (type?.contains("ContentDirectory", ignoreCase = true) == true) ||
                                (serviceId?.contains("ContentDirectory", ignoreCase = true) == true)
                    if (isContentDir) {
                        val controlRel = extractTagCI(block, "controlURL") ?: continue
                        return@withContext resolveControl(deviceDescriptionUrl, base, controlRel)
                            .takeIf { NetLimits.isHttpUrl(it) }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Lists the children of [objectId], following the server's paging until it has them all.
     *
     * A server returns at most the page we ask for, and `TotalMatches` says how many exist, so a
     * single request silently drops everything past the first page of a big folder. Failures are
     * thrown as [DlnaBrowseException] rather than turned into an empty list: an unreachable server,
     * a SOAP fault and a truly empty folder must not look the same to the person looking at the
     * screen. [BrowseResult.truncated] is set when the safety cap was hit.
     */
    @Throws(DlnaBrowseException::class)
    suspend fun browse(controlUrl: String, objectId: String): BrowseResult =
        withContext(Dispatchers.IO) {
            if (!NetLimits.isHttpUrl(controlUrl)) throw DlnaBrowseException("Bad control URL")
            val containers = mutableListOf<Container>()
            val items = mutableListOf<Item>()
            var start = 0
            var pages = 0
            var truncated = false
            while (true) {
                val page = browsePage(controlUrl, objectId, start, PAGE_SIZE)
                containers += page.result.containers
                items += page.result.items
                start += page.numberReturned
                pages++
                val done = page.numberReturned == 0 ||
                    (page.totalMatches in 1..start) ||
                    // Some servers report TotalMatches=0 ("unknown"): stop on a short page.
                    (page.totalMatches <= 0 && page.numberReturned < PAGE_SIZE)
                if (done) break
                if (pages >= MAX_PAGES || containers.size + items.size >= MAX_ENTRIES) {
                    truncated = true
                    break
                }
            }
            BrowseResult(containers, items, truncated)
        }

    private class Page(val result: BrowseResult, val numberReturned: Int, val totalMatches: Int)

    private fun browsePage(controlUrl: String, objectId: String, start: Int, count: Int): Page {
        val soapAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <ObjectID>${escapeXml(objectId)}</ObjectID>
                  <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                  <Filter>*</Filter>
                  <StartingIndex>$start</StartingIndex>
                  <RequestedCount>$count</RequestedCount>
                  <SortCriteria></SortCriteria>
                </u:Browse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=utf-8")
                setRequestProperty("SOAPAction", soapAction)
            }
            conn.outputStream.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(envelope) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                // A SOAP fault arrives as HTTP 500 with the reason in the body.
                val body = conn.errorStream?.use { runCatching { NetLimits.readTextLimited(it) }.getOrNull() }
                throw DlnaBrowseException(faultText(body) ?: "HTTP $code")
            }
            val body = conn.inputStream.use { NetLimits.readTextLimited(it) }
            if (!body.contains("<Result>")) {
                throw DlnaBrowseException(faultText(body) ?: "No result in server answer")
            }
            return Page(
                parseDidlFromSoap(body),
                intTag(body, "NumberReturned") ?: 0,
                intTag(body, "TotalMatches") ?: 0
            )
        } catch (e: DlnaBrowseException) {
            throw e
        } catch (e: java.io.IOException) {
            throw DlnaBrowseException(e.message ?: e.javaClass.simpleName, e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun intTag(soap: String, tag: String): Int? =
        Regex("<(?:\\w+:)?$tag>\\s*(\\d+)\\s*</").find(soap)?.groupValues?.get(1)?.toIntOrNull()

    /** The UPnP `errorDescription`/`errorCode` of a SOAP fault, or null if [body] has none. */
    private fun faultText(body: String?): String? {
        if (body == null) return null
        val desc = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1)?.trim()
        val code = Regex("<errorCode>(.*?)</errorCode>").find(body)?.groupValues?.get(1)?.trim()
        return when {
            !desc.isNullOrEmpty() && !code.isNullOrEmpty() -> "$desc ($code)"
            !desc.isNullOrEmpty() -> desc
            !code.isNullOrEmpty() -> "UPnP error $code"
            else -> null
        }
    }

    data class BrowseResult(
        val containers: List<Container>,
        val items: List<Item>,
        /** True when the folder was cut off at the safety cap, not because it ended. */
        val truncated: Boolean = false
    )

    class DlnaBrowseException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

    data class Container(val id: String, val parentId: String?, val title: String)
    data class Item(val title: String, val resUrl: String, val mime: String?)

    private fun parseDidlFromSoap(soap: String): BrowseResult {
        // Extract Result content (escaped DIDL)
        val resultStart = soap.indexOf("<Result>")
        val resultEnd = soap.indexOf("</Result>")
        if (resultStart < 0 || resultEnd < 0) return BrowseResult(emptyList(), emptyList())
        val escaped = soap.substring(resultStart + 8, resultEnd)
        val didl = escaped
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
        val containers = mutableListOf<Container>()
        val items = mutableListOf<Item>()
        // Containers
        val contPattern = Pattern.compile(
            "<container[^>]*id=\"([^\"]+)\"[^>]*parentID=\"([^\"]*)\"[^>]*>(.*?)</container>",
            Pattern.DOTALL
        )
        val contMatcher = contPattern.matcher(didl)
        while (contMatcher.find()) {
            val id = contMatcher.group(1)
            val parent = contMatcher.group(2)
            val block = contMatcher.group(3)
            val title = extractTag(block, "dc:title") ?: extractTag(block, "title") ?: id
            containers.add(Container(id, parent, title))
        }
        // Items
        val itemPattern = Pattern.compile("<item[^>]*>(.*?)</item>", Pattern.DOTALL)
        val itemMatcher = itemPattern.matcher(didl)
        while (itemMatcher.find()) {
            val block = itemMatcher.group(1)
            val title = extractTag(block, "dc:title") ?: extractTag(block, "title") ?: "Item"
            val resBlock = extractTagRaw(block, "res")
            val url = resBlock?.second?.takeIf { NetLimits.isHttpUrl(it) } ?: continue
            val mime = extractAttr(resBlock.first, "protocolInfo")?.let { proto ->
                // protocolInfo like: http-get:*:video/mp4:*
                val parts = proto.split(":")
                if (parts.size >= 3) parts[2] else null
            }
            items.add(Item(title, url, mime))
        }
        return BrowseResult(containers, items)
    }

    private fun extractTag(block: String, tag: String): String? {
        val start = block.indexOf("<$tag")
        if (start < 0) return null
        val gt = block.indexOf('>', start)
        if (gt < 0) return null
        val end = block.indexOf("</$tag>", gt + 1)
        if (end < 0) return null
        return xmlUnescape(block.substring(gt + 1, end).trim())
    }

    /**
     * The DIDL is XML inside the SOAP envelope's own XML text, so it is escaped twice. The first
     * pass (in [parseDidlFromSoap]) restores the DIDL markup; what is left in a value, such as the
     * `&amp;` between URL parameters, is the DIDL's own escaping and has to be undone here.
     */
    private fun xmlUnescape(v: String): String {
        if (!v.contains('&')) return v
        val numeric = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
        return numeric.replace(v) {
            val n = it.groupValues[1]
            val cp = (if (n.startsWith("x")) n.drop(1).toIntOrNull(16) else n.toIntOrNull()) ?: return@replace it.value
            if (Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else it.value
        }
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun extractTagCI(block: String, tag: String): String? {
        val p = Pattern.compile("<${tag}[^>]*>([\\s\\S]*?)</${tag}>", Pattern.CASE_INSENSITIVE)
        val m = p.matcher(block)
        return if (m.find()) m.group(1).trim() else null
    }

    private fun extractTagRaw(block: String, tag: String): Pair<String, String>? {
        val start = block.indexOf("<$tag")
        if (start < 0) return null
        val gt = block.indexOf('>', start)
        if (gt < 0) return null
        val attrs = block.substring(start, gt + 1)
        val end = block.indexOf("</$tag>", gt + 1)
        if (end < 0) return null
        val value = xmlUnescape(block.substring(gt + 1, end).trim())
        return attrs to value
    }

    private fun extractAttr(tagOpen: String, attr: String): String? {
        val p = Pattern.compile("$attr=\"([^\"]*)\"")
        val m = p.matcher(tagOpen)
        return if (m.find()) m.group(1) else null
    }

    private fun findBlock(src: String, open: String, close: String): String? {
        val i = src.indexOf(open)
        if (i < 0) return null
        val j = src.indexOf(close, i)
        if (j < 0) return null
        return src.substring(i, j + close.length)
    }

    private fun findAll(src: String, open: String, close: String): List<String> {
        val out = mutableListOf<String>()
        var idx = 0
        while (true) {
            val i = src.indexOf(open, idx)
            if (i < 0) break
            val j = src.indexOf(close, i)
            if (j < 0) break
            out.add(src.substring(i, j + close.length))
            idx = j + close.length
        }
        return out
    }

    private fun resolveRelative(base: String, rel: String): String {
        val baseUrl = URL(base)
        return URL(baseUrl, rel).toString()
    }

    private fun resolveControl(descUrl: String, baseUrlOpt: String?, control: String): String {
        return try {
            val ctrl = control.trim()
            // Absolute URL
            if (ctrl.startsWith("http://") || ctrl.startsWith("https://")) return ctrl
            // Use baseURL if provided by device
            if (!baseUrlOpt.isNullOrBlank()) {
                return URL(URL(baseUrlOpt), ctrl).toString()
            }
            // Fallback to device description URL as base
            URL(URL(descUrl), ctrl).toString()
        } catch (_: Exception) {
            control
        }
    }

    private fun fetchText(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Accept", "application/xml, text/xml, */*;q=0.8")
                setRequestProperty("User-Agent", "XPlayer2/1.0 (Android)")
            }
            conn.inputStream.use { NetLimits.readTextLimited(it) }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
