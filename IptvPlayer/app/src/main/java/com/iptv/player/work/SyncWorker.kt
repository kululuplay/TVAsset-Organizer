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
import com.iptv.player.data.ServiceLocator

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The worker may run in a fresh process, so make sure DI is ready.
        ServiceLocator.init(applicationContext)
        val settings = ServiceLocator.settings

        if (!settings.isAutoSyncEnabled()) return Result.success()
        val config = settings.getSourceConfig() ?: return Result.success()

        return try {
            val ok = ServiceLocator.repository.syncAll(config)
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "iptv_auto_sync"
    }
}
