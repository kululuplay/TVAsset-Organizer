/*
 * SyncWorker.kt
 * Background worker that refreshes the playlist + EPG (and VOD/series for Xtream)
 * on a schedule so the guide and channel list stay current without the user
 * opening the app. Driven by WorkManager; see [SyncScheduler].
 */
package com.iptv.player.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (runPlaybackAwareSync(applicationContext)) {
            BackgroundSyncOutcome.SUCCESS,
            BackgroundSyncOutcome.DISABLED,
            BackgroundSyncOutcome.ALREADY_RUNNING -> Result.success()
            BackgroundSyncOutcome.RETRY -> Result.retry()
            BackgroundSyncOutcome.DEFERRED_FOR_PLAYBACK -> {
                // Periodic work succeeds so it keeps its normal cadence; a unique,
                // persistent one-shot performs this missed run after playback.
                SyncScheduler.deferUntilPlaybackIdle(applicationContext)
                Result.success()
            }
        }
    }

    companion object {
        const val UNIQUE_NAME = "iptv_auto_sync"
    }
}
