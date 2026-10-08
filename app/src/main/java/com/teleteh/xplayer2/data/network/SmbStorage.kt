package com.teleteh.xplayer2.data.network

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Login for one SMB server. An empty [user] means a guest connection. */
data class SmbCredentials(val user: String = "", val password: String = "", val domain: String = "") {
    val isGuest: Boolean get() = user.isBlank()
}

class SmbStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smb_shares", Context.MODE_PRIVATE)

    // Kept apart from the share list so that file stays a plain name -> URI map. The URI never
    // carries the password, so it can go to the Recent list and to the player's intent safely.
    private val credPrefs: SharedPreferences =
        context.getSharedPreferences("smb_credentials", Context.MODE_PRIVATE)

    fun getAll(): List<NetworkItem.SmbShare> {
        return prefs.all.entries
            .filter { it.value is String }
            .map { (name, uri) -> NetworkItem.SmbShare(name, uri as String) }
            .sortedBy { it.name.lowercase() }
    }

    fun addOrUpdate(name: String, uri: String) {
        prefs.edit().putString(name, uri).apply()
    }

    fun remove(name: String) {
        val uri = prefs.getString(name, null)
        prefs.edit().remove(name).apply()
        // Forget the login when no other share uses the same server.
        val host = uri?.let { hostOf(it) } ?: return
        if (getAll().none { hostOf(it.uri) == host }) credPrefs.edit().remove(host).apply()
    }

    fun saveCredentials(host: String, c: SmbCredentials) {
        val json = JSONObject().put("u", c.user).put("p", c.password).put("d", c.domain)
        credPrefs.edit().putString(host.lowercase(), json.toString()).apply()
    }

    fun credentialsFor(host: String): SmbCredentials {
        val raw = credPrefs.getString(host.lowercase(), null) ?: return SmbCredentials()
        return try {
            val j = JSONObject(raw)
            SmbCredentials(j.optString("u"), j.optString("p"), j.optString("d"))
        } catch (_: Exception) {
            SmbCredentials()
        }
    }

    companion object {
        fun hostOf(uri: String): String? =
            android.net.Uri.parse(uri).host?.lowercase()
    }
}
