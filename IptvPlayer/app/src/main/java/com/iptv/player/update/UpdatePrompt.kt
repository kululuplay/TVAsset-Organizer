/*
 * UpdatePrompt.kt
 * Lightweight launch-time update check. The first time an update is found within a
 * process session it shows a single modern dialog and routes the user to
 * AboutActivity to download/install. Stays completely silent on failure or when
 * already up to date, so a flaky network never interrupts the user. Picking
 * "remind me later" (or Back) records the version: it is offered again after a
 * day at the earliest (UpdatePromptPolicy), and a release the device cannot
 * run is never announced at all.
 */
package com.iptv.player.update

import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.ui.settings.AboutActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UpdatePrompt {

    /** Guards against re-prompting on every Activity (re)creation in one session. */
    @Volatile
    private var shownThisSession = false

    /**
     * Checks for an update off the main thread and, if one is available and we have
     * not already prompted this session, shows a non-blocking modern dialog. Safe to
     * call from any [AppCompatActivity] onCreate.
     *
     * [onNoPrompt] is invoked (on the main thread) when no update dialog is shown —
     * because none is available, the check failed, or one was already shown this
     * session. Callers use it to chain a different launch dialog (e.g. the expiry
     * reminder) so the two never overlap.
     */
    fun maybeShow(activity: AppCompatActivity, onNoPrompt: (() -> Unit)? = null) {
        if (shownThisSession) {
            onNoPrompt?.invoke()
            return
        }
        activity.lifecycleScope.launch {
            val result = try {
                UpdateChecker(ServiceLocator.httpClient, activity)
                    .check(BuildConfig.VERSION_NAME)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onNoPrompt?.invoke()
                return@launch
            }
            if (result !is UpdateResult.Available) {
                onNoPrompt?.invoke()
                return@launch
            }
            // A version dismissed with "Later" stays quiet for a day, and a
            // release this device cannot run is never announced. The marker is
            // read off the main thread; an unreadable one counts as none.
            val dismissals = UpdatePromptDismissals(activity)
            val dismissal = withContext(Dispatchers.IO) {
                runCatching { dismissals.current() }.getOrNull()
            }
            val allowed = UpdatePromptPolicy.shouldPrompt(
                info = result.info,
                deviceSdkInt = Build.VERSION.SDK_INT,
                dismissal = dismissal,
                nowMs = System.currentTimeMillis(),
            )
            if (!allowed) {
                onNoPrompt?.invoke()
                return@launch
            }
            // The request can complete after Home/settings/navigation. Present
            // only on a resumed dashboard; destruction cancels this wait.
            activity.lifecycle.withResumed {
                if (shownThisSession || activity.isFinishing || activity.isDestroyed) {
                    onNoPrompt?.invoke()
                } else {
                    shownThisSession = true
                    showDialog(activity, result.info, dismissals)
                }
            }
        }
    }

    private fun showDialog(
        activity: AppCompatActivity,
        info: UpdateInfo,
        dismissals: UpdatePromptDismissals,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

        view.findViewById<TextView>(R.id.updateVersion).text =
            activity.getString(R.string.update_version_tag, info.versionName)
        view.findViewById<TextView>(R.id.updateMessage).text =
            activity.getString(R.string.update_version_ready, info.versionName)

        val notesView = view.findViewById<TextView>(R.id.updateNotes)
        val notesScroll = view.findViewById<View>(R.id.updateNotesScroll)
        // Launch-time update actions must remain visible on every TV. Detailed
        // GitHub notes can be arbitrarily long and previously pushed both buttons
        // below the viewport on low-height OEM launchers. The About screen remains
        // the detailed update workspace; this prompt uses one localized summary.
        notesView.setText(R.string.update_notes_fallback)
        notesScroll.visibility = View.VISIBLE
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.updateTitle), true)
        ViewCompat.setAccessibilityPaneTitle(
            view,
            activity.getString(R.string.update_available_title),
        )

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()

        val updateNow = view.findViewById<View>(R.id.updateNowButton)
        val updateLater = view.findViewById<View>(R.id.updateLaterButton)
        var updateChosen = false
        updateNow.setOnClickListener {
            updateChosen = true
            dialog.dismiss()
            activity.startActivity(
                Intent(activity, AboutActivity::class.java)
                    .putExtra(AboutActivity.EXTRA_AUTO_CHECK, true)
            )
        }
        updateLater.setOnClickListener {
            dialog.dismiss()
        }
        // "Later", Back and an outside touch all leave this version quiet for a
        // day; choosing the update must not, so a download cancelled in About
        // can still be offered again at the next launch.
        dialog.setOnDismissListener {
            if (!updateChosen) dismissals.record(info.versionName, System.currentTimeMillis())
        }

        if (activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            updateLater.nextFocusLeftId = updateNow.id
            updateNow.nextFocusRightId = updateLater.id
        } else {
            updateLater.nextFocusRightId = updateNow.id
            updateNow.nextFocusLeftId = updateLater.id
        }

        dialog.show()
        val metrics = activity.resources.displayMetrics
        val maxWidth = (560f * metrics.density).toInt()
        val safeWidth = (metrics.widthPixels * 0.90f).toInt().coerceAtMost(maxWidth)
        dialog.window?.setLayout(safeWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.apply {
            alpha = 0f
            scaleX = 0.97f
            scaleY = 0.97f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
        updateNow.requestFocus()
    }
}
