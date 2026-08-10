/*
 * UpdatePrompt.kt
 * Lightweight launch-time update check. The first time an update is found within a
 * process session it shows a single modern dialog and routes the user to
 * AboutActivity to download/install. Stays completely silent on failure or when
 * already up to date, so a flaky network never interrupts the user. Picking
 * "remind me later" simply dismisses; the check runs again on the next launch.
 */
package com.iptv.player.update

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.iptv.player.BuildConfig
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import com.iptv.player.ui.settings.AboutActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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
                UpdateChecker(ServiceLocator.httpClient).check(BuildConfig.VERSION_NAME)
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
            // Don't touch the window once the Activity is going away.
            if (shownThisSession || activity.isFinishing || activity.isDestroyed) {
                onNoPrompt?.invoke()
                return@launch
            }
            shownThisSession = true

            showDialog(activity, result.info)
        }
    }

    private fun showDialog(activity: AppCompatActivity, info: UpdateInfo) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null)

        view.findViewById<TextView>(R.id.updateVersion).text =
            activity.getString(R.string.update_version_tag, info.versionName)
        view.findViewById<TextView>(R.id.updateMessage).text =
            activity.getString(R.string.update_version_ready, info.versionName)

        val notesView = view.findViewById<TextView>(R.id.updateNotes)
        val notesScroll = view.findViewById<View>(R.id.updateNotesScroll)
        notesView.text = info.notes?.trim()
            ?: activity.getString(R.string.update_notes_fallback)
        notesScroll.visibility = View.VISIBLE
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.updateTitle), true)
        ViewCompat.setAccessibilityHeading(view.findViewById(R.id.updateNotesHeading), true)
        ViewCompat.setAccessibilityPaneTitle(
            view,
            activity.getString(R.string.update_available_title),
        )

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()

        val updateNow = view.findViewById<View>(R.id.updateNowButton)
        val updateLater = view.findViewById<View>(R.id.updateLaterButton)
        updateNow.setOnClickListener {
            dialog.dismiss()
            activity.startActivity(
                Intent(activity, AboutActivity::class.java)
                    .putExtra(AboutActivity.EXTRA_AUTO_CHECK, true)
            )
        }
        updateLater.setOnClickListener {
            dialog.dismiss()
        }

        if (activity.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            updateLater.nextFocusLeftId = updateNow.id
            updateNow.nextFocusRightId = updateLater.id
        } else {
            updateLater.nextFocusRightId = updateNow.id
            updateNow.nextFocusLeftId = updateLater.id
        }

        dialog.show()
        view.apply {
            alpha = 0f
            scaleX = 0.97f
            scaleY = 0.97f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
        updateNow.requestFocus()
    }
}
