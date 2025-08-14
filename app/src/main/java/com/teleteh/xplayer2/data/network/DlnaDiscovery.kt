package com.teleteh.xplayer2.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

class DlnaDiscovery {
    fun discover(scope: CoroutineScope, onDevice: (NetworkItem.DlnaDevice) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                // Send M-SEARCH
                val socket = DatagramSocket()
                socket.soTimeout = 3000
                val searchRequest = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: ssdp:all\r\n\r\n").toByteArray()
                val addr = InetAddress.getByName("239.255.255.250")
                val packet = DatagramPacket(searchRequest, searchRequest.size, addr, 1900)
                socket.send(packet)

                val buf = ByteArray(8192)
                val seen = HashSet<String>()
                val start = System.currentTimeMillis()
                while (System.currentTimeMillis() - start < 5000) {
                    try {
                        val resp = DatagramPacket(buf, buf.size)
                        socket.receive(resp)
                        val text = String(resp.data, 0, resp.length, Charset.forName("UTF-8"))
                        val headers = parseHeaders(text)
                        val location = headers["location"] ?: continue
                        val usn = headers["usn"]
                        val key = (usn ?: location).lowercase(Locale.US)
                        if (seen.add(key)) {
                            val info = fetchDeviceInfo(location)
                            withContext(Dispatchers.Main) {
                                onDevice(NetworkItem.DlnaDevice(info.first ?: "", location, usn, info.second))
                            }
                        }
                    } catch (_: Exception) {
                        // ignore timeouts and parse errors
                    }
                }
                socket.close()
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        raw.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val k = line.substring(0, idx).trim().lowercase(Locale.US)
                val v = line.substring(idx + 1).trim()
                map[k] = v
            }
        }
        return map
    }

    private fun fetchDeviceInfo(location: String): Pair<String?, String?> {
        return try {
            val conn = URL(location).openConnection()
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.getInputStream().use { input ->
                val reader = BufferedReader(InputStreamReader(input))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                val xml = sb.toString()
                val name = run {
                    val start = xml.indexOf("<friendlyName>")
                    val end = xml.indexOf("</friendlyName>")
                    // "<friendlyName>" length is 14; use +14 to avoid cutting the first character
                    if (start >= 0 && end > start) xml.substring(start + 14, end) else null
                }
                // Try to extract first icon url from <iconList><icon><url>...</url>
                val iconUrl = run {
                    val iconStart = xml.indexOf("<iconList>")
                    val iconEnd = xml.indexOf("</iconList>")
                    if (iconStart >= 0 && iconEnd > iconStart) {
                        val block = xml.substring(iconStart, iconEnd)
                        val uStart = block.indexOf("<url>")
                        val uEnd = block.indexOf("</url>")
                        if (uStart >= 0 && uEnd > uStart) {
                            val rel = block.substring(uStart + 5, uEnd).trim()
                            try { URL(URL(location), rel).toString() } catch (_: Exception) { rel }
                        } else null
                    } else null
                }
                Pair(name, iconUrl)
            }
        } catch (_: Exception) {
            Pair(null, null)
        }
    }
}
