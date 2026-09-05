package com.iptv.player.player

import java.util.Locale

/**
 * Limits repeated GPU readbacks without changing codec routing or stream quality.
 * A small PixelCopy destination can still copy the full decoder buffer on older
 * Amlogic gralloc implementations. Dimensions must describe the video source,
 * not the SurfaceView: a 1080p display can host a 4K decoder buffer.
 *
 * Decoder and format callbacks can arrive in either order. Once costly output
 * has been identified, keep startup-only validation for the rest of that stream;
 * late or adaptive metadata must not restart periodic readbacks. A new stream
 * clears both facts with [reset].
 */
internal class SurfaceReadbackPolicy(
    private val constrainedDevice: Boolean,
) {
    enum class Mode { CONTINUOUS, STARTUP_ONLY }

    var mode: Mode = initialMode()
        private set

    private var selectedDecoderName: String? = null
    private var videoWidth = 0
    private var videoHeight = 0

    /** True only for a change from one known decoder to another in this stream. */
    fun onVideoDecoderInitialized(decoderName: String): Boolean {
        val name = decoderName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return false
        val changed = selectedDecoderName?.let { it != name } == true
        selectedDecoderName = name
        updateMode()
        return changed
    }

    /** True only when an already-known valid source size changes in this stream. */
    fun onVideoFormat(width: Int, height: Int): Boolean {
        // Unknown metadata must not erase an already identified source format.
        if (width <= 0 || height <= 0) return false
        val changed = videoWidth > 0 && videoHeight > 0 &&
            (videoWidth != width || videoHeight != height)
        videoWidth = width
        videoHeight = height
        updateMode()
        return changed
    }

    fun reset() {
        selectedDecoderName = null
        videoWidth = 0
        videoHeight = 0
        mode = initialMode()
    }

    private fun updateMode() {
        val amlogicDecoder = selectedDecoderName?.let {
            it.startsWith("omx.amlogic.") || it.startsWith("c2.amlogic.")
        } == true
        val uhdVideo = videoWidth > 0 && videoHeight > 0 &&
            (maxOf(videoWidth, videoHeight) >= UHD_LONG_SIDE ||
                minOf(videoWidth, videoHeight) >= UHD_SHORT_SIDE)
        if (constrainedDevice || (amlogicDecoder && uhdVideo)) {
            mode = Mode.STARTUP_ONLY
        }
    }

    private fun initialMode(): Mode =
        if (constrainedDevice) Mode.STARTUP_ONLY else Mode.CONTINUOUS

    private companion object {
        private const val UHD_LONG_SIDE = 3_840
        private const val UHD_SHORT_SIDE = 2_160
    }
}
