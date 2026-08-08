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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.databinding.ActivityAboutBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.update.ApkInstaller
import com.iptv.player.update.ApkInstallValidator
import com.iptv.player.update.ApkValidationFailure
import com.iptv.player.update.ApkValidationResult
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
    private var downloadExpectedBytes: Long = -1L

    /**
     * Set when we arrived from the launch-time prompt (EXTRA_AUTO_CHECK). It makes
     * the screen start the download automatically as soon as the check confirms an
     * update, so the user does not have to press "Update now" a second time. Reset
     * once consumed so later manual checks keep the explicit button flow.
     */
    private var autoDownload = false

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
        ViewCompat.setAccessibilityHeading(binding.aboutTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, getString(R.string.about_title))
        binding.btnCheckUpdate.post { binding.btnCheckUpdate.requestFocus() }

        // Arrived from the launch-time prompt: run the check straight away and,
        // once it confirms an update, start the download automatically so the user
        // does not have to press "Update now" again.
        if (intent.getBooleanExtra(EXTRA_AUTO_CHECK, false)) {
            autoDownload = true
            checkForUpdate()
        }
    }

    private fun checkForUpdate() {
        if (!binding.btnCheckUpdate.isClickable) return
        binding.aboutUpdateStatus.visibility = View.VISIBLE
        binding.aboutUpdateStatus.setText(R.string.about_checking)
        binding.aboutUpdateStatus.setTextColor(
            ContextCompat.getColor(this, R.color.text_secondary),
        )
        binding.btnUpdateNow.visibility = View.GONE
        binding.updateProgressCard.visibility = View.GONE
        setChecking(true)

        lifecycleScope.launch {
            val result = try {
                checker.check(BuildConfig.VERSION_NAME)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                UpdateResult.Failed
            }
            setChecking(false)
            when (result) {
                is UpdateResult.Available -> {
                    pendingUpdate = result.info
                    binding.aboutUpdateStatus.text =
                        getString(R.string.about_update_available, result.info.versionName)
                    binding.aboutUpdateStatus.setTextColor(
                        ContextCompat.getColor(this@AboutActivity, R.color.accent),
                    )
                    binding.btnUpdateNow.visibility = View.VISIBLE
                    binding.btnUpdateNow.requestFocus()
                    // Came from the launch prompt: kick off the download right away
                    // so the user does not have to confirm a second time.
                    if (autoDownload) {
                        autoDownload = false
                        startUpdate()
                    }
                }
                UpdateResult.UpToDate -> {
                    pendingUpdate = null
                    binding.aboutUpdateStatus.setText(R.string.about_up_to_date)
                    binding.aboutUpdateStatus.setTextColor(
                        ContextCompat.getColor(this@AboutActivity, R.color.success),
                    )
                    binding.btnCheckUpdate.requestFocus()
                }
                UpdateResult.Failed -> {
                    pendingUpdate = null
                    binding.aboutUpdateStatus.setText(R.string.about_update_failed)
                    binding.aboutUpdateStatus.setTextColor(
                        ContextCompat.getColor(this@AboutActivity, R.color.danger),
                    )
                    binding.btnCheckUpdate.requestFocus()
                }
            }
        }
    }

    private fun setChecking(checking: Boolean) {
        // Do not disable a focused TV control: Android would move focus to an
        // arbitrary view. Clickability plus the guard above prevents re-entry.
        binding.btnCheckUpdate.isClickable = !checking
        binding.btnCheckUpdate.alpha = if (checking) 0.68f else 1f
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
            binding.aboutUpdateStatus.setTextColor(
                ContextCompat.getColor(this, R.color.danger),
            )
            return
        }

        // Delete APKs left behind by previous updates. A successful download is
        // kept (it is handed to the system installer) but never cleaned up after,
        // so without this purge every update added another ~30-40 MB file to the
        // app's external storage and its on-device size kept swelling.
        runCatching {
            dir.listFiles { f -> f.name.startsWith("update-") && f.name.endsWith(".apk") }
                ?.forEach { it.delete() }
        }

        binding.btnUpdateNow.visibility = View.GONE
        binding.btnCheckUpdate.visibility = View.GONE
        binding.aboutUpdateStatus.visibility = View.GONE
        binding.updateProgressCard.visibility = View.VISIBLE
        binding.btnCancelUpdate.visibility = View.VISIBLE
        binding.btnCancelUpdate.requestFocus()
        showProgress(0L, -1L, 0.0)

        val outFile = File(dir, "update-${System.currentTimeMillis()}.apk")
        downloadExpectedBytes = -1L

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
                        downloadExpectedBytes = total
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
                                if (total > 0L && downloaded != total) {
                                    throw java.io.IOException(
                                        "Incomplete APK: expected=$total actual=$downloaded",
                                    )
                                }
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
                binding.aboutUpdateStatus.setTextColor(
                    ContextCompat.getColor(this@AboutActivity, R.color.danger),
                )
                binding.btnCheckUpdate.visibility = View.VISIBLE
                binding.btnCheckUpdate.isClickable = true
                binding.btnCheckUpdate.alpha = 1f
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
        binding.btnCheckUpdate.visibility = View.VISIBLE
        binding.btnCheckUpdate.isClickable = true
        binding.btnCheckUpdate.alpha = 1f
        binding.aboutUpdateStatus.visibility = View.VISIBLE
        val info = pendingUpdate
        if (info != null) {
            binding.aboutUpdateStatus.text =
                getString(R.string.about_update_available, info.versionName)
            binding.aboutUpdateStatus.setTextColor(
                ContextCompat.getColor(this, R.color.accent),
            )
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
            binding.aboutUpdateStatus.setTextColor(
                ContextCompat.getColor(this, R.color.danger),
            )
            binding.btnCheckUpdate.visibility = View.VISIBLE
            binding.btnCheckUpdate.isClickable = true
            binding.btnCheckUpdate.alpha = 1f
            binding.btnCheckUpdate.requestFocus()
            return
        }

        when (val validation = ApkInstallValidator.validate(
            context = this,
            file = target,
            expectedSize = downloadExpectedBytes,
        )) {
            ApkValidationResult.Valid -> Unit
            is ApkValidationResult.Invalid -> {
                runCatching { target.delete() }
                showInstallValidationError(validation.failure)
                return
            }
        }
        binding.updateProgressBar.isIndeterminate = false
        binding.updateProgressBar.progress = 100
        binding.updatePercent.text = String.format(Locale.getDefault(), "%d%%", 100)
        binding.updateSize.text = getString(R.string.about_download_complete)
        binding.updateSize.announceForAccessibility(binding.updateSize.text)
        binding.updateSpeedEta.text = ""
        binding.btnCancelUpdate.visibility = View.GONE
        binding.btnCheckUpdate.visibility = View.VISIBLE
        binding.btnCheckUpdate.isClickable = true
        binding.btnCheckUpdate.alpha = 1f
        binding.btnCheckUpdate.requestFocus()
        if (!ApkInstaller.install(this, target)) {
            binding.updateProgressCard.visibility = View.GONE
            binding.aboutUpdateStatus.visibility = View.VISIBLE
            binding.aboutUpdateStatus.setText(R.string.about_update_failed)
            binding.aboutUpdateStatus.setTextColor(
                ContextCompat.getColor(this, R.color.danger),
            )
            binding.btnUpdateNow.visibility = View.VISIBLE
            binding.btnUpdateNow.requestFocus()
        }
    }

    private fun showInstallValidationError(failure: ApkValidationFailure) {
        val message = when (failure) {
            ApkValidationFailure.MISSING -> R.string.about_update_file_missing
            ApkValidationFailure.INCOMPLETE -> R.string.about_update_file_incomplete
            ApkValidationFailure.INSUFFICIENT_STORAGE -> R.string.about_update_storage_low
            ApkValidationFailure.INVALID_APK -> R.string.about_update_invalid_apk
            ApkValidationFailure.WRONG_PACKAGE -> R.string.about_update_wrong_package
            ApkValidationFailure.NOT_NEWER -> R.string.about_update_not_newer
            ApkValidationFailure.SIGNATURE_MISMATCH -> R.string.about_update_signature_mismatch
        }
        binding.updateProgressCard.visibility = View.GONE
        binding.btnCancelUpdate.visibility = View.GONE
        binding.aboutUpdateStatus.visibility = View.VISIBLE
        binding.aboutUpdateStatus.setText(message)
        binding.aboutUpdateStatus.setTextColor(
            ContextCompat.getColor(this, R.color.danger),
        )
        binding.btnCheckUpdate.visibility = View.VISIBLE
        binding.btnCheckUpdate.isClickable = true
        binding.btnCheckUpdate.alpha = 1f
        binding.btnUpdateNow.visibility = View.VISIBLE
        binding.btnUpdateNow.requestFocus()
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
        downloadJob?.cancel()
        downloadJob = null
        activeCall?.cancel()
        activeCall = null
        super.onDestroy()
    }
}
