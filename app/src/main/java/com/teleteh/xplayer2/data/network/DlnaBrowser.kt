package com.teleteh.xplayer2.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

class DlnaBrowser {
    suspend fun resolveContentDirectoryControlUrl(deviceDescriptionUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val xml = fetchText(deviceDescriptionUrl) ?: return@withContext null
            // Extract optional base URL (URLBase/baseURL)
            val base = extractTagCI(xml, "URLBase") ?: extractTagCI(xml, "baseURL")
            // Iterate all <service> blocks and find ContentDirectory
            val servicePattern = Pattern.compile("<service[\\s\\S]*?</service>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val m = servicePattern.matcher(xml)
            while (m.find()) {
                val block = m.group()
                val type = extractTagCI(block, "serviceType")
                val serviceId = extractTagCI(block, "serviceId")
                val isContentDir = (type?.contains("ContentDirectory", ignoreCase = true) == true) ||
                        (serviceId?.contains("ContentDirectory", ignoreCase = true) == true)
                if (isContentDir) {
                    val controlRel = extractTagCI(block, "controlURL") ?: continue
                    return@withContext resolveControl(deviceDescriptionUrl, base, controlRel)
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun browse(controlUrl: String, objectId: String): BrowseResult = withContext(Dispatchers.IO) {
        val soapAction = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                  <ObjectID>${escapeXml(objectId)}</ObjectID>
                  <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                  <Filter>*</Filter>
                  <StartingIndex>0</StartingIndex>
                  <RequestedCount>200</RequestedCount>
                  <SortCriteria></SortCriteria>
                </u:Browse>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val url = URL(controlUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 6000
            doOutput = true
            setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            setRequestProperty("SOAPAction", soapAction)
        }
        conn.outputStream.use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { it.write(envelope) }
        }
        val body = try {
            conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (e: Exception) {
            conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: throw e
        } finally {
            conn.disconnect()
        }
        parseDidlFromSoap(body)
    }

    data class BrowseResult(
        val containers: List<Container>,
        val items: List<Item>
    )
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
        val contPattern = Pattern.compile("<container[^>]*id=\"([^\"]+)\"[^>]*parentID=\"([^\"]*)\"[^>]*>(.*?)</container>", Pattern.DOTALL)
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
            val url = resBlock?.second ?: continue
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
        return block.substring(gt + 1, end).trim()
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
        val value = block.substring(gt + 1, end).trim()
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
        return try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 6000
                setRequestProperty("Accept", "application/xml, text/xml, */*;q=0.8")
                setRequestProperty("User-Agent", "XPlayer2/1.0 (Android)")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
