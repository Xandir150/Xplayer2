package com.teleteh.xplayer2.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists "remembered" network containers (a folder or a video list — never a single video) so the
 * Sources tab can show them again next time. Modeled on [SmbStorage], but a container needs a
 * {type, title, url} triple, so we store a single JSON array under one key instead of one pref each.
 *
 * Entries are de-duplicated by [normalize]d url (lowercased, trailing slash trimmed). Newest-first:
 * re-adding an existing url moves it to the front and refreshes its title.
 */
class WebSourceStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("web_sources", Context.MODE_PRIVATE)

    fun getAll(): List<NetworkItem.WebSource> = read()

    /** Add a container (or move an existing one to the front, refreshing its title). */
    fun addOrUpdate(type: WebSourceType, title: String, url: String) {
        val norm = normalize(url)
        if (norm.isEmpty()) return
        val list = read().filter { normalize(it.url) != norm }.toMutableList()
        list.add(0, NetworkItem.WebSource(type, title.ifBlank { url }, url))
        write(list)
    }

    fun remove(url: String) {
        val norm = normalize(url)
        write(read().filter { normalize(it.url) != norm })
    }

    private fun read(): List<NetworkItem.WebSource> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = runCatching { WebSourceType.valueOf(o.optString("type")) }.getOrNull()
                    ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NetworkItem.WebSource(type, o.optString("title").ifBlank { url }, url)
            }
        }.getOrElse { Log.w(TAG, "Failed to read web sources", it); emptyList() }
    }

    private fun write(list: List<NetworkItem.WebSource>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("type", it.type.name).put("title", it.title).put("url", it.url))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun normalize(url: String): String = url.trim().lowercase().trimEnd('/')

    companion object {
        private const val TAG = "WebSourceStore"
        private const val KEY = "list"
    }
}
