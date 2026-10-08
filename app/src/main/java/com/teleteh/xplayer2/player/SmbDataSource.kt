package com.teleteh.xplayer2.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.teleteh.xplayer2.data.network.SmbClient
import jcifs.SmbRandomAccess
import java.io.IOException

/** Reads a file from an SMB share with random access, so seeking does not re-read the file. */
@UnstableApi
class SmbDataSource(private val client: SmbClient) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: SmbRandomAccess? = null
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        try {
            val smb = client.open(dataSpec.uri.toString())
            val length = smb.length()
            val raf = smb.openRandomAccess("r")
            try {
                if (dataSpec.position > length) throw IOException("Position beyond end of file")
                raf.seek(dataSpec.position)
            } catch (e: Throwable) {
                // `file` is not assigned yet, so close() would never see this handle.
                try { raf.close() } catch (_: Throwable) { }
                throw e
            }
            file = raf
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else length - dataSpec.position
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = file!!.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** Sends `smb://` URIs to [SmbDataSource] and everything else to [fallback]. */
@UnstableApi
class SmbAwareDataSourceFactory(
    context: Context,
    private val fallback: DataSource.Factory
) : DataSource.Factory {
    private val client = SmbClient(context)

    override fun createDataSource(): DataSource = Switching(SmbDataSource(client), fallback.createDataSource())

    private class Switching(private val smb: DataSource, private val other: DataSource) : DataSource {
        private var active: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(l: TransferListener) {
            listeners += l
            smb.addTransferListener(l)
            other.addTransferListener(l)
        }

        override fun open(dataSpec: DataSpec): Long {
            val d = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) smb else other
            active = d
            return d.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() {
            try { active?.close() } finally { active = null }
        }
    }
}
