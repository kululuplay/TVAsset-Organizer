package com.iptv.player.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener

/**
 * DefaultHttpDataSource follows same-protocol redirects itself. Cross-protocol
 * following stays disabled; when an HTTP response explicitly upgrades to HTTPS,
 * this wrapper reopens exactly once at the secure target. A later downgrade is
 * rejected by the fresh delegate and never requested automatically: the stream
 * path carries the line credentials, which must never travel over cleartext
 * because a redirect said so. Shared by the live (ExoPlayerEngine) and VOD
 * (Media3VodEngine) data sources.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class UpgradeOnlyHttpDataSourceFactory(
    private val userAgent: String,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
    private val allowHttpToHttpsRedirects: Boolean,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = UpgradeOnlyHttpDataSource(
        sourceFactory = {
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(connectTimeoutMs)
                .setReadTimeoutMs(readTimeoutMs)
                .setAllowCrossProtocolRedirects(false)
                .createDataSource()
        },
        allowHttpToHttpsRedirects = allowHttpToHttpsRedirects,
    )
}

/**
 * Wraps one [HttpDataSource] per open; [sourceFactory] builds the replacement
 * after an http -> https upgrade, so listeners and request properties the
 * factory installs apply to the secure connection as well.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class UpgradeOnlyHttpDataSource(
    private val sourceFactory: () -> HttpDataSource,
    private val allowHttpToHttpsRedirects: Boolean,
) : DataSource {
    private val transferListeners = ArrayList<TransferListener>()
    private var source: HttpDataSource = newSource()

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        source.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        return try {
            source.open(dataSpec)
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            val location = error.headerFields.entries
                .firstOrNull { (name, _) -> name.equals("Location", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
            val target = if (allowHttpToHttpsRedirects) {
                VodHttpRedirectPolicy.httpsUpgradeTarget(
                    originalUrl = dataSpec.uri.toString(),
                    status = error.responseCode,
                    location = location,
                )
            } else {
                null
            } ?: throw error

            runCatching { source.close() }
            source = newSource()
            source.open(dataSpec.withUri(Uri.parse(target)))
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        source.read(buffer, offset, length)

    override fun getUri(): Uri? = source.uri

    override fun getResponseHeaders(): Map<String, List<String>> = source.responseHeaders

    override fun close() {
        source.close()
    }

    private fun newSource(): HttpDataSource = sourceFactory().also { next ->
        transferListeners.forEach(next::addTransferListener)
    }
}
