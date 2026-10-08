package com.teleteh.xplayer2.util

/**
 * A URL that is safe to write to logcat: scheme, host and path only.
 *
 * Share links and CDN URLs carry their secret in the query (`public_key`, signatures, tokens) or in
 * the user-info part, and logcat is readable by anything holding a bug report. The query and
 * fragment are replaced by a marker so a log line still shows that one was present.
 */
fun redactUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    val schemeEnd = url.indexOf("://")
    val authStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val cut = url.indexOfAny(charArrayOf('?', '#'), authStart)
    var out = if (cut >= 0) url.substring(0, cut) else url
    val at = out.indexOf('@', authStart)
    val slash = out.indexOf('/', authStart)
    if (at >= 0 && (slash < 0 || at < slash)) out = out.substring(0, authStart) + out.substring(at + 1)
    return if (cut >= 0) "$out?…" else out
}

fun redactUrl(uri: android.net.Uri?): String = redactUrl(uri?.toString())
