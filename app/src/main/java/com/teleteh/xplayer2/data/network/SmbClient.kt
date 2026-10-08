package com.teleteh.xplayer2.data.network

import android.content.Context
import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import java.util.Properties

/** A folder or file inside a share. [uri] is a plain `smb://host/share/path` with no login in it. */
data class SmbEntry(val name: String, val uri: String, val isDirectory: Boolean, val size: Long)

/** The server refused the login: the caller should ask the user for one. */
class SmbLoginRequired(val host: String, cause: Throwable) : Exception(cause)

/**
 * Thin wrapper over jcifs-ng. All calls block on network I/O, so call them off the main thread.
 * The login comes from [SmbStorage] by host name, which is why URIs never carry a password.
 */
class SmbClient(context: Context) {
    private val storage = SmbStorage(context.applicationContext)

    fun list(uri: String): List<SmbEntry> {
        val host = SmbStorage.hostOf(uri) ?: throw IllegalArgumentException("No host in $uri")
        try {
            val dir = open(uri.withTrailingSlash())
            return dir.listFiles().orEmpty()
                .filter { !it.name.startsWith(".") && !it.name.endsWith("$/") }
                .map { f ->
                    val isDir = f.isDirectory
                    SmbEntry(
                        name = f.name.trimEnd('/'),
                        uri = plainUri(f),
                        isDirectory = isDir,
                        size = if (isDir) 0 else runCatching { f.length() }.getOrDefault(0)
                    )
                }
                .sortedWith(compareByDescending<SmbEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        } catch (e: SmbAuthException) {
            throw SmbLoginRequired(host, e)
        }
    }

    fun open(uri: String): SmbFile {
        val host = SmbStorage.hostOf(uri) ?: throw IllegalArgumentException("No host in $uri")
        return SmbFile(uri, context(storage.credentialsFor(host)))
    }

    private fun plainUri(f: SmbFile): String {
        val u = Uri.parse(f.url.toString())
        return u.buildUpon().encodedAuthority(u.encodedAuthority?.substringAfterLast('@')).build().toString()
    }

    private fun String.withTrailingSlash() = if (endsWith("/")) this else "$this/"

    companion object {
        /** Whether [name] looks like something the player can open. */
        fun isMedia(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS

        private val MEDIA_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
            "vob", "iso", "3gp", "ogv", "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav"
        )

        fun context(c: SmbCredentials): CIFSContext {
            val props = Properties().apply {
                setProperty("jcifs.smb.client.minVersion", "SMB1")
                setProperty("jcifs.smb.client.maxVersion", "SMB311")
                // Name lookup: DNS first, then NetBIOS, so both "nas.local" and "NAS" resolve.
                setProperty("jcifs.resolveOrder", "DNS,BCAST")
                setProperty("jcifs.smb.client.responseTimeout", "15000")
                setProperty("jcifs.smb.client.connTimeout", "8000")
                setProperty("jcifs.smb.client.rcv_buf_size", "1048576")
                setProperty("jcifs.smb.client.snd_buf_size", "1048576")
            }
            val base = BaseContext(PropertyConfiguration(props))
            return if (c.isGuest) base.withGuestCrendentials()
            else base.withCredentials(NtlmPasswordAuthenticator(c.domain, c.user, c.password))
        }
    }
}
