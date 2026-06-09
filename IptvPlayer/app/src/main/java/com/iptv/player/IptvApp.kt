/*
 * IptvApp
 * Application entry point. Wires up the singleton service locator so the rest of
 * the app can grab the database, repository, settings store and OkHttp client
 * without a full DI framework (keeps things lightweight for low-end devices).
 */
package com.iptv.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.StrictMode
import androidx.appcompat.app.AlertDialog
import com.iptv.player.data.ServiceLocator
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.screensaver.ScreensaverActivity
import com.iptv.player.ui.trailer.TrailerActivity
import com.iptv.player.util.AnnouncementCenter
import com.iptv.player.util.AnrWatchdog
import com.iptv.player.util.CrashReporter
import com.iptv.player.util.HeartbeatReporter
import com.iptv.player.util.Logger
import com.iptv.player.work.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class IptvApp : Application() {

    private var anrWatchdog: AnrWatchdog? = null

    /** Count of started (foreground) activities; drives heartbeat start/stop. */
    private var startedActivities = 0

    /** The currently-resumed activity — used to surface remote announcements. */
    @Volatile private var currentActivityRef: WeakReference<Activity>? = null

    /** Highest announcement id already shown this process (concurrency guard). */
    @Volatile private var shownAnnouncementId = 0L

    override fun onCreate() {
        super.onCreate()

        // Start logging + crash capture first so any failure during the rest of
        // startup is recorded.
        Logger.init(this)
        if (BuildConfig.DEBUG) enableStrictMode()

        ServiceLocator.init(this)

        // If the last run crashed, quietly ship the captured report so we can see
        // failures on users' devices (Fire TV / Sony) without any interaction.
        CrashReporter.uploadPendingIfAny(this)

        // Watch for main-thread freezes (ANRs) and record their stack to the log.
        anrWatchdog = AnrWatchdog().also { it.start() }

        // When a heartbeat brings a fresh announcement, try to show it right away
        // on whatever screen is in front (else it surfaces on the next safe resume).
        AnnouncementCenter.setListener {
            currentActivityRef?.get()?.let { maybeShowAnnouncement(it) }
        }

        // Live-device telemetry: ping the ops panel every minute WHILE foregrounded
        // so we can see who is watching right now (count, IP, model, version).
        // Gated on the started-activity count because Android TV keeps idle
        // processes alive for hours — a bare process loop would over-count "live".
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) HeartbeatReporter.start(this@IptvApp)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) HeartbeatReporter.stop()
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                maybeShowAnnouncement(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (currentActivityRef?.get() === activity) currentActivityRef = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Re-establish the periodic background sync if the user enabled it.
        CoroutineScope(Dispatchers.Default).launch {
            val settings = ServiceLocator.settings
            if (settings.isAutoSyncEnabled()) {
                SyncScheduler.schedule(this@IptvApp, settings.getAutoSyncHours())
            }
        }
    }

    /**
     * Show the pending remote announcement once, on a safe (non-player) screen.
     * Suppressed over the players / screensaver / trailer so we never interrupt
     * viewing — it surfaces on the next safe resume instead. Deduped by a persisted
     * id (across restarts) plus an in-memory guard (against concurrent shows).
     */
    private fun maybeShowAnnouncement(activity: Activity) {
        if (AnnouncementCenter.pending == null) return
        if (isSuppressedScreen(activity)) return
        CoroutineScope(Dispatchers.Main).launch {
            val ann = AnnouncementCenter.pending ?: return@launch
            if (!isShowable(activity)) return@launch
            val settings = ServiceLocator.settings
            if (ann.id <= settings.getLastShownAnnouncementId()) return@launch
            // Atomic last line of defence so two resumes can't both pop the dialog.
            if (!claimAnnouncement(ann.id)) return@launch
            settings.setLastShownAnnouncementId(ann.id)
            // The two DataStore calls above suspend (disk I/O); the activity may have
            // gone away meanwhile, so re-check before touching its window. id is
            // already persisted, so a missed show simply waits for the next resume.
            if (!isShowable(activity)) return@launch
            runCatching {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.announcement_title)
                    .setMessage(ann.message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure { Logger.w("IptvApp", "announcement dialog failed: ${it.message}") }
        }
    }

    @Synchronized
    private fun claimAnnouncement(id: Long): Boolean {
        if (id <= shownAnnouncementId) return false
        shownAnnouncementId = id
        return true
    }

    private fun isSuppressedScreen(activity: Activity): Boolean =
        activity is PlayerActivity ||
            activity is VodPlayerActivity ||
            activity is ScreensaverActivity ||
            activity is TrailerActivity

    /** [activity] is still the front, alive, and a safe screen for a dialog. */
    private fun isShowable(activity: Activity): Boolean =
        currentActivityRef?.get() === activity &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            !isSuppressedScreen(activity)

    /**
     * Debug-only: surface accidental disk/network work on the main thread during
     * development. Logged, not crashed, so it never affects shipped builds.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
    }
}
