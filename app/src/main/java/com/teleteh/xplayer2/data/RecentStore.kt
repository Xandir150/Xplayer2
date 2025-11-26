package com.teleteh.xplayer2.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

class RecentStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(): List<RecentEntry> {
        val json = prefs.getString(KEY_ITEMS, "")?.trim().orEmpty()
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val raw = (0 until arr.length()).mapNotNull { idx ->
                arr.optJSONObject(idx)?.toEntry()
            }
            // Sanitize titles for better display
            var mutated = false
            val fixed = raw.map { e ->
                val nice = ensureNiceTitle(e)
                if (nice != e.title) {
                    mutated = true
                    e.copy(title = nice)
                } else e
            }.sortedByDescending { it.lastPlayedAt }
            if (mutated) {
                val out = JSONArray()
                fixed.forEach { out.put(it.toJson()) }
                prefs.edit().putString(KEY_ITEMS, out.toString()).apply()
            }
            fixed
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun upsert(newEntry: RecentEntry, maxItems: Int = 50) {
        val current = getAll().toMutableList()
        val existingIdx = current.indexOfFirst { it.uri == newEntry.uri }
        if (existingIdx >= 0) current.removeAt(existingIdx)
        current.add(0, newEntry)
        while (current.size > maxItems) current.removeLast()
        val arr = JSONArray()
        current.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun find(uri: String): RecentEntry? = getAll().firstOrNull { it.uri == uri }

    fun delete(uri: String) {
        val current = getAll().toMutableList()
        val idx = current.indexOfFirst { it.uri == uri }
        if (idx >= 0) {
            current.removeAt(idx)
            val arr = JSONArray()
            current.forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
        }
    }

    fun clear() {
        prefs.edit().putString(KEY_ITEMS, JSONArray().toString()).apply()
    }

    private fun JSONObject.toEntry(): RecentEntry? = try {
        val uriStr = getString("uri")
        val storedSourceType = optString("sourceType", "").takeIf { it.isNotBlank() }
        val sourceType = storedSourceType?.let { 
            try { SourceType.valueOf(it) } catch (_: Throwable) { null }
        } ?: RecentEntry.detectSourceType(Uri.parse(uriStr))
        
        RecentEntry(
            uri = uriStr,
            title = optString("title", ""),
            lastPositionMs = optLong("lastPositionMs", 0L),
            durationMs = optLong("durationMs", 0L),
            lastPlayedAt = optLong("lastPlayedAt", 0L),
            framePacking = if (has("framePacking") && !isNull("framePacking")) optInt("framePacking") else null,
            sbsEnabled = if (has("sbsEnabled") && !isNull("sbsEnabled")) optBoolean("sbsEnabled") else null,
            sbsShiftEnabled = if (has("sbsShiftEnabled") && !isNull("sbsShiftEnabled")) optBoolean("sbsShiftEnabled") else null,
            sourceType = sourceType
        )
    } catch (_: Throwable) {
        null
    }

    private fun RecentEntry.toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("title", title)
        put("lastPositionMs", lastPositionMs)
        put("durationMs", durationMs)
        put("lastPlayedAt", lastPlayedAt)
        if (framePacking != null) put("framePacking", framePacking)
        if (sbsEnabled != null) put("sbsEnabled", sbsEnabled)
        if (sbsShiftEnabled != null) put("sbsShiftEnabled", sbsShiftEnabled)
        if (sourceType != null) put("sourceType", sourceType.name)
    }

    private fun ensureNiceTitle(entry: RecentEntry): String {
        // If title already looks like a normal filename, keep it
        val t = entry.title
        if (t.isNotBlank() && !t.startsWith("msf:") && !t.startsWith("content:")) return t
        val uri = runCatching { Uri.parse(entry.uri) }.getOrNull() ?: return t
        // Try DISPLAY_NAME
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
                ?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        val name = c.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        } catch (_: Throwable) { /* ignore */
        }
        val last = uri.lastPathSegment ?: entry.uri
        return runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrElse { last }
    }

    companion object {
        private const val PREFS = "recent_store"
        private const val KEY_ITEMS = "items"
    }
}

