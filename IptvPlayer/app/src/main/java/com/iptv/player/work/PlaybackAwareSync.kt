package com.iptv.player.work

import android.content.Context
import com.iptv.player.data.ServiceLocator
import com.iptv.player.playback.core.IdleWorkResult
import com.iptv.player.playback.core.PlaybackResourceGovernor
import com.iptv.player.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal enum class BackgroundSyncOutcome {
    SUCCESS,
    DISABLED,
    RETRY,
    DEFERRED_FOR_PLAYBACK,
    ALREADY_RUNNING,
}

/** Pure retry-budget policy for WorkManager jobs on Android 14/15+. */
internal object BackgroundSyncRetryPolicy {
    const val MAX_RUN_ATTEMPTS = 4

    fun shouldRetry(runAttemptCount: Int): Boolean =
        runAttemptCount < MAX_RUN_ATTEMPTS
}

/** One process may contain periodic and deferred WorkManager instances at once. */
private val syncLease = Mutex()

/** Whether a background catalog sync currently runs in this process (diagnostics). */
internal fun backgroundSyncBusy(): Boolean = syncLease.isLocked

internal suspend fun runPlaybackAwareSync(context: Context): BackgroundSyncOutcome {
    // A second worker must not wait and then repeat the same complete catalog sync.
    if (!syncLease.tryLock()) return BackgroundSyncOutcome.ALREADY_RUNNING
    try {
        ServiceLocator.init(context.applicationContext)
        val settings = ServiceLocator.settings
        if (!settings.isAutoSyncEnabled()) return BackgroundSyncOutcome.DISABLED
        val config = settings.getSourceConfig() ?: return BackgroundSyncOutcome.DISABLED

        return when (
            val result = PlaybackResourceGovernor.runWhileIdle {
                ServiceLocator.repository.syncAll(config)
            }
        ) {
            is IdleWorkResult.Completed -> {
                if (result.value) BackgroundSyncOutcome.SUCCESS else BackgroundSyncOutcome.RETRY
            }
            IdleWorkResult.InterruptedByPlayback ->
                BackgroundSyncOutcome.DEFERRED_FOR_PLAYBACK
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        // Catch Throwable (including OutOfMemoryError on huge catalogs) so a
        // background worker never crashes the foreground playback process.
        Logger.w("BackgroundSync", "background catalog sync failed: ${t.javaClass.simpleName}")
        return BackgroundSyncOutcome.RETRY
    } finally {
        syncLease.unlock()
    }
}
