package com.iptv.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceReadbackPolicyTest {

    @Test
    fun `unconstrained device starts with continuous sampling`() {
        assertEquals(SurfaceReadbackPolicy.Mode.CONTINUOUS, policy().mode)
    }

    @Test
    fun `constrained device stays startup only across metadata and reset`() {
        val policy = SurfaceReadbackPolicy(constrainedDevice = true)
        assertStartupOnly(policy)
        policy.onVideoDecoderInitialized("c2.qti.hevc.decoder")
        policy.onVideoFormat(1_920, 1_080)
        assertStartupOnly(policy)
        policy.reset()
        assertStartupOnly(policy)
    }

    @Test
    fun `observed Amlogic HEVC UHD source uses startup only`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoFormat(3_840, 2_160)
        assertStartupOnly(policy)
    }

    @Test
    fun `codec two Amlogic prefix is case insensitive`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("  C2.AMLOGIC.HEVC.DECODER  ")
        policy.onVideoFormat(3_840, 2_160)
        assertStartupOnly(policy)
    }

    @Test
    fun `decoder metadata alone does not constrain full HD`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.avc.decoder.awesome2")
        assertContinuous(policy)
        policy.onVideoFormat(1_920, 1_080)
        assertContinuous(policy)
    }

    @Test
    fun `source UHD alone does not constrain an unknown decoder`() {
        val policy = policy()
        policy.onVideoFormat(3_840, 2_160)
        assertContinuous(policy)
    }

    @Test
    fun `source metadata can arrive before decoder initialization`() {
        val policy = policy()
        policy.onVideoFormat(3_840, 2_160)
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        assertStartupOnly(policy)
    }

    @Test
    fun `late UHD format switches a previously full HD stream`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoFormat(1_920, 1_080)
        assertContinuous(policy)
        policy.onVideoFormat(3_840, 2_160)
        assertStartupOnly(policy)
    }

    @Test
    fun `unknown format does not erase UHD before decoder metadata arrives`() {
        val policy = policy()
        policy.onVideoFormat(3_840, 2_160)
        policy.onVideoFormat(-1, -1)
        policy.onVideoFormat(0, 0)
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        assertStartupOnly(policy)
    }

    @Test
    fun `both dimensions must be positive before identifying UHD`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoFormat(3_840, -1)
        policy.onVideoFormat(0, 2_160)
        assertContinuous(policy)
    }

    @Test
    fun `UHD thresholds are inclusive and orientation independent`() {
        for ((width, height) in listOf(3_840 to 1_080, 3_000 to 2_160, 2_160 to 3_840)) {
            val policy = policy()
            policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
            policy.onVideoFormat(width, height)
            assertStartupOnly(policy)
        }
    }

    @Test
    fun `sub UHD dimensions retain continuous sampling`() {
        for ((width, height) in listOf(3_839 to 2_159, 2_560 to 1_440, 1_920 to 2_160)) {
            val policy = policy()
            policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
            policy.onVideoFormat(width, height)
            assertContinuous(policy)
        }
    }

    @Test
    fun `modern other vendor UHD decoder remains continuous`() {
        for (decoder in listOf("c2.qti.hevc.decoder", "OMX.MTK.VIDEO.DECODER.HEVC", "c2.android.hevc.decoder")) {
            val policy = policy()
            policy.onVideoDecoderInitialized(decoder)
            policy.onVideoFormat(3_840, 2_160)
            assertContinuous(policy)
        }
    }

    @Test
    fun `vendor identification requires exact hardware decoder namespace`() {
        for (decoder in listOf("amlogic", "OMX.amlogic", "OMX.amlogicish.hevc.decoder", "c2.vendor.amlogic.decoder")) {
            val policy = policy()
            policy.onVideoDecoderInitialized(decoder)
            policy.onVideoFormat(3_840, 2_160)
            assertContinuous(policy)
        }
    }

    @Test
    fun `startup only remains sticky after smaller format or another decoder`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoFormat(3_840, 2_160)
        policy.onVideoFormat(1_920, 1_080)
        policy.onVideoDecoderInitialized("c2.qti.hevc.decoder")
        assertStartupOnly(policy)
    }

    @Test
    fun `latest known format is used until costly output has been identified`() {
        val policy = policy()
        policy.onVideoFormat(3_840, 2_160)
        policy.onVideoFormat(1_920, 1_080)
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        assertContinuous(policy)
    }

    @Test
    fun `blank decoder metadata does not erase selected hardware decoder`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoDecoderInitialized("  ")
        policy.onVideoFormat(3_840, 2_160)
        assertStartupOnly(policy)
    }

    @Test
    fun `reset clears both decoder and source facts for next stream`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoFormat(3_840, 2_160)
        assertStartupOnly(policy)
        policy.reset()
        assertContinuous(policy)
        policy.onVideoFormat(3_840, 2_160)
        assertContinuous(policy)
        policy.reset()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        assertContinuous(policy)
    }

    @Test
    fun `first and repeated normalized decoder names are not transitions`() {
        val policy = policy()
        assertFalse(policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2"))
        assertFalse(policy.onVideoDecoderInitialized("  omx.AMLOGIC.HEVC.decoder.awesome2  "))
        assertFalse(policy.onVideoDecoderInitialized(""))
        assertFalse(policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2"))
    }

    @Test
    fun `decoder change is reported once independently of video format`() {
        val policy = policy()
        assertFalse(policy.onVideoDecoderInitialized("OMX.amlogic.avc.decoder.awesome2"))
        assertTrue(policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2"))
        assertFalse(policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2"))
    }

    @Test
    fun `first duplicate and unknown source dimensions are not transitions`() {
        val policy = policy()
        assertFalse(policy.onVideoFormat(-1, -1))
        assertFalse(policy.onVideoFormat(1_920, 1_080))
        assertFalse(policy.onVideoFormat(1_920, 1_080))
        assertFalse(policy.onVideoFormat(0, 2_160))
        assertFalse(policy.onVideoFormat(3_840, -1))
        assertFalse(policy.onVideoFormat(1_920, 1_080))
    }

    @Test
    fun `source size change is reported once independently of decoder`() {
        val policy = policy()
        assertFalse(policy.onVideoFormat(1_920, 1_080))
        assertTrue(policy.onVideoFormat(3_840, 2_160))
        assertFalse(policy.onVideoFormat(3_840, 2_160))
        assertTrue(policy.onVideoFormat(1_920, 1_080))
    }

    @Test
    fun `exact dimension and orientation changes are reported even within UHD`() {
        val policy = policy()
        assertFalse(policy.onVideoFormat(3_840, 2_160))
        assertTrue(policy.onVideoFormat(4_096, 2_160))
        assertTrue(policy.onVideoFormat(2_160, 4_096))
        assertFalse(policy.onVideoFormat(2_160, 4_096))
    }

    @Test
    fun `simultaneous decoder and source transitions preserve sticky mode`() {
        val policy = policy()
        assertFalse(policy.onVideoDecoderInitialized("OMX.amlogic.avc.decoder.awesome2"))
        assertFalse(policy.onVideoFormat(1_920, 1_080))
        assertTrue(policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2"))
        assertTrue(policy.onVideoFormat(3_840, 2_160))
        assertStartupOnly(policy)
        assertTrue(policy.onVideoFormat(1_920, 1_080))
        assertTrue(policy.onVideoDecoderInitialized("c2.qti.avc.decoder"))
        assertStartupOnly(policy)
    }

    @Test
    fun `source first simultaneous transition also selects startup only`() {
        val policy = policy()
        assertFalse(policy.onVideoFormat(1_920, 1_080))
        assertFalse(policy.onVideoDecoderInitialized("c2.qti.avc.decoder"))
        assertTrue(policy.onVideoFormat(3_840, 2_160))
        assertContinuous(policy)
        assertTrue(policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2"))
        assertStartupOnly(policy)
    }

    @Test
    fun `reset makes next stream metadata first observations not transitions`() {
        val policy = policy()
        policy.onVideoDecoderInitialized("OMX.amlogic.hevc.decoder.awesome2")
        policy.onVideoFormat(3_840, 2_160)
        policy.reset()
        assertFalse(policy.onVideoDecoderInitialized("c2.qti.avc.decoder"))
        assertFalse(policy.onVideoFormat(1_920, 1_080))
        assertContinuous(policy)
    }

    private fun policy() = SurfaceReadbackPolicy(constrainedDevice = false)

    private fun assertStartupOnly(policy: SurfaceReadbackPolicy) {
        assertEquals(SurfaceReadbackPolicy.Mode.STARTUP_ONLY, policy.mode)
    }

    private fun assertContinuous(policy: SurfaceReadbackPolicy) {
        assertEquals(SurfaceReadbackPolicy.Mode.CONTINUOUS, policy.mode)
    }
}
