package com.iptv.player.player

/**
 * Whether the live Exo engine samples the SurfaceView with PixelCopy to prove
 * its first picture, or trusts Media3's own rendered-frame cadence.
 *
 * A PixelCopy is a GPU readback of the decoder output. On weak sticks it stalls
 * the hardware video pipeline for the duration of the copy, which shows up as
 * dropped frames exactly while the viewer judges the channel. Where the pixel
 * verdict could not change the route anyway (it is advisory: Fire TV, or no
 * alternative video stage for this stream), or where the picture is only the
 * Home preview thumbnail, the readback buys nothing and is skipped entirely;
 * sustained native frames (or the validation deadline) decide instead.
 * Where the verdict is authoritative, PixelCopy validates the first output
 * only; sampling stops after the first healthy frame and resumes only for a
 * new output (surface or decoder change).
 */
internal object SurfaceValidationPolicy {

    enum class Mode {
        /** Sample the surface until the first healthy frame (or a verdict). */
        PIXEL_COPY,

        /** No GPU readback on this stream; native frame cadence proves video. */
        NATIVE_FRAMES,
    }

    fun mode(previewSurface: Boolean, pixelsAdvisory: Boolean): Mode =
        if (previewSurface || pixelsAdvisory) Mode.NATIVE_FRAMES else Mode.PIXEL_COPY

    fun reason(previewSurface: Boolean, pixelsAdvisory: Boolean): String = when {
        previewSurface -> "preview surface"
        pixelsAdvisory -> "pixel verdict advisory"
        else -> "authoritative"
    }
}
