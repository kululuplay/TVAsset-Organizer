/*
 * AboutActivity.kt
 * Shows app name + version and checks GitHub Releases for a newer build. When an
 * update is available the "Update now" button streams the APK over OkHttp while
 * showing a live progress card (percent, downloaded/total size, speed and ETA),
 * then launches the system installer via FileProvider. A "Cancel" button keeps
 * D-pad focus during the download and lets the user abort. Requires the user to
 * allow "install unknown apps" the first time (handled gracefully on Android O+).
 */
package com.iptv.player.ui.settings

import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding
    private val checker by lazy { UpdateChecker(ServiceLocator.httpClient) }

    private var pendingUpdate: UpdateInfo? = null
    private var downloadFile: File? = null
    private var downloadJob: Job? = null
    private var activeCall: Call? = null

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
        binding.btnCancelUpdate.setOnClickListener { cancelDownload() }

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
        binding.updateProgressCard.visibility = View.GONE
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
        if (downloadJob?.isActive == true) return
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

    /**
     * Streams the APK over OkHttp into the app's external files dir, publishing
     * live progress to [showProgress]. Runs blocking IO on Dispatchers.IO; UI
     * updates hop back to the main thread. The active call + job are cancelled in
     * [cancelDownload] / onDestroy, and any partial file is deleted.
     */
    private fun downloadApk(apkUrl: String) {
        val dir = getExternalFilesDir(null)
        if (dir == null) {
            binding.aboutUpdateStatus.visibility = View.VISIBLE
            binding.aboutUpdateStatus.setText(R.string.about_update_failed)
            return
        }

        binding.btnUpdateNow.visibility = View.GONE
        binding.btnCheckUpdate.isEnabled = false
        binding.aboutUpdateStatus.visibility = View.GONE
        binding.updateProgressCard.visibility = View.VISIBLE
        binding.btnCancelUpdate.visibility = View.VISIBLE
        binding.btnCancelUpdate.requestFocus()
        showProgress(0L, -1L, 0.0)

        val outFile = File(dir, "update-${System.currentTimeMillis()}.apk")

        downloadJob = lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                var success = false
                try {
                    val request = Request.Builder().url(apkUrl).build()
                    val call = ServiceLocator.httpClient.newCall(request)
                    activeCall = call
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body ?: return@use
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            FileOutputStream(outFile).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var downloaded = 0L
                                val startNanos = System.nanoTime()
                                var lastNanos = startNanos
                                var lastBytes = 0L
                                var smoothedSpeed = 0.0
                                var read: Int
                                while (input.read(buffer).also { read = it } != -1) {
                                    if (!isActive) return@withContext false
                                    output.write(buffer, 0, read)
                                    downloaded += read
                                    val now = System.nanoTime()
                                    val dt = (now - lastNanos) / 1e9
                                    if (dt >= 0.2 || (total > 0 && downloaded == total)) {
                                        val inst = if (dt > 0) (downloaded - lastBytes) / dt else 0.0
                                        smoothedSpeed =
                                            if (smoothedSpeed == 0.0) inst
                                            else smoothedSpeed * 0.7 + inst * 0.3
                                        lastNanos = now
                                        lastBytes = downloaded
                                        val snapBytes = downloaded
                                        val snapSpeed = smoothedSpeed
                                        withContext(Dispatchers.Main) {
                                            showProgress(snapBytes, total, snapSpeed)
                                        }
                                    }
                                }
                                output.flush()
                            }
                        }
                        success = true
                    }
                    success
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                } finally {
                    activeCall = null
                    // Keep the file only on a clean success; otherwise (failure or
                    // cancellation) remove the partial download.
                    if (!success) runCatching { outFile.delete() }
                }
            }

            if (ok) {
                downloadFile = outFile
                onDownloadComplete()
            } else {
                binding.updateProgressCard.visibility = View.GONE
                binding.btnCancelUpdate.visibility = View.GONE
                binding.aboutUpdateStatus.visibility = View.VISIBLE
                binding.aboutUpdateStatus.setText(R.string.about_update_failed)
                binding.btnCheckUpdate.isEnabled = true
                binding.btnUpdateNow.visibility = View.VISIBLE
                binding.btnUpdateNow.requestFocus()
            }
        }
    }

    /** User aborted the download: stop the call/job and restore the buttons. */
    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        activeCall?.cancel()
        activeCall = null
        binding.updateProgressCard.visibility = View.GONE
        binding.btnCancelUpdate.visibility = View.GONE
        binding.btnCheckUpdate.isEnabled = true
        binding.aboutUpdateStatus.visibility = View.VISIBLE
        val info = pendingUpdate
        if (info != null) {
            binding.aboutUpdateStatus.text =
                getString(R.string.about_update_available, info.versionName)
            binding.btnUpdateNow.visibility = View.VISIBLE
            binding.btnUpdateNow.requestFocus()
        } else {
            binding.btnCheckUpdate.requestFocus()
        }
    }

    /** Updates the progress card. [total] <= 0 means the size is unknown. */
    private fun showProgress(downloaded: Long, total: Long, speedBytesPerSec: Double) {
        if (total > 0) {
            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            binding.updateProgressBar.isIndeterminate = false
            binding.updateProgressBar.progress = pct
            binding.updatePercent.text = String.format(Locale.getDefault(), "%d%%", pct)
            binding.updateSize.text = getString(
                R.string.about_download_size,
                formatBytes(downloaded),
                formatBytes(total)
            )
            val eta = if (speedBytesPerSec > 1) {
                formatDuration(((total - downloaded) / speedBytesPerSec).toLong())
            } else {
                "--:--"
            }
            binding.updateSpeedEta.text = getString(
                R.string.about_download_meta,
                formatBytes(speedBytesPerSec.toLong()),
                eta
            )
        } else {
            binding.updateProgressBar.isIndeterminate = true
            binding.updatePercent.text = getString(R.string.about_download_preparing)
            binding.updateSize.text = formatBytes(downloaded)
            binding.updateSpeedEta.text =
                if (speedBytesPerSec > 1) {
                    getString(
                        R.string.about_download_meta,
                        formatBytes(speedBytesPerSec.toLong()),
                        "--:--"
                    )
                } else {
                    ""
                }
        }
    }

    private fun onDownloadComplete() {
        val target = downloadFile
        if (target == null || !target.exists()) {
            binding.updateProgressCard.visibility = View.GONE
            binding.btnCancelUpdate.visibility = View.GONE
            binding.aboutUpdateStatus.visibility = View.VISIBLE
            binding.aboutUpdateStatus.setText(R.string.about_update_failed)
            binding.btnCheckUpdate.isEnabled = true
            return
        }
        binding.updateProgressBar.isIndeterminate = false
        binding.updateProgressBar.progress = 100
        binding.updatePercent.text = String.format(Locale.getDefault(), "%d%%", 100)
        binding.updateSize.text = getString(R.string.about_download_complete)
        binding.updateSpeedEta.text = ""
        binding.btnCancelUpdate.visibility = View.GONE
        binding.btnCheckUpdate.isEnabled = true
        if (!ApkInstaller.install(this, target)) {
            binding.updateProgressCard.visibility = View.GONE
            binding.aboutUpdateStatus.visibility = View.VISIBLE
            binding.aboutUpdateStatus.setText(R.string.about_update_failed)
        }
    }

    /** Human-readable byte count (B / KB / MB / GB). */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }

    /** Seconds -> "m:ss". */
    private fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        downloadJob = null
        activeCall?.cancel()
        activeCall = null
    }
}
