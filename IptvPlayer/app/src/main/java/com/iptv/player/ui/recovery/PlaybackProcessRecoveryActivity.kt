package com.iptv.player.ui.recovery

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.View
import com.iptv.player.playback.android.PlaybackProcessRecovery
import com.iptv.player.playback.core.ProcessRecoveryVerifier
import com.iptv.player.ui.dashboard.DashboardActivity
import com.iptv.player.ui.home.HomeActivity
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.splash.SplashActivity
import java.io.File

/**
 * Runs in :playback_recovery, kills only the verified default app process, then
 * relaunches the explicit playback Intent. No provider URL is persisted or logged.
 *
 * The main process is recognised through the Binder token it sends along
 * ([EXTRA_MAIN_TOKEN]): the kernel reports the death of its owner, which is the
 * only evidence available on production devices, where /proc/<pid> of a
 * non-debuggable sibling process is invisible (see [ProcessRecoveryVerifier]).
 */
class PlaybackProcessRecoveryActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var mainToken: IBinder? = null

    @Volatile
    private var mainTokenDied = false
    private val deathRecipient = IBinder.DeathRecipient { mainTokenDied = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Color.BLACK)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val oldMainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        val oldMainStartTicks = intent.getLongExtra(EXTRA_MAIN_START_TICKS, -1L)
        val requestedAtMs = intent.getLongExtra(EXTRA_REQUESTED_AT_MS, -1L)
        val target = targetIntentOrNull()
        mainToken = intent.getBundleExtra(EXTRA_MAIN_TOKEN)?.getBinder(KEY_MAIN_TOKEN)
        mainToken?.let { token ->
            // linkToDeath fails only when the owner process is already dead.
            mainTokenDied = runCatching { token.linkToDeath(deathRecipient, 0) }.isFailure
        }
        when (verifyMainProcess(oldMainPid, oldMainStartTicks)) {
            ProcessRecoveryVerifier.State.INVALID -> {
                finishAndRemoveTask()
                return
            }
            ProcessRecoveryVerifier.State.GONE,
            ProcessRecoveryVerifier.State.VERIFIED -> Unit
        }
        if (!PlaybackProcessRecovery.acknowledgeRecovery(this, requestedAtMs)) {
            finishAndRemoveTask()
            return
        }
        handler.post {
            if (
                verifyMainProcess(oldMainPid, oldMainStartTicks) ==
                ProcessRecoveryVerifier.State.VERIFIED
            ) {
                Process.killProcess(oldMainPid)
            }
            waitForMainProcessExit(
                oldMainPid,
                oldMainStartTicks,
                target,
                attempt = 0,
            )
        }
    }

    private fun verifyMainProcess(pid: Int, expectedStartTicks: Long): ProcessRecoveryVerifier.State =
        ProcessRecoveryVerifier.decide(evidence(pid, expectedStartTicks))

    private fun evidence(pid: Int, expectedStartTicks: Long): ProcessRecoveryVerifier.Evidence {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val managerProcess = runCatching { manager?.runningAppProcesses }
            .getOrNull()
            ?.firstOrNull { it.pid == pid }
        return ProcessRecoveryVerifier.Evidence(
            pid = pid,
            ownPid = Process.myPid(),
            tokenAlive = mainToken?.let { token -> !mainTokenDied && token.isBinderAlive },
            signalProbe = probeSignal(pid),
            expectedStartTicks = expectedStartTicks,
            observedStartTicks = PlaybackProcessRecovery.readProcessStartTicks(pid),
            managerProcessName = managerProcess?.processName,
            managerUid = managerProcess?.uid,
            procCommandLine = PlaybackProcessRecovery.readProcessCommandLine(pid),
            procUid = readProcessUid(pid),
            packageName = packageName,
            ownUid = Process.myUid(),
        )
    }

    /** kill(pid, 0): existence and ownership of a pid without reading /proc. */
    private fun probeSignal(pid: Int): ProcessRecoveryVerifier.SignalProbe = try {
        Os.kill(pid, 0)
        ProcessRecoveryVerifier.SignalProbe.EXISTS
    } catch (e: ErrnoException) {
        when (e.errno) {
            OsConstants.ESRCH -> ProcessRecoveryVerifier.SignalProbe.NO_SUCH_PROCESS
            OsConstants.EPERM -> ProcessRecoveryVerifier.SignalProbe.NOT_OURS
            else -> ProcessRecoveryVerifier.SignalProbe.UNAVAILABLE
        }
    } catch (e: Throwable) {
        ProcessRecoveryVerifier.SignalProbe.UNAVAILABLE
    }

    private fun waitForMainProcessExit(
        pid: Int,
        expectedStartTicks: Long,
        target: Intent?,
        attempt: Int,
    ) {
        when (verifyMainProcess(pid, expectedStartTicks)) {
            ProcessRecoveryVerifier.State.GONE -> {
                relaunch(target)
                return
            }
            ProcessRecoveryVerifier.State.INVALID -> {
                // The pid no longer describes the process we were asked to
                // replace; never reopen a provider socket on a guess.
                finishAndRemoveTask()
                return
            }
            ProcessRecoveryVerifier.State.VERIFIED -> Unit
        }
        if (attempt >= MAX_EXIT_POLLS) {
            // Never reopen a provider socket until kernel death is proven.
            finishAndRemoveTask()
            return
        }
        handler.postDelayed(
            {
                waitForMainProcessExit(
                    pid,
                    expectedStartTicks,
                    target,
                    attempt + 1,
                )
            },
            EXIT_POLL_MS,
        )
    }

    private fun readProcessUid(pid: Int): Int? = runCatching {
        File("/proc/$pid/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("Uid:") }
                ?.substringAfter("Uid:")
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.firstOrNull()
                ?.toIntOrNull()
        }
    }.getOrNull()

    private fun relaunch(requested: Intent?) {
        val target = requested
            ?.takeIf {
                it.component?.packageName == packageName &&
                    it.component?.className?.let(ALLOWED_TARGETS::contains) == true
            }
            ?: Intent(this, SplashActivity::class.java)
        target.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        runCatching { startActivity(target) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(this, SplashActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                        },
                    )
                }
            }
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }

    @Suppress("DEPRECATION")
    private fun targetIntentOrNull(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_TARGET_INTENT)
        }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mainToken?.let { token -> runCatching { token.unlinkToDeath(deathRecipient, 0) } }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MAIN_PID = "extra_main_pid"
        const val EXTRA_MAIN_START_TICKS = "extra_main_start_ticks"
        const val EXTRA_REQUESTED_AT_MS = "extra_requested_at_ms"
        const val EXTRA_TARGET_INTENT = "extra_target_intent"
        const val EXTRA_REASON = "extra_reason"

        /** Bundle extra carrying the main process token under [KEY_MAIN_TOKEN]. */
        const val EXTRA_MAIN_TOKEN = "extra_main_token"
        const val KEY_MAIN_TOKEN = "token"
        private const val EXIT_POLL_MS = 100L
        private const val MAX_EXIT_POLLS = 50
        private val ALLOWED_TARGETS = setOf(
            SplashActivity::class.java.name,
            DashboardActivity::class.java.name,
            HomeActivity::class.java.name,
            PlayerActivity::class.java.name,
            VodPlayerActivity::class.java.name,
        )
    }
}
