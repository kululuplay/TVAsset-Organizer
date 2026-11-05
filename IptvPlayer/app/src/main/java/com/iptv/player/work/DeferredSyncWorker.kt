package com.iptv.player.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Persistent one-shot retry created when periodic work collides with playback. */
class DeferredSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (runPlaybackAwareSync(applicationContext)) {
        BackgroundSyncOutcome.SUCCESS,
        BackgroundSyncOutcome.DISABLED,
        BackgroundSyncOutcome.ALREADY_RUNNING -> {
            SyncScheduler.markDeferredComplete()
            Result.success()
        }
        BackgroundSyncOutcome.RETRY ->
            if (BackgroundSyncRetryPolicy.shouldRetry(runAttemptCount)) {
                Result.retry()
            } else {
                SyncScheduler.markDeferredComplete()
                Result.failure()
            }
        BackgroundSyncOutcome.DEFERRED_FOR_PLAYBACK -> {
            SyncScheduler.deferUntilPlaybackIdle(applicationContext)
            if (BackgroundSyncRetryPolicy.shouldRetry(runAttemptCount)) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }
}
