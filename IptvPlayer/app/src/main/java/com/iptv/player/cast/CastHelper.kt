/*
 * CastHelper.kt
 * Thin, defensive wrapper around the Cast SDK. Every entry point is guarded so
 * the app never crashes on devices without Google Play services (most TV boxes).
 * Casting is primarily useful when the app is installed on a phone/tablet.
 */
package com.iptv.player.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage

object CastHelper {

    /** True when Google Play services (and thus the Cast SDK) are usable here. */
    fun isAvailable(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (t: Throwable) {
        false
    }

    fun castContext(context: Context): CastContext? = try {
        if (isAvailable(context)) CastContext.getSharedInstance(context) else null
    } catch (t: Throwable) {
        null
    }

    /** True when a Cast device is currently connected. */
    fun isConnected(context: Context): Boolean =
        castContext(context)?.sessionManager?.currentCastSession?.isConnected == true

    /**
     * Loads a stream onto the connected Cast device. Returns false when no session
     * is active or the SDK is unavailable. Note: the default receiver plays
     * HLS/MP4/WebM; raw MPEG-TS live streams generally will not cast.
     */
    fun loadMedia(
        context: Context,
        url: String,
        title: String,
        imageUrl: String?,
        isLive: Boolean
    ): Boolean = try {
        val client = castContext(context)?.sessionManager?.currentCastSession?.remoteMediaClient
        if (client == null) {
            false
        } else {
            val metadata = MediaMetadata(
                if (isLive) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE
            ).apply {
                putString(MediaMetadata.KEY_TITLE, title)
                imageUrl?.takeIf { it.isNotBlank() }?.let { addImage(WebImage(Uri.parse(it))) }
            }
            val mediaInfo = MediaInfo.Builder(url)
                .setStreamType(
                    if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED
                )
                .setContentType(guessMime(url))
                .setMetadata(metadata)
                .build()
            client.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(mediaInfo)
                    .setAutoplay(true)
                    .build()
            )
            true
        }
    } catch (t: Throwable) {
        false
    }

    private fun guessMime(url: String): String {
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.endsWith(".m3u8") -> "application/x-mpegURL"
            lower.endsWith(".mpd") -> "application/dash+xml"
            lower.endsWith(".mkv") -> "video/x-matroska"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
    }
}
