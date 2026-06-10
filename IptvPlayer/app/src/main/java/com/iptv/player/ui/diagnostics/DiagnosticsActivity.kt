/*
 * DiagnosticsActivity.kt
 * Runs connectivity diagnostics sequentially (internet, ping, speed, DNS) and
 * renders pass/fail rows with detail text. Shows device info and can share a
 * plain-text log summary via an ACTION_SEND intent.
 */
package com.iptv.player.ui.diagnostics

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.databinding.ActivityDiagnosticsBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.util.Logger
import com.iptv.player.util.NetworkUtils
import com.iptv.player.util.PeeringTester
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DiagnosticsActivity : BaseActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    private val results = mutableListOf<DiagnosticResult>()

    // Guard flags so the two tests can't overlap. We do NOT disable the busy
    // peering button (it can be focused, and disabling a focused button steals
    // D-pad focus to a nav row) — re-entry is blocked by the flag instead.
    private var quickRunning = false
    private var peeringRunning = false
    private var peeringJob: Job? = null
    private val peeringRows = mutableListOf<View>()
    private var lastPeeringSummary: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceInfo.text = deviceInfo()
        binding.btnRunTests.setOnClickListener { runTests() }
        binding.btnPeering.setOnClickListener { runPeeringTest() }
        binding.btnSendLogs.setOnClickListener { sendLogs() }
        binding.btnRunTests.requestFocus()
    }

    override fun onStop() {
        // Abort the long peering test if the user leaves the screen.
        peeringJob?.cancel()
        super.onStop()
    }

    private fun runTests() {
        if (quickRunning || peeringRunning) return
        quickRunning = true
        binding.btnRunTests.isEnabled = false
        binding.diagResults.removeAllViews()
        results.clear()

        lifecycleScope.launch {
            try {
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
            } finally {
                binding.btnRunTests.isEnabled = true
                quickRunning = false
            }
        }
    }

    /**
     * Comparative ~3-minute peering test: probes our server alongside two anycast
     * anchors and reports whether a streaming problem is on the path toward us or
     * on the customer's own line. Live rows update twice a second; the verdict
     * line doubles as a countdown while the test runs.
     */
    private fun runPeeringTest() {
        if (peeringRunning || quickRunning) return
        peeringRunning = true

        val targets = PeeringTester.defaultTargets(getString(R.string.diag_peering_server))
        binding.peeringResults.removeAllViews()
        peeringRows.clear()
        targets.forEach { t ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_diag_row, binding.peeringResults, false)
            row.findViewById<TextView>(R.id.diagLabel).text = t.label
            row.findViewById<TextView>(R.id.diagDetail).text = getString(R.string.diag_running)
            row.findViewById<TextView>(R.id.diagStatus).text = ""
            binding.peeringResults.addView(row)
            peeringRows += row
        }
        binding.peeringVerdict.visibility = View.VISIBLE

        // Keep the screen awake for the whole 3-minute run.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        peeringJob = lifecycleScope.launch {
            try {
                val stats = PeeringTester.run(targets) { snap, remainingMs ->
                    binding.peeringVerdict.setTextColor(
                        ContextCompat.getColor(this@DiagnosticsActivity, R.color.text_secondary))
                    binding.peeringVerdict.text =
                        getString(R.string.diag_peering_running, (remainingMs / 1000).toInt())
                    snap.forEachIndexed { i, s -> updatePeeringRow(peeringRows.getOrNull(i), s) }
                }
                stats.forEachIndexed { i, s -> updatePeeringRow(peeringRows.getOrNull(i), s) }
                val verdict = PeeringTester.verdict(stats)
                showVerdict(verdict)
                val summary = buildPeeringSummary(verdict, stats)
                lastPeeringSummary = summary
                PeeringTester.upload(this@DiagnosticsActivity, summary)
            } catch (_: CancellationException) {
                // User left the screen mid-test; leave the partial UI as-is.
            } finally {
                // Keep-screen-on is held app-wide by BaseActivity; don't drop it.
                peeringRunning = false
            }
        }
    }

    private fun updatePeeringRow(row: View?, s: PeeringTester.PeerStat) {
        row ?: return
        row.findViewById<TextView>(R.id.diagLabel).text = s.label
        row.findViewById<TextView>(R.id.diagDetail).text = if (s.reachable) {
            getString(R.string.diag_peering_detail, s.avgMs, s.jitterMs, s.lossPct, s.samples, s.attempts)
        } else {
            getString(R.string.diag_peering_unreachable)
        }
        val healthy = s.reachable && s.lossPct < 5 && s.jitterMs < 40
        val status = row.findViewById<TextView>(R.id.diagStatus)
        status.text = "%${s.lossPct}"
        status.setTextColor(
            ContextCompat.getColor(this, if (healthy) R.color.success else R.color.danger))
    }

    private fun verdictRes(v: PeeringTester.Verdict): Int = when (v) {
        PeeringTester.Verdict.OK -> R.string.diag_peering_ok
        PeeringTester.Verdict.PEERING -> R.string.diag_peering_peering
        PeeringTester.Verdict.CUSTOMER_LINE -> R.string.diag_peering_local
        PeeringTester.Verdict.SERVER_DOWN -> R.string.diag_peering_down
        PeeringTester.Verdict.INCONCLUSIVE -> R.string.diag_peering_inconclusive
    }

    private fun showVerdict(v: PeeringTester.Verdict) {
        val color = when (v) {
            PeeringTester.Verdict.OK -> R.color.success
            PeeringTester.Verdict.INCONCLUSIVE -> R.color.text_secondary
            else -> R.color.danger
        }
        binding.peeringVerdict.text = getString(verdictRes(v))
        binding.peeringVerdict.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun buildPeeringSummary(v: PeeringTester.Verdict, stats: List<PeeringTester.PeerStat>): String {
        val parts = stats.joinToString(" | ") { s ->
            if (s.reachable) "${s.label}: ${s.avgMs}ms j${s.jitterMs} %k${s.lossPct}"
            else "${s.label}: ${getString(R.string.diag_peering_unreachable)}"
        }
        return "${getString(verdictRes(v))} — $parts"
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
            lastPeeringSummary?.let {
                appendLine()
                appendLine("${getString(R.string.diag_peering)}: $it")
            }
        }
        // Attach the rotating app log file when available so provider/playback
        // failures captured in the field travel with the diagnostics report.
        val logUri: Uri? = Logger.shareableFile()?.let { file ->
            runCatching {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            }.getOrNull()
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_logs_subject))
            putExtra(Intent.EXTRA_TEXT, summary)
            if (logUri != null) {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, logUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        startActivity(Intent.createChooser(intent, getString(R.string.diag_send_logs)))
    }
}
