package com.iptv.player.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowEndUiBudgetTest {

    @Test
    fun `standard devices keep Coil defaults with a 128 MB disk cache`() {
        val policy = LowEndUiBudget.imageCache(compat = false, lowRamDevice = false)
        assertNull(policy.memoryCachePercent)
        assertTrue(policy.crossfade)
        assertEquals(128L * 1024 * 1024, policy.diskCacheBytes)
    }

    @Test
    fun `compat or low-RAM devices get a 10 percent cache and no crossfade`() {
        listOf(
            LowEndUiBudget.imageCache(compat = true, lowRamDevice = false),
            LowEndUiBudget.imageCache(compat = false, lowRamDevice = true),
        ).forEach { policy ->
            assertEquals(0.10, policy.memoryCachePercent!!, 1e-9)
            assertFalse(policy.crossfade)
            assertEquals(64L * 1024 * 1024, policy.diskCacheBytes)
        }
    }

    @Test
    fun `UI hidden trims on standard and clears on constrained devices`() {
        assertEquals(
            LowEndUiBudget.TrimAction.TRIM,
            LowEndUiBudget.trimAction(LowEndUiBudget.TRIM_MEMORY_UI_HIDDEN, constrained = false),
        )
        assertEquals(
            LowEndUiBudget.TrimAction.CLEAR,
            LowEndUiBudget.trimAction(LowEndUiBudget.TRIM_MEMORY_UI_HIDDEN, constrained = true),
        )
    }

    @Test
    fun `running low and above clears, moderate does nothing`() {
        assertEquals(
            LowEndUiBudget.TrimAction.CLEAR,
            LowEndUiBudget.trimAction(LowEndUiBudget.TRIM_MEMORY_RUNNING_LOW, constrained = false),
        )
        // TRIM_MEMORY_COMPLETE (80) is the strongest level.
        assertEquals(LowEndUiBudget.TrimAction.CLEAR, LowEndUiBudget.trimAction(80, constrained = false))
        // TRIM_MEMORY_RUNNING_MODERATE (5) is below the clearing threshold.
        assertEquals(LowEndUiBudget.TrimAction.NONE, LowEndUiBudget.trimAction(5, constrained = true))
    }

    @Test
    fun `compat doubles overlay refresh intervals`() {
        assertEquals(1_000L, LowEndUiBudget.refreshIntervalMs(1_000L, compat = false))
        assertEquals(2_000L, LowEndUiBudget.refreshIntervalMs(1_000L, compat = true))
        assertEquals(60_000L, LowEndUiBudget.refreshIntervalMs(30_000L, compat = true))
    }
}
