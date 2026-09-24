package com.iptv.player.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcDeinterlacePolicyTest {

    @Test
    fun `hardware route keeps libVLC defaults everywhere`() {
        assertFalse(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = false, amlogicDecoder = true, is64Bit = false, remoteOverride = null))
        assertFalse(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = false, amlogicDecoder = true, is64Bit = false, remoteOverride = false))
    }

    @Test
    fun `software route on Amlogic or 32-bit devices runs without deinterlacing`() {
        assertTrue(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = true, amlogicDecoder = true, is64Bit = true, remoteOverride = null))
        assertTrue(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = true, amlogicDecoder = false, is64Bit = false, remoteOverride = null))
    }

    @Test
    fun `capable 64-bit non-Amlogic devices keep the deinterlacer`() {
        assertFalse(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = true, amlogicDecoder = false, is64Bit = true, remoteOverride = null))
    }

    @Test
    fun `remote key overrides the device rule in both directions`() {
        assertFalse(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = true, amlogicDecoder = true, is64Bit = false, remoteOverride = true))
        assertTrue(VlcDeinterlacePolicy.disableDeinterlace(softwareDecode = true, amlogicDecoder = false, is64Bit = true, remoteOverride = false))
    }
}
