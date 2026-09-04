package com.iptv.player.util

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/** One TV-friendly, explicitly confirmed support upload shared by both entry points. */
class SupportDiagnosticDialog : DialogFragment() {
    private val state by lazy { ViewModelProvider(this)[SupportDiagnosticState::class.java] }
    private var uploadJob: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.support_diagnostic_title)
            .setMessage(R.string.support_diagnostic_confirm)
            .setPositiveButton(R.string.support_diagnostic_send, null)
            .setNegativeButton(R.string.support_diagnostic_cancel, null)
            .create()

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        (activity as? BaseActivity)?.trackIdleInteractions(alert)
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (state.result is SupportResult.Success) dismiss() else upload()
        }
        alert.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            uploadJob?.cancel()
            dismiss()
        }
        render()
    }

    private fun upload() {
        if (state.busy || uploadJob?.isActive == true) return
        val appContext = requireContext().applicationContext
        val summary = arguments?.getString(ARG_SUMMARY).orEmpty()
        state.busy = true
        state.result = null
        state.interrupted = false
        val operation = ++state.operation
        render()
        uploadJob = lifecycleScope.launch {
            try {
                val result = withTimeout(UPLOAD_BUDGET_MS) {
                    // Preserve exactly this payload for Retry. SupportClient maps
                    // its sanitized fingerprint to the same persisted request ID.
                    val snapshot = state.snapshot ?: withContext(Dispatchers.IO) {
                        readSnapshot(appContext, summary)
                    }.also { state.snapshot = it }
                    coroutineContext.ensureActive()
                    SupportClient.uploadDiagnostic(
                        context = appContext,
                        message = snapshot.message,
                        log = snapshot.log,
                        metadata = snapshot.metadata,
                    )
                }
                if (state.operation == operation) state.result = result
            } catch (_: TimeoutCancellationException) {
                if (state.operation == operation) {
                    state.result = SupportResult.Failure(SupportFailureKind.TIMEOUT)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (state.operation == operation) {
                    state.result = SupportResult.Failure(SupportFailureKind.UNKNOWN)
                }
            } finally {
                if (state.operation == operation) {
                    state.busy = false
                    render()
                }
            }
        }
    }

    private fun render() {
        if (!isAdded) return
        val alert = dialog as? AlertDialog ?: return
        if (!alert.isShowing) return
        val result = state.result
        val success = result is SupportResult.Success
        val message = when {
            state.busy -> getString(R.string.support_diagnostic_sending)
            state.interrupted -> getString(R.string.support_diagnostic_interrupted)
            result != null -> result.userMessage + if (success && state.snapshot?.incomplete == true) {
                "\n\n" + getString(R.string.support_diagnostic_incomplete)
            } else {
                ""
            }
            else -> getString(R.string.support_diagnostic_confirm)
        }
        alert.setMessage(message)
        val positive = alert.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = alert.getButton(AlertDialog.BUTTON_NEGATIVE)
        positive.isEnabled = !state.busy
        positive.setText(when {
            state.busy -> R.string.support_diagnostic_send
            success -> R.string.support_diagnostic_close
            result != null || state.interrupted -> R.string.support_diagnostic_retry
            else -> R.string.support_diagnostic_send
        })
        negative.visibility = if (success) View.GONE else View.VISIBLE
        if (state.busy) negative.requestFocus() else positive.requestFocus()
    }

    override fun onDismiss(dialog: DialogInterface) {
        uploadJob?.cancel()
        super.onDismiss(dialog)
    }

    override fun onDestroy() {
        // FragmentManager owns the window across lifecycle changes. A recreated
        // dialog keeps its snapshot but requires an explicit retry after cancel.
        if (uploadJob?.isActive == true) {
            state.operation++
            state.busy = false
            state.interrupted = true
        }
        uploadJob?.cancel()
        uploadJob = null
        super.onDestroy()
    }

    private suspend fun readSnapshot(context: Context, summary: String): SupportDiagnosticSnapshot {
        var incomplete = false
        val metadata: Map<String, Any?> = try {
            val settings = ServiceLocator.settings
            val selection = settings.getPlaybackSelection()
            mapOf(
                "engine" to "configured:${selection.player.name}",
                "decoder" to "configured:${selection.decoder.name}",
                "transport" to "configured:${settings.getStreamFormat().name}",
                "buffer" to "configured:${settings.getBufferMode().name}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            incomplete = true
            emptyMap()
        }
        coroutineContext.ensureActive()
        val playback = try {
            val file = PlaybackLog.file(context)
            if (file == null || !file.isFile) "" else RandomAccessFile(file, "r").use { reader ->
                val start = (reader.length() - PLAYBACK_TAIL_BYTES).coerceAtLeast(0L)
                reader.seek(start)
                val bytes = ByteArray((reader.length() - start).coerceAtMost(PLAYBACK_TAIL_BYTES).toInt())
                reader.readFully(bytes)
                // Never upload a credential fragment from a partially read line.
                val offset = if (start == 0L) 0 else bytes.indexOf('\n'.code.toByte()) + 1
                if (start > 0L && offset == 0) "" else {
                    String(bytes, offset, bytes.size - offset, Charsets.UTF_8)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }
        coroutineContext.ensureActive()
        // Logger's current file is rotated at 512 KiB. Read/sanitize on IO and
        // keep its recent tail, without creating another share attachment file.
        val general = Logger.recentText(maxChars = GENERAL_TAIL_CHARS).let { tail ->
            // recentText may cut inside a line before redaction changes its
            // length. Always discard that first line rather than risk a partial
            // legacy URL/credential at the snapshot boundary.
            tail.substringAfter('\n', "")
        }
        if (playback.isBlank() || general.isBlank()) incomplete = true
        val log = buildString {
            appendLine("=== Playback log ===")
            appendLine(playback.ifBlank { "[Playback log unavailable]" })
            appendLine("=== App log ===")
            appendLine(general.ifBlank { "[App log unavailable]" })
        }
        // Redact before the final UTF-8 byte cap. The client repeats its stronger
        // URL/known-secret sanitization before transmission as the final boundary.
        return SupportDiagnosticSnapshot(
            message = SupportPayloadPolicy.sanitize(summary).take(5_000),
            log = SupportPayloadPolicy.utf8Tail(
                SupportPayloadPolicy.sanitize(SensitiveDataRedactor.redact(log)),
                SupportPayloadPolicy.MAX_LOG_BYTES,
            ),
            metadata = metadata,
            incomplete = incomplete,
        )
    }

    companion object {
        private const val TAG = "kululu.support.diagnostic"
        private const val ARG_SUMMARY = "summary"
        private const val PLAYBACK_TAIL_BYTES = 80L * 1024L
        private const val GENERAL_TAIL_CHARS = 16 * 1024
        private const val UPLOAD_BUDGET_MS = 75_000L

        fun show(activity: FragmentActivity, summary: String) {
            if (activity.isFinishing || activity.isDestroyed) return
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            SupportDiagnosticDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SUMMARY, SupportPayloadPolicy.sanitize(summary).take(5_000))
                }
            }.showNow(manager, TAG)
        }
    }
}

/** No Activity/window reference survives a rotation, only the immutable payload. */
class SupportDiagnosticState : ViewModel() {
    internal var snapshot: SupportDiagnosticSnapshot? = null
    internal var result: SupportResult? = null
    internal var busy = false
    internal var interrupted = false
    internal var operation = 0L
}

internal data class SupportDiagnosticSnapshot(
    val message: String,
    val log: String,
    val metadata: Map<String, Any?>,
    val incomplete: Boolean,
)
