/*
 * AboutActivity.kt
 * Shows app name + version and checks GitHub Releases for a newer build. When an
 * update is available the "Update now" button downloads the APK (DownloadManager)
 * and launches the system installer via FileProvider. Requires the user to allow
 * "install unknown apps" the first time (handled gracefully on Android O+).
 */
package com.iptv.player.ui.settings

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityAboutBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.update.ApkInstaller
import com.iptv.player.update.UpdateChecker
import com.iptv.player.update.UpdateInfo
import com.iptv.player.update.UpdateResult
import kotlinx.coroutines.launch
import java.io.File

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val checker by lazy { UpdateChecker(ServiceLocator.httpClient) }

    private var pendingUpdate: UpdateInfo? = null
    private var downloadId: Long = -1L
    private var downloadFile: File? = null
    private var downloadReceiver: BroadcastReceiver? = null

    companion object {
        /** When passed as true, the screen runs the update check immediately on open. */
        const val EXTRA_AUTO_CHECK = "extra_auto_check"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnUpdateNow.setOnClickListener { startUpdate() }

        // Arrived from the launch-time prompt: run the check straight away so the
        // user lands on a focused "Update now" button.
        if (intent.getBooleanExtra(EXTRA_AUTO_CHECK, false)) {
            checkForUpdate()
        }
    }

    private fun checkForUpdate() {
        binding.aboutUpdateStatus.visibility = View.VISIBLE
        binding.aboutUpdateStatus.setText(R.string.about_checking)
        binding.btnUpdateNow.visibility = View.GONE
        binding.btnCheckUpdate.isEnabled = false

        lifecycleScope.launch {
            val result = checker.check(BuildConfig.VERSION_NAME)
            binding.btnCheckUpdate.isEnabled = true
            when (result) {
                is UpdateResult.Available -> {
                    pendingUpdate = result.info
                    binding.aboutUpdateStatus.text =
                        getString(R.string.about_update_available, result.info.versionName)
                    binding.btnUpdateNow.visibility = View.VISIBLE
                    binding.btnUpdateNow.requestFocus()
                }
                UpdateResult.UpToDate -> {
                    pendingUpdate = null
                    binding.aboutUpdateStatus.setText(R.string.about_up_to_date)
                }
                UpdateResult.Failed -> {
                    pendingUpdate = null
                    binding.aboutUpdateStatus.setText(R.string.about_update_failed)
                }
            }
        }
    }

    private fun startUpdate() {
        val info = pendingUpdate ?: return
        val apkUrl = info.apkUrl
        if (apkUrl.isNullOrBlank()) {
            // No APK asset attached — open the release page in a browser instead.
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)))
            }
            return
        }
        if (!ApkInstaller.canRequestInstall(this)) {
            Toast.makeText(this, R.string.about_install_permission, Toast.LENGTH_LONG).show()
            ApkInstaller.openInstallPermissionSettings(this)
            return
        }
        downloadApk(apkUrl)
    }

    private fun downloadApk(apkUrl: String) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val fileName = "update-${System.currentTimeMillis()}.apk"
        downloadFile = File(getExternalFilesDir(null), fileName)
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle(getString(R.string.app_name))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(this@AboutActivity, null, fileName)
            setMimeType("application/vnd.android.package-archive")
        }
        registerDownloadReceiver()
        downloadId = manager.enqueue(request)
        binding.aboutUpdateStatus.setText(R.string.about_download_started)
        binding.btnUpdateNow.isEnabled = false
    }

    private fun registerDownloadReceiver() {
        if (downloadReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                onDownloadComplete()
            }
        }
        downloadReceiver = receiver
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun onDownloadComplete() {
        binding.btnUpdateNow.isEnabled = true
        val target = downloadFile
        if (target == null || !target.exists()) {
            binding.aboutUpdateStatus.setText(R.string.about_update_failed)
            return
        }
        if (!ApkInstaller.install(this, target)) {
            binding.aboutUpdateStatus.setText(R.string.about_update_failed)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadReceiver?.let { runCatching { unregisterReceiver(it) } }
        downloadReceiver = null
    }
}
