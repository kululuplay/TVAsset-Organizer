/*
 * LaunchCrashGuardTest.kt
 * Pure-JVM tests for the startup crash-loop guard: arming/disarming, the
 * consecutive-crash streak that triggers safe mode, and the reset on a
 * confirmed healthy launch. Context is a Mockito mock wired to an in-memory
 * SharedPreferences fake — no Robolectric, runs anywhere.
 */
package com.iptv.player.util

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LaunchCrashGuardTest {

    private lateinit var context: Context
    private lateinit var prefs: FakeSharedPreferences

    @Before
    fun setUp() {
        prefs = FakeSharedPreferences()
        context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
    }

    @Test
    fun `fresh install reports no crash and zero streak`() = runBlocking {
        assertFalse(LaunchCrashGuard.previousLaunchCrashed(context))
        assertEquals(0, LaunchCrashGuard.crashStreak(context))
    }

    @Test
    fun `armed guard that is never cleared counts as a crash`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        assertTrue(LaunchCrashGuard.previousLaunchCrashed(context))
    }

    @Test
    fun `successful launch clears the guard and the streak`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.markLaunchSucceeded(context)
        assertFalse(LaunchCrashGuard.previousLaunchCrashed(context))
        assertEquals(0, LaunchCrashGuard.crashStreak(context))
    }

    @Test
    fun `consuming a crash disarms the guard and increments the streak`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        val streak = LaunchCrashGuard.consumeCrashAndCountStreak(context)
        assertEquals(1, streak)
        assertEquals(1, LaunchCrashGuard.crashStreak(context))
        // Disarmed: the SAME crash must not be counted again on the next launch.
        assertFalse(LaunchCrashGuard.previousLaunchCrashed(context))
    }

    @Test
    fun `consecutive crashes reach the safe-mode threshold`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.consumeCrashAndCountStreak(context)
        LaunchCrashGuard.markLaunchStarted(context)
        val streak = LaunchCrashGuard.consumeCrashAndCountStreak(context)
        assertEquals(2, streak)
        assertTrue(streak >= LaunchCrashGuard.SAFE_MODE_THRESHOLD)
    }

    @Test
    fun `clean exit disarms the guard so the next launch is not a crash`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        // User pressed Back/Home on the splash, or the OS reclaimed the process.
        LaunchCrashGuard.markCleanExit(context)
        assertFalse(LaunchCrashGuard.previousLaunchCrashed(context))
        assertEquals(0, LaunchCrashGuard.crashStreak(context))
    }

    @Test
    fun `clean exit keeps an existing streak but does not grow it`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.consumeCrashAndCountStreak(context)
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.markCleanExit(context)
        assertFalse(LaunchCrashGuard.previousLaunchCrashed(context))
        // Not a healthy launch either: the streak is neither bumped nor reset.
        assertEquals(1, LaunchCrashGuard.crashStreak(context))
        // Two benign exits in a row never reach the safe-mode threshold.
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.markCleanExit(context)
        assertFalse(LaunchCrashGuard.previousLaunchCrashed(context))
        assertTrue(LaunchCrashGuard.crashStreak(context) < LaunchCrashGuard.SAFE_MODE_THRESHOLD)
    }

    @Test
    fun `re-arming after a clean exit guards the hand-off again`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.markCleanExit(context)
        LaunchCrashGuard.markLaunchStarted(context)
        assertTrue(LaunchCrashGuard.previousLaunchCrashed(context))
    }

    @Test
    fun `healthy launch between crashes breaks the streak`() = runBlocking {
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.consumeCrashAndCountStreak(context)
        // This launch completes fine…
        LaunchCrashGuard.markLaunchStarted(context)
        LaunchCrashGuard.markLaunchSucceeded(context)
        // …so a later crash starts counting from 1 again.
        LaunchCrashGuard.markLaunchStarted(context)
        assertEquals(1, LaunchCrashGuard.consumeCrashAndCountStreak(context))
    }
}
