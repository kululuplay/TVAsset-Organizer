package com.iptv.player.work

import android.content.Context
import com.iptv.player.data.ServiceLocator
import com.iptv.player.playback.core.IdleWorkResult
import com.iptv.player.playback.core.PlaybackResourceGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal enum class BackgroundSyncOutcome {
    SUCCESS,
    DISABLED,
    RETRY,
    DEFERRED_FOR_PLAYBACK,
    ALREADY_RUNNING,
}

/** One process may contain periodic and deferred WorkManager instances at once. */
private val syncLease = Mutex()

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
    } catch (_: Exception) {
        return BackgroundSyncOutcome.RETRY
    } finally {
        syncLease.unlock()
    }
}
