/*
 * ExpiryWarningPrompt.kt
 * Launch-time subscription-expiry reminder. When the active account has 5 days
 * or fewer remaining, it shows a single modern dialog asking the user to renew
 * in advance with their IPTV provider. Mirrors UpdatePrompt: silent on failure
 * or when nothing is due, shows once per session, and is fully D-pad driven.
 *
 * "Don't show again" suppresses the reminder for the *current* expiry date only;
 * once the subscription is renewed (a later expiry date) the reminder fires again.
 */
package com.iptv.player.update

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

object ExpiryWarningPrompt {

    /** Show the reminder when remaining days fall within this window (inclusive). */
    private const val WARN_WITHIN_DAYS = 5L

    /** Guards against re-prompting on every Activity (re)creation in one session. */
    @Volatile
    private var shownThisSession = false

    /**
     * Checks the account expiry off the main thread and, if the subscription ends
     * within [WARN_WITHIN_DAYS] days and the user has not suppressed the reminder
     * for this expiry date, shows a non-blocking modern dialog. Safe to call from
     * any [AppCompatActivity] onCreate. Stays silent on any failure.
     */
    fun maybeShow(activity: AppCompatActivity) {
        if (shownThisSession) return
        activity.lifecycleScope.launch {
            val expiryMs = try {
                val config = ServiceLocator.settings.getSourceConfig() ?: return@launch
                val info = ServiceLocator.repository.getAccountInfo(config) ?: return@launch
                val days = info.daysRemaining ?: return@launch
                if (days !in 0..WARN_WITHIN_DAYS) return@launch
                info.expiryDateMs ?: return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }

            // Respect a prior "don't show again" for this exact expiry date.
            if (ServiceLocator.settings.getSuppressedExpiryWarning() == expiryMs) return@launch

            // Don't touch the window once the Activity is going away.
            if (shownThisSession || activity.isFinishing || activity.isDestroyed) return@launch
            shownThisSession = true

            showDialog(activity, expiryMs)
        }
    }

    private fun showDialog(activity: AppCompatActivity, expiryMs: Long) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_expiry_warning, null)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()

        val okButton = view.findViewById<View>(R.id.expiryOkButton)
        okButton.setOnClickListener { dialog.dismiss() }

        view.findViewById<View>(R.id.expiryDontShowButton).setOnClickListener {
            activity.lifecycleScope.launch {
                runCatching { ServiceLocator.settings.setSuppressedExpiryWarning(expiryMs) }
            }
            dialog.dismiss()
        }

        dialog.show()
        okButton.requestFocus()
    }
}
