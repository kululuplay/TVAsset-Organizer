package com.iptv.player.player

import com.iptv.player.player.SurfaceValidationPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceValidationPolicyTest {

    @Test
    fun authoritativePixelsOnAFullscreenSurfaceAreSampled() {
        assertEquals(Mode.PIXEL_COPY, SurfaceValidationPolicy.mode(previewSurface = false, pixelsAdvisory = false))
        assertEquals("authoritative", SurfaceValidationPolicy.reason(previewSurface = false, pixelsAdvisory = false))
    }

    @Test
    fun advisoryPixelsSkipTheReadback() {
        // Fire TV default (LiveSurfaceVerdictPolicy.Trust.ALWAYS) or no alternative
        // video stage: the copy could not change the route, so it is not made.
        assertEquals(Mode.NATIVE_FRAMES, SurfaceValidationPolicy.mode(previewSurface = false, pixelsAdvisory = true))
        assertEquals("pixel verdict advisory", SurfaceValidationPolicy.reason(previewSurface = false, pixelsAdvisory = true))
    }

    @Test
    fun thePreviewThumbnailIsNeverSampled() {
        assertEquals(Mode.NATIVE_FRAMES, SurfaceValidationPolicy.mode(previewSurface = true, pixelsAdvisory = false))
        assertEquals(Mode.NATIVE_FRAMES, SurfaceValidationPolicy.mode(previewSurface = true, pixelsAdvisory = true))
        assertEquals("preview surface", SurfaceValidationPolicy.reason(previewSurface = true, pixelsAdvisory = false))
    }
}
