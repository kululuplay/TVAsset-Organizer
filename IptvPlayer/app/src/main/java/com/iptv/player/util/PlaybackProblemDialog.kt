package com.iptv.player.util

import android.app.Dialog
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
import com.iptv.player.playback.android.PlaybackQoeRuntime
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.ui.common.BaseActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Selecting the player action is the explicit send request. Retries keep the same incident IDs. */
class PlaybackProblemDialog : DialogFragment() {
    private val state by lazy { ViewModelProvider(this)[PlaybackProblemState::class.java] }
    private var job: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AlertDialog.Builder(requireContext())
        .setTitle(R.string.support_playback_problem_title)
        .setMessage(R.string.support_diagnostic_sending)
        .setPositiveButton(R.string.support_diagnostic_retry, null)
        .setNegativeButton(R.string.support_diagnostic_cancel, null)
        .create()

    override fun onStart() {
        super.onStart()
        val alert = dialog as? AlertDialog ?: return
        (activity as? BaseActivity)?.trackIdleInteractions(alert)
        alert.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (state.result is SupportResult.Success) dismiss() else upload()
        }
        alert.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { job?.cancel(); dismiss() }
        if (!state.started) upload() else render()
    }

    private fun upload() {
        if (state.busy || job?.isActive == true) return
        state.started = true
        state.busy = true
        state.result = null
        val operation = ++state.operation
        val app = requireContext().applicationContext
        val args = requireArguments()
        val issue = args.getString("issue").orEmpty()
        val label = args.getString("label").orEmpty()
        val metadata = listOf("playback_session_id", "content_key", "playback_incident_id")
            .associateWith { args.getString(it) }
        render()
        job = lifecycleScope.launch {
            try {
                val result = SupportClient.uploadPlaybackProblem(app, issue, label, metadata)
                if (state.operation == operation) state.result = result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (state.operation == operation) state.result = SupportResult.Failure(SupportFailureKind.UNKNOWN) }
            finally { if (state.operation == operation) { state.busy = false; render() } }
        }
    }

    private fun render() {
        if (!isAdded) return
        val alert = dialog as? AlertDialog ?: return
        if (!alert.isShowing) return
        val success = state.result is SupportResult.Success
        alert.setMessage(when {
            state.busy -> getString(R.string.support_diagnostic_sending)
            state.result != null -> state.result!!.userMessage
            else -> getString(R.string.support_diagnostic_interrupted)
        })
        val positive = alert.getButton(AlertDialog.BUTTON_POSITIVE)
        positive.isEnabled = !state.busy
        positive.setText(if (success) R.string.support_diagnostic_close else R.string.support_diagnostic_retry)
        val negative = alert.getButton(AlertDialog.BUTTON_NEGATIVE)
        negative.visibility = if (success) View.GONE else View.VISIBLE
        if (state.busy) negative.requestFocus() else positive.requestFocus()
    }

    override fun onDismiss(dialog: DialogInterface) { job?.cancel(); super.onDismiss(dialog) }
    override fun onDestroy() {
        state.operation++
        state.busy = false
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "kululu.support.playback.problem"
        fun show(activity: FragmentActivity, session: PlaybackSessionId?, contentKey: String?, label: String) {
            if (activity.isFinishing || activity.isDestroyed) return
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            val linked = PlaybackQoeRuntime.reportProblem(session, contentKey)
            PlaybackProblemDialog().apply {
                arguments = Bundle().apply {
                    putString("issue", activity.getString(R.string.support_playback_problem_message))
                    putString("label", SupportPayloadPolicy.playbackLabel(linked?.label ?: label, emptyList()))
                    putString("playback_session_id", linked?.sessionId)
                    putString("content_key", linked?.contentKey ?: contentKey)
                    putString("playback_incident_id", linked?.incidentId)
                }
            }.showNow(manager, TAG)
        }
    }
}

class PlaybackProblemState : ViewModel() {
    internal var started = false
    internal var busy = false
    internal var result: SupportResult? = null
    internal var operation = 0L
}
