package com.iptv.player.player

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VlcSurfaceRetirementTest {
    @Test fun `blocked native owner and repeated timeout removal never reenter JNI`() {
        val retirement = VlcSurfaceRetirement()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val nativeCalls = AtomicInteger()
        val owner = Thread {
            entered.countDown()
            unblock.await()
            retirement.ownershipReleased()
        }
        owner.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(3) { retirement.requestRemoval { nativeCalls.incrementAndGet() } }
            assertEquals(0, nativeCalls.get())
        } finally {
            unblock.countDown()
            owner.join(1000)
        }
        assertFalse(owner.isAlive)
        assertEquals(1, nativeCalls.get())
        retirement.requestRemoval { nativeCalls.incrementAndGet() }
        retirement.ownershipReleased()
        assertEquals(1, nativeCalls.get())
    }

    @Test fun `release finishing before UI retirement drains exactly once`() {
        val retirement = VlcSurfaceRetirement()
        retirement.ownershipReleased()
        var count = 0
        retirement.requestRemoval { count++ }
        retirement.requestRemoval { count++ }
        assertEquals(1, count)
    }

    @Test fun `failed release boundaries cannot authorize removal`() {
        val retirement = VlcSurfaceRetirement()
        val result = VlcNativeCleanup.runFull({}, {}, { error("release failed") }, {})
        var removed = false
        retirement.requestRemoval { removed = true }
        if (result.ownershipDefinitivelyReleased) retirement.ownershipReleased()
        assertFalse(removed)
    }

    @Test fun `Activity destruction on active surface asks for worker cleanup once`() {
        val gate = VlcSurfaceCallbackGate()
        var callbacks = 0
        var losses = 0
        gate.dispatch { callbacks++ }
        gate.destroyed { losses++ }
        gate.dispatch { error("recreated surface must not reuse native owner") }
        gate.destroyed { losses++ }
        assertEquals(1, callbacks)
        assertEquals(1, losses)
    }

    @Test fun `planned stop suppresses all delayed lifecycle callbacks`() {
        val gate = VlcSurfaceCallbackGate()
        gate.retire()
        gate.dispatch { error("JNI on UI after stop reservation") }
        gate.destroyed { error("planned retirement is not an output failure") }
    }
}
