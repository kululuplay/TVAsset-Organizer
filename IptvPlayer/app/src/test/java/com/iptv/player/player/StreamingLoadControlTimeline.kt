package com.iptv.player.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** A real network period is required for Media3 to choose its streaming buffer policy. */
@OptIn(markerClass = [UnstableApi::class])
internal fun streamingLoadControlTimeline(live: Boolean): Timeline {
    // Uri.parse is an Android stub in these plain JVM tests.
    val uri = mock(Uri::class.java).also {
        `when`(it.scheme).thenReturn("https")
        `when`(it.toString()).thenReturn("https://example.test/test-media")
    }
    val mediaItem = MediaItem.Builder().setUri(uri).build()
    return SinglePeriodTimeline(
        if (live) C.TIME_UNSET else 120_000_000L,
        !live,
        live,
        live,
        null,
        mediaItem,
    )
}
