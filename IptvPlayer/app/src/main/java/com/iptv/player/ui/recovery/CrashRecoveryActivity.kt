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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.iptv.player.R
import com.iptv.player.databinding.ActivityCrashRecoveryBinding
import com.iptv.player.ui.dashboard.DashboardActivity
import com.iptv.player.util.LaunchCrashGuard
import com.iptv.player.util.Logger

class CrashRecoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashRecoveryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashRecoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recoveryDevice.text = deviceInfo()
        binding.recoveryLog.text =
            Logger.recentText().ifBlank { getString(R.string.recovery_no_log) }

        binding.btnRecoveryShare.setOnClickListener { shareLog() }
        binding.btnRecoveryRetry.setOnClickListener { retry() }
        binding.btnRecoveryShare.requestFocus()
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
