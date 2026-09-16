package com.iptv.player.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Validates the same media connection; never opens a second probe stream. */
@OptIn(markerClass = [UnstableApi::class])
internal class DirectMediaDataSource(private val source: DataSource) : DataSource {
    private val prefix = ByteArray(32)
    private var prefixSize = 0
    private var prefixRead = 0

    class Factory(private val factory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = DirectMediaDataSource(factory.createDataSource())
    }

    override fun open(dataSpec: DataSpec): Long {
        prefixSize = 0
        prefixRead = 0
        MediaTransportPolicy.requireDirectMedia(dataSpec.uri.toString())
        try {
            val length = source.open(dataSpec)
            source.uri?.let { MediaTransportPolicy.requireDirectMedia(it.toString()) }
            val mime = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
            if (MediaTransportPolicy.isHlsContentType(mime)) throw MediaTransportPolicy.UnsupportedTransportException()
            // Read a tiny prefix from this connection, and return every byte to
            // the extractor. Applies to range requests as well as initial loads.
            while (prefixSize < prefix.size) {
                val count = source.read(prefix, prefixSize, prefix.size - prefixSize)
                if (count == C.RESULT_END_OF_INPUT) break
                if (count == 0) throw IOException("Media source made no progress")
                prefixSize += count
            }
            if (MediaTransportPolicy.isPlaylistHeader(prefix, prefixSize)) {
                throw MediaTransportPolicy.UnsupportedTransportException()
            }
            return length
        } catch (error: Exception) {
            runCatching { source.close() }
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (prefixRead < prefixSize) {
            val count = minOf(length, prefixSize - prefixRead)
            prefix.copyInto(buffer, offset, prefixRead, prefixRead + count)
            prefixRead += count
            return count
        }
        return source.read(buffer, offset, length)
    }

    override fun addTransferListener(listener: TransferListener) = source.addTransferListener(listener)
    override fun getUri(): Uri? = source.uri
    override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders
    override fun close() {
        prefixSize = 0
        prefixRead = 0
        source.close()
    }
}
