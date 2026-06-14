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
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.iptv.player.data.ServiceLocator
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.screensaver.ScreensaverActivity
import com.iptv.player.ui.trailer.TrailerActivity
import com.iptv.player.util.AbnormalExitDetector
import com.iptv.player.util.AnnouncementCenter
import com.iptv.player.util.AnrWatchdog
import com.iptv.player.util.CrashReporter
import com.iptv.player.util.HeartbeatReporter
import com.iptv.player.util.Logger
import com.iptv.player.util.RequestReporter
import com.iptv.player.util.ResolvedRequestCenter
import com.iptv.player.util.StabilityTelemetry
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

    /** Guards against two dialogs (announcement + resolved) stacking on one resume. */
    @Volatile private var dialogInFlight = false

    override fun onCreate() {
        super.onCreate()

        // Start logging + crash capture first so any failure during the rest of
        // startup is recorded.
        Logger.init(this)
        if (BuildConfig.DEBUG) enableStrictMode()

        ServiceLocator.init(this)

        // Load any stability events spooled by a previous run so they ship on the
        // next beat (set up before we record the abnormal-exit check below).
        StabilityTelemetry.init(this)

        // Sample the pending-crash flag BEFORE the uploader runs — uploadPendingIfAny
        // clears the marker asynchronously, which would otherwise race the abnormal-
        // exit check below and mis-attribute a real Java crash as an abnormal exit.
        val hadPendingCrash = Logger.hasPendingCrash()

        // If the last run crashed, quietly ship the captured report so we can see
        // failures on users' devices (Fire TV / Sony) without any interaction.
        CrashReporter.uploadPendingIfAny(this)

        // Native libVLC/MediaCodec crashes and OOM-kills never reach the Java crash
        // handler. If the previous session died mid-playback without a clean stop
        // (and there was no Java crash explaining it), record one suspected
        // abnormal-exit event.
        AbnormalExitDetector.detectAndReport(this, hadPendingCrash)

        // Watch for main-thread freezes (ANRs): log the stack and ship one (rate-
        // limited) telemetry event so chronic freezes are visible in the field.
        anrWatchdog = AnrWatchdog(
            onAnr = { stack ->
                StabilityTelemetry.record(type = "anr", severity = "fatal", detail = stack)
            }
        ).also { it.start() }

        // When a heartbeat brings a fresh announcement, try to show it right away
        // on whatever screen is in front (else it surfaces on the next safe resume).
        AnnouncementCenter.setListener {
            currentActivityRef?.get()?.let { maybeShowAnnouncement(it) }
        }

        // When a heartbeat reports the operator resolved one of this device's
        // requests, pop a confirmation on the next safe screen (then ACK it).
        ResolvedRequestCenter.setListener {
            currentActivityRef?.get()?.let { maybeShowResolved(it) }
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
                if (startedActivities == 0) {
                    HeartbeatReporter.stop()
                    // Orderly background stop: the next launch must NOT read this as
                    // an abnormal exit. (A native crash / OOM-kill skips this path.)
                    AbnormalExitDetector.markCleanStop(this@IptvApp)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                maybeShowAnnouncement(activity)
                maybeShowResolved(activity)
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
     * id (across restarts) plus an in-memory guard (against concurrent shows). The
     * shared dialog slot is claimed FIRST so it can't stack with a resolved popup.
     */
    private fun maybeShowAnnouncement(activity: Activity) {
        if (AnnouncementCenter.pending == null) return
        if (isSuppressedScreen(activity)) return
        CoroutineScope(Dispatchers.Main).launch {
            val ann = AnnouncementCenter.pending ?: return@launch
            if (!isShowable(activity)) return@launch
            val settings = ServiceLocator.settings
            if (ann.id <= settings.getLastShownAnnouncementId()) return@launch
            // Grab the one dialog slot before consuming anything; if a resolved
            // popup is already up, bail and re-deliver on the next safe resume.
            if (!claimDialog()) return@launch
            if (!claimAnnouncement(ann.id)) { releaseDialog(); return@launch }
            settings.setLastShownAnnouncementId(ann.id)
            // The DataStore write above suspends (disk I/O); the activity may have
            // gone away meanwhile, so re-check before touching its window.
            if (!isShowable(activity)) { releaseDialog(); return@launch }
            runCatching {
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_announcement, null, false)
                view.findViewById<TextView>(R.id.annMessage).text = ann.message
                val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
                    .setView(view)
                    .create()
                val ok = view.findViewById<View>(R.id.annOkButton)
                ok.setOnClickListener { dialog.dismiss() }
                dialog.setOnDismissListener { releaseDialog() }
                dialog.show()
                ok.requestFocus()
            }.onFailure {
                releaseDialog()
                Logger.w("IptvApp", "announcement dialog failed: ${it.message}")
            }
        }
    }

    /**
     * Show the newest just-resolved request once, on a safe screen, then ACK it so
     * the server stops re-sending. Mirrors the announcement flow: same suppression,
     * same single dialog slot. Dedup is in-memory (ResolvedRequestCenter) backed by
     * the server's `notified` flag, so an un-shown one re-arrives on the next
     * heartbeat — surviving player-suppression, resume races and process death.
     */
    private fun maybeShowResolved(activity: Activity) {
        if (ResolvedRequestCenter.nextUnshown() == null) return
        if (isSuppressedScreen(activity)) return
        CoroutineScope(Dispatchers.Main).launch {
            val rr = ResolvedRequestCenter.nextUnshown() ?: return@launch
            if (!isShowable(activity)) return@launch
            if (!claimDialog()) return@launch
            val shown = runCatching {
                val view = LayoutInflater.from(activity)
                    .inflate(R.layout.dialog_resolved, null, false)
                val isContent = rr.type == "channel" || rr.type == "movie" || rr.type == "series"
                view.findViewById<TextView>(R.id.resolvedTitle).setText(
                    if (isContent) R.string.resolved_title_added else R.string.resolved_title_complaint
                )
                view.findViewById<TextView>(R.id.resolvedMessage).setText(
                    if (isContent) R.string.resolved_body_added else R.string.resolved_body_complaint
                )
                view.findViewById<TextView>(R.id.resolvedOriginal).text = rr.message
                val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
                    .setView(view)
                    .create()
                val ok = view.findViewById<View>(R.id.resolvedOkButton)
                ok.setOnClickListener { dialog.dismiss() }
                dialog.setOnDismissListener { releaseDialog() }
                dialog.show()
                ok.requestFocus()
                true
            }.getOrElse {
                releaseDialog()
                Logger.w("IptvApp", "resolved dialog failed: ${it.message}")
                false
            }
            if (shown) {
                // Mark + ACK only after a real show; a failed ACK is retried by the
                // heartbeat loop (server keeps listing it until notified=true).
                ResolvedRequestCenter.markShown(rr.id)
                runCatching { RequestReporter.ack(this@IptvApp, listOf(rr.id)) }
            }
        }
    }

    @Synchronized
    private fun claimDialog(): Boolean {
        if (dialogInFlight) return false
        dialogInFlight = true
        return true
    }

    @Synchronized
    private fun releaseDialog() {
        dialogInFlight = false
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
