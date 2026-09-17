package com.iptv.player.ui.player

import com.iptv.player.data.model.AspectRatio
import com.iptv.player.player.vod.VodAspectMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VodAspectNativeTest {

    @Test
    fun `zoom scale crops on the larger axis and keeps the source ratio`() {
        // 16:9 source in a 4:3 container: height ratio wins (1080 -> 720 = 0.667),
        // width ratio would be 960/1920 = 0.5.
        assertEquals(720f / 1080f, VodAspectNative.zoomScale(1920, 1080, 960, 720), 1e-6f)
        // Square source in a wide container: width ratio wins.
        assertEquals(2f, VodAspectNative.zoomScale(500, 500, 1000, 600), 1e-6f)
    }

    @Test
    fun `zoom scale is zero (libVLC auto) for unknown dimensions`() {
        assertEquals(0f, VodAspectNative.zoomScale(0, 1080, 1920, 1080), 0f)
        assertEquals(0f, VodAspectNative.zoomScale(1920, 1080, 1920, 0), 0f)
    }

    @Test
    fun `every app aspect maps onto a Media3 mode`() {
        assertEquals(VodAspectMode.FIT, VodAspectNative.asVodAspectMode(AspectRatio.ORIGINAL))
        assertEquals(VodAspectMode.FIXED_WIDTH, VodAspectNative.asVodAspectMode(AspectRatio.RATIO_16_9))
        assertEquals(VodAspectMode.FIXED_HEIGHT, VodAspectNative.asVodAspectMode(AspectRatio.RATIO_4_3))
        assertEquals(VodAspectMode.FILL, VodAspectNative.asVodAspectMode(AspectRatio.FILL))
        assertEquals(VodAspectMode.ZOOM, VodAspectNative.asVodAspectMode(AspectRatio.ZOOM))
    }
}
