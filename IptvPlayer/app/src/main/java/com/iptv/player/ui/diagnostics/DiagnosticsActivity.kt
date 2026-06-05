/*
 * DiagnosticsActivity.kt
 * Runs connectivity diagnostics sequentially (internet, ping, speed, DNS) and
 * renders pass/fail rows with detail text. Shows device info and can share a
 * plain-text log summary via an ACTION_SEND intent.
 */
package com.iptv.player.ui.diagnostics

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.databinding.ActivityDiagnosticsBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.util.NetworkUtils
import kotlinx.coroutines.launch

class DiagnosticsActivity : BaseActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    private val results = mutableListOf<DiagnosticResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceInfo.text = deviceInfo()
        binding.btnRunTests.setOnClickListener { runTests() }
        binding.btnSendLogs.setOnClickListener { sendLogs() }
        binding.btnRunTests.requestFocus()
    }

    private fun runTests() {
        binding.btnRunTests.isEnabled = false
        binding.diagResults.removeAllViews()
        results.clear()

        lifecycleScope.launch {
            val config = ServiceLocator.settings.getSourceConfig()

            // 1) Simple internet check (no network call).
            val online = NetworkUtils.isOnline(this@DiagnosticsActivity)
            addResult(DiagnosticResult(getString(R.string.diag_internet), online,
                if (online) getString(R.string.diag_passed) else getString(R.string.error_no_internet)))

            if (config != null) {
                addResult(localize(ServiceLocator.repository.pingServer(config), R.string.diag_ping))
                addResult(localize(ServiceLocator.repository.speedTestMbps(config), R.string.diag_speed))
                addResult(localize(ServiceLocator.repository.checkDns(config), R.string.diag_dns))
            }

            binding.btnRunTests.isEnabled = true
        }
    }

    /** Replaces the repository's internal label with the localized one. */
    private fun localize(result: DiagnosticResult, labelRes: Int): DiagnosticResult =
        result.copy(label = getString(labelRes))

    private fun addResult(result: DiagnosticResult) {
        results += result
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_diag_row, binding.diagResults, false)
        row.findViewById<TextView>(R.id.diagLabel).text = result.label
        row.findViewById<TextView>(R.id.diagDetail).text = result.detail
        val status = row.findViewById<TextView>(R.id.diagStatus)
        status.text = getString(if (result.ok) R.string.diag_passed else R.string.diag_failed)
        status.setTextColor(ContextCompat.getColor(this, if (result.ok) R.color.success else R.color.danger))
        binding.diagResults.addView(row)
    }

    private fun deviceInfo(): String =
        getString(R.string.diag_device_format, Build.MANUFACTURER, Build.MODEL,
            Build.VERSION.RELEASE, appVersion())

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun sendLogs() {
        val summary = buildString {
            appendLine(getString(R.string.diag_logs_subject))
            appendLine()
            appendLine(deviceInfo())
            appendLine()
            results.forEach { r ->
                val status = getString(if (r.ok) R.string.diag_passed else R.string.diag_failed)
                appendLine("${r.label}: $status (${r.detail})")
            }
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_logs_subject))
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diag_send_logs)))
    }
}
