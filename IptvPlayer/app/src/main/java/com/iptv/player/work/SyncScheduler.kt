/*
 * SyncScheduler.kt
 * Helpers to (re)schedule or cancel the periodic background sync. Uses a unique
 * periodic work request so toggling the setting simply updates the schedule.
 */
package com.iptv.player.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.iptv.player.playback.core.PlaybackResourceGovernor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object SyncScheduler {

    /** Schedules (or updates) the periodic sync. Interval is clamped to >= 1h. */
    fun schedule(context: Context, intervalHours: Int) {
        bindPlaybackGovernor(context)
        val hours = intervalHours.coerceAtLeast(1).toLong()
        val constraints = networkConstraints()

        val request = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        deferredPending.set(false)
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(SyncWorker.UNIQUE_NAME)
            cancelUniqueWork(DEFERRED_UNIQUE_NAME)
        }
    }

    /** Persist one missed run; unique work and a process lease prevent duplicates. */
    fun deferUntilPlaybackIdle(context: Context) {
        val app = context.applicationContext
        bindPlaybackGovernor(app)
        deferredPending.set(true)
        enqueueDeferred(
            context = app,
            policy = ExistingWorkPolicy.KEEP,
            delayMinutes = if (PlaybackResourceGovernor.isPlaybackActive) 15L else 0L,
        )
    }

    internal fun markDeferredComplete() {
        deferredPending.set(false)
    }

    private fun bindPlaybackGovernor(context: Context) {
        if (governorBound.compareAndSet(false, true)) {
            val app = context.applicationContext
            idleListener = PlaybackResourceGovernor.addIdleListener {
                if (deferredPending.get()) {
                    // Replace a backed-off attempt with an immediate unique run.
                    enqueueDeferred(app, ExistingWorkPolicy.REPLACE, delayMinutes = 0L)
                }
            }
        }
    }

    private fun enqueueDeferred(
        context: Context,
        policy: ExistingWorkPolicy,
        delayMinutes: Long,
    ) {
        val request = OneTimeWorkRequestBuilder<DeferredSyncWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .apply {
                if (delayMinutes > 0L) setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DEFERRED_UNIQUE_NAME,
            policy,
            request,
        )
    }

    private fun networkConstraints(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    @Volatile
    private var idleListener: AutoCloseable? = null
    private val governorBound = AtomicBoolean(false)
    private val deferredPending = AtomicBoolean(false)
    private const val DEFERRED_UNIQUE_NAME = "iptv_auto_sync_deferred"
}
