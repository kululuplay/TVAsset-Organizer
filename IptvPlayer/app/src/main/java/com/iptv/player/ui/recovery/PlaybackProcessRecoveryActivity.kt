package com.iptv.player.ui.recovery

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import com.iptv.player.playback.android.PlaybackProcessRecovery
import com.iptv.player.ui.dashboard.DashboardActivity
import com.iptv.player.ui.home.HomeActivity
import com.iptv.player.ui.player.PlayerActivity
import com.iptv.player.ui.player.VodPlayerActivity
import com.iptv.player.ui.splash.SplashActivity
import java.io.File

/**
 * Runs in :playback_recovery, kills only the verified default app process, then
 * relaunches the explicit playback Intent. No provider URL is persisted or logged.
 */
class PlaybackProcessRecoveryActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

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
        when (verifyMainProcess(oldMainPid, oldMainStartTicks)) {
            MainProcessState.INVALID -> {
                finishAndRemoveTask()
                return
            }
            MainProcessState.GONE,
            MainProcessState.VERIFIED -> Unit
        }
        if (!PlaybackProcessRecovery.acknowledgeRecovery(this, requestedAtMs)) {
            finishAndRemoveTask()
            return
        }
        handler.post {
            if (
                verifyMainProcess(oldMainPid, oldMainStartTicks) ==
                MainProcessState.VERIFIED
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

    private fun verifyMainProcess(pid: Int, expectedStartTicks: Long): MainProcessState {
        if (pid <= 0 || pid == Process.myPid() || expectedStartTicks <= 0L) {
            return MainProcessState.INVALID
        }
        if (!File("/proc/$pid").exists()) return MainProcessState.GONE
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val managerProcess = manager?.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
        val commandLine = PlaybackProcessRecovery.readProcessCommandLine(pid)
        val startTicks = PlaybackProcessRecovery.readProcessStartTicks(pid)
        val uid = readProcessUid(pid)
        val managerIdentityVerified = managerProcess?.let { process ->
            process.processName == packageName && process.uid == Process.myUid()
        } == true
        val procIdentityVerified =
            commandLine == packageName && uid == Process.myUid()
        return if (
            startTicks == expectedStartTicks &&
            (managerIdentityVerified || procIdentityVerified)
        ) {
            MainProcessState.VERIFIED
        } else {
            MainProcessState.INVALID
        }
    }

    private fun waitForMainProcessExit(
        pid: Int,
        expectedStartTicks: Long,
        target: Intent?,
        attempt: Int,
    ) {
        val sameProcessStillAlive =
            PlaybackProcessRecovery.readProcessStartTicks(pid) == expectedStartTicks
        if (!sameProcessStillAlive) {
            relaunch(target)
            return
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
        super.onDestroy()
    }

    private enum class MainProcessState { VERIFIED, GONE, INVALID }

    companion object {
        const val EXTRA_MAIN_PID = "extra_main_pid"
        const val EXTRA_MAIN_START_TICKS = "extra_main_start_ticks"
        const val EXTRA_REQUESTED_AT_MS = "extra_requested_at_ms"
        const val EXTRA_TARGET_INTENT = "extra_target_intent"
        const val EXTRA_REASON = "extra_reason"
        private const val EXIT_POLL_MS = 100L
        private const val MAX_EXIT_POLLS = 20
        private val ALLOWED_TARGETS = setOf(
            SplashActivity::class.java.name,
            DashboardActivity::class.java.name,
            HomeActivity::class.java.name,
            PlayerActivity::class.java.name,
            VodPlayerActivity::class.java.name,
        )
    }
}
