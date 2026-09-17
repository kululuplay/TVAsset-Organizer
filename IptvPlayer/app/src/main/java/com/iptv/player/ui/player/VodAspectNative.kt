/*
 * VodAspectNative.kt
 *
 * Architecture note (VOD player decomposition):
 *   Maps the app's [AspectRatio] onto the two VOD backends: [VodAspectMode] for
 *   Media3 and libVLC's aspect-ratio/scale model. FILL stretches to the container
 *   by using its own w:h as the display ratio; ZOOM crops to fill by scaling up
 *   using the current video dimensions. The scale arithmetic is pure so it can be
 *   unit-tested without libVLC.
 *
 * Moved verbatim from VodPlayerActivity.
 */
package com.iptv.player.ui.player

import com.iptv.player.data.model.AspectRatio
import com.iptv.player.player.vod.VodAspectMode
import org.videolan.libvlc.MediaPlayer

internal object VodAspectNative {

    fun asVodAspectMode(value: AspectRatio): VodAspectMode = when (value) {
        AspectRatio.ORIGINAL -> VodAspectMode.FIT
        AspectRatio.RATIO_16_9 -> VodAspectMode.FIXED_WIDTH
        AspectRatio.RATIO_4_3 -> VodAspectMode.FIXED_HEIGHT
        AspectRatio.FILL -> VodAspectMode.FILL
        AspectRatio.ZOOM -> VodAspectMode.ZOOM
    }

    /** Runs on the owner worker: libVLC setters are JNI. */
    fun apply(
        mp: MediaPlayer,
        selected: AspectRatio,
        containerWidth: Int,
        containerHeight: Int,
    ) {
        when (selected) {
            AspectRatio.ORIGINAL -> {
                mp.setAspectRatio(null)
                mp.setScale(0f)
            }
            AspectRatio.RATIO_16_9 -> {
                mp.setAspectRatio("16:9")
                mp.setScale(0f)
            }
            AspectRatio.RATIO_4_3 -> {
                mp.setAspectRatio("4:3")
                mp.setScale(0f)
            }
            AspectRatio.FILL -> {
                if (containerWidth > 0 && containerHeight > 0) {
                    mp.setAspectRatio("$containerWidth:$containerHeight")
                } else {
                    mp.setAspectRatio(null)
                }
                mp.setScale(0f)
            }
            AspectRatio.ZOOM -> {
                mp.setAspectRatio(null)
                mp.setScale(zoomScale(mp, containerWidth, containerHeight))
            }
        }
    }

    /** Scale factor that crops the video to fill the container, keeping its AR. */
    private fun zoomScale(mp: MediaPlayer, containerWidth: Int, containerHeight: Int): Float {
        val track = mp.currentVideoTrack ?: return 0f
        return zoomScale(track.width, track.height, containerWidth, containerHeight)
    }

    fun zoomScale(
        videoWidth: Int,
        videoHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
    ): Float {
        val vw = videoWidth
        val vh = videoHeight
        if (vw <= 0 || vh <= 0 || containerWidth <= 0 || containerHeight <= 0) return 0f
        // Absolute source-to-container scale on the larger axis crops only the
        // excess edge while preserving the source aspect ratio.
        val fill = maxOf(
            containerWidth.toFloat() / vw,
            containerHeight.toFloat() / vh,
        )
        return fill.takeIf { it > 0f } ?: 0f
    }
}
