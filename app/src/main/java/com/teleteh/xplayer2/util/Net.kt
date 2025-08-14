package com.teleteh.xplayer2.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object Net {
    suspend fun pingHttp(urlStr: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 2000
                readTimeout = 2000
            }
            val code = conn.responseCode
            conn.disconnect()
            // Some control endpoints return 405 to HEAD; treat < 500 as reachable
            return@withContext code in 200..499
        } catch (_: Exception) {
            // Fallback: try GET with small range
            try {
                val url = URL(urlStr)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-0")
                    connectTimeout = 2000
                    readTimeout = 2000
                }
                val code = conn.responseCode
                conn.disconnect()
                return@withContext code in 200..499
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }
}
