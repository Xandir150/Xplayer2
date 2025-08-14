package com.teleteh.xplayer2.data.network

import android.content.Context
import android.content.SharedPreferences

class SmbStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("smb_shares", Context.MODE_PRIVATE)

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
        prefs.edit().remove(name).apply()
    }
}
