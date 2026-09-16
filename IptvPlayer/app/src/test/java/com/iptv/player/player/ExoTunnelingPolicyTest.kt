package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoTunnelingPolicyTest {
    private val amlogic = listOf("OMX.amlogic.avc.decoder.awesome", "c2.android.avc.decoder")
    private val mediatek = listOf("OMX.MTK.VIDEO.DECODER.AVC", "c2.android.avc.decoder")

    @Test
    fun `tunneling is off without a remote opt-in`() {
        assertFalse(ExoTunnelingPolicy.shouldEnable(null, mediatek, false))
        assertFalse(ExoTunnelingPolicy.shouldEnable(false, mediatek, false))
    }

    @Test
    fun `opt-in enables tunneling only on non-Amlogic decoders`() {
        assertTrue(ExoTunnelingPolicy.shouldEnable(true, mediatek, false))
        assertFalse(ExoTunnelingPolicy.shouldEnable(true, amlogic, false))
        assertFalse(ExoTunnelingPolicy.shouldEnable(true, listOf("c2.amlogic.hevc.decoder"), false))
    }

    @Test
    fun `after the untunneled retry the stream stays untunneled`() {
        assertFalse(ExoTunnelingPolicy.shouldEnable(true, mediatek, true))
        assertTrue(ExoTunnelingPolicy.shouldRetryWithoutTunneling(true, false))
        assertFalse(ExoTunnelingPolicy.shouldRetryWithoutTunneling(true, true))
        assertFalse(ExoTunnelingPolicy.shouldRetryWithoutTunneling(false, false))
    }
}
