/*
 * CrashRecoveryActivity.kt
 * Shown when the previous launch crashed before the home screen appeared (see
 * LaunchCrashGuard). Deliberately extends AppCompatActivity directly — NOT
 * BaseActivity — so the app-wide locale/density override is bypassed here; if that
 * override is itself the crash cause, this screen still opens. Surfaces the
 * captured crash log so the user can send it, and offers a guarded retry.
 */
package com.iptv.player.ui.recovery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.data.model.StreamFormat
import com.iptv.player.databinding.ActivityCrashRecoveryBinding
import com.iptv.player.ui.dashboard.DashboardActivity
import com.iptv.player.util.LaunchCrashGuard
import com.iptv.player.util.Logger
import com.iptv.player.util.PlaybackRouteMemory
import com.iptv.player.util.StabilityTelemetry
import kotlinx.coroutines.launch

class CrashRecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashRecoveryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen bypasses BaseActivity, so apply the app-wide no-sleep
        // policy here too: never let the device sleep while it is showing.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityCrashRecoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recoveryDevice.text = deviceInfo()
        binding.recoveryLog.text =
            Logger.recentText().ifBlank { getString(R.string.recovery_no_log) }

        binding.btnRecoveryShare.setOnClickListener { shareLog() }
        binding.btnRecoveryRetry.setOnClickListener { retry() }
        binding.btnRecoveryShare.requestFocus()

        maybeEnterSafeMode()
    }

    /**
     * Safe mode: after [LaunchCrashGuard.SAFE_MODE_THRESHOLD]+ consecutive launch
     * crashes, reset every risky playback setting to its default so a bad choice
     * (or a corrupted remembered value) can't keep the app in a boot-crash loop.
     * Idempotent — re-applying defaults on a further crash is harmless. Purely
     * best-effort: any failure here must never take down the recovery screen.
     */
    private fun maybeEnterSafeMode() {
        val streak = LaunchCrashGuard.crashStreak(this)
        if (streak < LaunchCrashGuard.SAFE_MODE_THRESHOLD) return
        binding.recoverySafeMode.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching {
                val settings = ServiceLocator.settings
                settings.setPlayerMode(PlayerMode.AUTO)
                settings.setDecoderMode(DecoderMode.AUTO)
                settings.setBufferMode(BufferMode.NORMAL)
                settings.setStreamFormat(StreamFormat.TS)
                settings.setAudioPassthrough(false)
                settings.setResumeOnLaunch(false)
                // A learned per-channel decode route may itself be the crasher.
                PlaybackRouteMemory.clear()
                Logger.w("Recovery", "Safe mode: player settings reset to defaults (streak=$streak)")
                StabilityTelemetry.record(
                    type = "safe_mode",
                    channel = null,
                    kind = null,
                    severity = "fatal",
                    detail = "consecutive launch crashes=$streak; player settings reset to defaults"
                )
            }.onFailure { Logger.w("Recovery", "Safe mode reset failed: ${it.message}") }
        }
    }

    private fun deviceInfo(): String {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · v$version"
    }

    private fun shareLog() {
        val uri: Uri? = Logger.shareableFile()?.let { file ->
            runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }.getOrNull()
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.recovery_share_subject))
            putExtra(Intent.EXTRA_TEXT, deviceInfo() + "\n\n" + Logger.recentText())
            if (uri != null) {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.recovery_share)))
        }
    }

    private fun retry() {
        // Re-arm the guard so a repeat crash is caught again, then try the home.
        LaunchCrashGuard.markLaunchStarted(this)
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }
}
