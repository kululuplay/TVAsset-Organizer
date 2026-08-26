/*
 * DiagnosticsActivity.kt
 * Runs connectivity diagnostics sequentially (internet, ping, speed, DNS) and
 * renders pass/fail rows with detail text. Shows device info and can share a
 * plain-text log summary via an ACTION_SEND intent.
 */
package com.iptv.player.ui.diagnostics

import android.app.ActivityManager
import android.content.Intent
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.DiagnosticResult
import com.iptv.player.databinding.ActivityDiagnosticsBinding
import com.iptv.player.ui.common.BaseActivity
import com.iptv.player.util.Logger
import com.iptv.player.util.NetworkUtils
import com.iptv.player.util.PeeringTester
import com.iptv.player.util.SupportReportUploader
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
    private var quickJob: Job? = null
    private var peeringJob: Job? = null
    private val peeringRows = mutableListOf<View>()
    private var lastPeeringSummary: String? = null
    private var lastSupportCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceInfo.text = deviceInfo()
        binding.btnRunTests.setOnClickListener { runTests() }
        binding.btnPeering.setOnClickListener { runPeeringTest() }
        binding.btnSendLogs.setOnClickListener { sendLogs() }
        ViewCompat.setAccessibilityHeading(binding.diagnosticsTitle, true)
        ViewCompat.setAccessibilityPaneTitle(binding.root, getString(R.string.diag_title))
        binding.btnRunTests.post { binding.btnRunTests.requestFocus() }
    }

    override fun onStop() {
        // Abort active network work if the user leaves the screen.
        quickJob?.cancel()
        peeringJob?.cancel()
        super.onStop()
    }

    private fun runTests() {
        if (quickRunning || peeringRunning) return
        quickRunning = true
        setQuickRunning(true)
        binding.diagResults.removeAllViews()
        results.clear()
        lastSupportCode = null

        quickJob = lifecycleScope.launch {
            try {
                val config = ServiceLocator.settings.getSourceConfig()

                // 1) Simple internet check (no network call).
                val online = NetworkUtils.isOnline(this@DiagnosticsActivity)
                addResult(DiagnosticResult(getString(R.string.diag_internet), online,
                    if (online) getString(R.string.diag_passed) else getString(R.string.error_no_internet)))

                if (config != null) {
                    addSafeResult(R.string.diag_ping) {
                        ServiceLocator.repository.pingServer(config)
                    }
                    addSafeResult(R.string.diag_speed) {
                        ServiceLocator.repository.speedTestMbps(config)
                    }
                    addSafeResult(R.string.diag_dns) {
                        ServiceLocator.repository.checkDns(config)
                    }
                } else {
                    addResult(
                        DiagnosticResult(
                            getString(R.string.diag_server),
                            false,
                            getString(R.string.account_none),
                        ),
                    )
                }
                deviceHealthResults().forEach(::addResult)

                // Short route-quality sample for the one-button test. The
                // dedicated peering action still provides the full 3-minute run.
                val quickVerdict = if (online) {
                    val quickPeers = PeeringTester.run(
                        targets = PeeringTester.defaultTargets(
                            getString(R.string.diag_peering_server),
                        ),
                        durationMs = QUICK_ROUTE_DURATION_MS,
                        intervalMs = 1_000L,
                        connectTimeoutMs = 1_200,
                    ) { _, remainingMs ->
                        binding.diagQuickStatus.text = getString(
                            R.string.diag_quick_route_running,
                            (remainingMs / 1_000L).coerceAtLeast(0L),
                        )
                    }
                    PeeringTester.verdict(quickPeers, minSamples = 8)
                } else {
                    PeeringTester.Verdict.INCONCLUSIVE
                }
                addResult(
                    DiagnosticResult(
                        label = getString(R.string.diag_route_quality),
                        ok = quickVerdict == PeeringTester.Verdict.OK,
                        detail = getString(verdictRes(quickVerdict)),
                    ),
                )

                val summary = buildQuickSummary()
                lastSupportCode = SupportReportUploader.upload(
                    this@DiagnosticsActivity,
                    results,
                    summary,
                )
                val finalStatus = lastSupportCode?.let { code ->
                    getString(
                        R.string.diag_complete_support_code,
                        results.count { it.ok },
                        results.size,
                        code,
                    )
                } ?: getString(
                    R.string.diag_complete_summary,
                    results.count { it.ok },
                    results.size,
                )
                binding.diagQuickStatus.text = finalStatus
                binding.diagQuickStatus.announceForAccessibility(finalStatus)
            } catch (ce: CancellationException) {
                binding.diagQuickStatus.setText(R.string.diag_ready)
                throw ce
            } catch (_: Throwable) {
                addResult(
                    DiagnosticResult(
                        getString(R.string.diag_title),
                        false,
                        getString(R.string.error_unknown),
                    ),
                )
                binding.diagQuickStatus.setText(R.string.diag_failed)
            } finally {
                quickRunning = false
                quickJob = null
                setQuickRunning(false, keepStatus = results.isNotEmpty())
            }
        }
    }

    private fun setQuickRunning(running: Boolean, keepStatus: Boolean = false) {
        // Keep the focused view enabled; disabling it makes Android TV move focus
        // to an arbitrary sibling. The running guard + isClickable blocks re-entry.
        binding.btnRunTests.isClickable = !running
        binding.btnRunTests.alpha = if (running) 0.68f else 1f
        binding.btnRunTests.setText(
            if (running) R.string.diag_running else R.string.diag_run,
        )
        binding.diagQuickProgress.visibility = if (running) View.VISIBLE else View.GONE
        if (running) {
            binding.diagQuickStatus.setText(R.string.diag_running)
        } else if (!keepStatus) {
            binding.diagQuickStatus.setText(R.string.diag_ready)
        }
    }

    private fun deviceHealthResults(): List<DiagnosticResult> {
        val manager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo().also { info ->
            runCatching { manager?.getMemoryInfo(info) }
        }
        val availableMemory = memory.availMem.coerceAtLeast(0L)
        val memoryOk = !memory.lowMemory && availableMemory >= MIN_FREE_MEMORY_BYTES

        val freeStorage = StatFs(filesDir.absolutePath).availableBytes.coerceAtLeast(0L)
        val storageOk = freeStorage >= MIN_FREE_STORAGE_BYTES

        val decoderTypes = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .map { it.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
        val h264 = "video/avc" in decoderTypes
        val hevc = "video/hevc" in decoderTypes
        val ac3 = "audio/ac3" in decoderTypes
        val eac3 = "audio/eac3" in decoderTypes || "audio/e-ac3" in decoderTypes

        return listOf(
            DiagnosticResult(
                getString(R.string.diag_memory),
                memoryOk,
                getString(
                    R.string.diag_memory_detail,
                    Formatter.formatFileSize(this, availableMemory),
                ),
            ),
            DiagnosticResult(
                getString(R.string.diag_storage),
                storageOk,
                getString(
                    R.string.diag_storage_detail,
                    Formatter.formatFileSize(this, freeStorage),
                ),
            ),
            DiagnosticResult(
                getString(R.string.diag_codecs),
                h264,
                getString(
                    R.string.diag_codecs_detail,
                    yesNo(h264),
                    yesNo(hevc),
                    yesNo(ac3),
                    yesNo(eac3),
                ),
            ),
        )
    }

    private fun yesNo(value: Boolean): String = getString(
        if (value) R.string.diag_yes else R.string.diag_no,
    )

    private fun buildQuickSummary(): String = buildString {
        append(deviceInfo())
        append(" | ")
        results.forEachIndexed { index, result ->
            if (index > 0) append("; ")
            append(result.label)
            append('=')
            append(if (result.ok) "OK" else "FAIL")
            append('(')
            append(sanitizeDetail(result.detail))
            append(')')
        }
    }

    private suspend fun addSafeResult(
        labelRes: Int,
        block: suspend () -> DiagnosticResult,
    ) {
        val result = try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            DiagnosticResult(
                label = getString(labelRes),
                ok = false,
                detail = getString(R.string.error_unknown),
            )
        }
        addResult(localize(result, labelRes))
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
        binding.btnPeering.isClickable = false
        binding.btnPeering.alpha = 0.68f
        lastPeeringSummary = null

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
            } catch (_: Throwable) {
                binding.peeringVerdict.visibility = View.VISIBLE
                binding.peeringVerdict.setText(R.string.diag_failed)
                binding.peeringVerdict.setTextColor(
                    ContextCompat.getColor(this@DiagnosticsActivity, R.color.danger),
                )
                binding.peeringVerdict.announceForAccessibility(
                    binding.peeringVerdict.text,
                )
            } finally {
                // Keep-screen-on is held app-wide by BaseActivity; don't drop it.
                peeringRunning = false
                peeringJob = null
                binding.btnPeering.isClickable = true
                binding.btnPeering.alpha = 1f
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
        binding.peeringVerdict.announceForAccessibility(binding.peeringVerdict.text)
    }

    private fun buildPeeringSummary(v: PeeringTester.Verdict, stats: List<PeeringTester.PeerStat>): String {
        val parts = stats.joinToString(" | ") { s ->
            if (s.reachable) {
                "${s.label}: " + getString(
                    R.string.diag_peering_detail,
                    s.avgMs,
                    s.jitterMs,
                    s.lossPct,
                    s.samples,
                    s.attempts,
                )
            }
            else "${s.label}: ${getString(R.string.diag_peering_unreachable)}"
        }
        return "${getString(verdictRes(v))} — $parts"
    }

    /** Replaces the repository's internal label with the localized one. */
    private fun localize(result: DiagnosticResult, labelRes: Int): DiagnosticResult =
        result.copy(
            label = getString(labelRes),
            detail = sanitizeDetail(result.detail),
        )

    /** Avoid surfacing provider URLs/tokens from low-level exception messages. */
    private fun sanitizeDetail(detail: String): String =
        detail
            .replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "•••")
            .replace(
                Regex(
                    """(?i)(username|user|password|pass|token|auth)=([^&\s]+)""",
                ),
                "\$1=•••",
            )

    private fun addResult(result: DiagnosticResult) {
        results += result
        val row = LayoutInflater.from(this)
            .inflate(R.layout.item_diag_row, binding.diagResults, false)
        row.findViewById<TextView>(R.id.diagLabel).text = result.label
        row.findViewById<TextView>(R.id.diagDetail).text = result.detail
        val status = row.findViewById<TextView>(R.id.diagStatus)
        status.text = getString(if (result.ok) R.string.diag_passed else R.string.diag_failed)
        status.setTextColor(ContextCompat.getColor(this, if (result.ok) R.color.success else R.color.danger))
        row.contentDescription =
            "${result.label}. ${status.text}. ${result.detail}"
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
            lastSupportCode?.let {
                appendLine()
                appendLine(getString(R.string.diag_support_code_line, it))
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
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.diag_send_logs)))
        }.onFailure {
            Toast.makeText(this, R.string.settings_share_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val QUICK_ROUTE_DURATION_MS = 12_000L
        const val MIN_FREE_MEMORY_BYTES = 128L * 1024L * 1024L
        const val MIN_FREE_STORAGE_BYTES = 250L * 1024L * 1024L
    }
}
