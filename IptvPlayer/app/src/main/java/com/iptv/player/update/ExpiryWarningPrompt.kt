/*
 * ExpiryWarningPrompt.kt
 * Launch-time subscription dialogs, driven by a single account-info lookup:
 *
 *  - EXPIRED: when the subscription has already ended (status "Expired" or a
 *    negative remaining-days count) a prominent, non-cancelable notice tells the
 *    user they can no longer watch any content and must renew.
 *  - EXPIRING SOON: when 5 days or fewer remain, a softer reminder asks the user
 *    to renew in advance. "Don't show again" suppresses it for the *current*
 *    expiry date only; a later renewal (a different date) re-enables it.
 *
 * Mirrors UpdatePrompt: silent on failure or when nothing is due, shows once per
 * session, and is fully D-pad driven. [onNoPrompt] is invoked (on the main
 * thread) on every path where no dialog is shown, so callers can chain another
 * launch dialog (e.g. the update prompt) without the two overlapping. Expiry is
 * checked first so an already-expired account always sees its notice even when an
 * update is also available.
 */
package com.iptv.player.update

import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object ExpiryWarningPrompt {

    /** Show the soft reminder when remaining days fall within this window. */
    private const val WARN_WITHIN_DAYS = 5L

    /** Guards against re-prompting on every Activity (re)creation in one session. */
    @Volatile
    private var shownThisSession = false

    /**
     * Checks the account expiry off the main thread and shows the appropriate
     * dialog: the expired notice when the subscription has ended, otherwise the
     * soft reminder when it ends within [WARN_WITHIN_DAYS] days. Safe to call from
     * any [AppCompatActivity] onCreate. Stays silent on any failure and invokes
     * [onNoPrompt] whenever no dialog is shown.
     */
    fun maybeShow(activity: AppCompatActivity, onNoPrompt: (() -> Unit)? = null) {
        if (shownThisSession) {
            onNoPrompt?.invoke()
            return
        }
        activity.lifecycleScope.launch {
            val info = try {
                val config = ServiceLocator.settings.getSourceConfig()
                val account = config?.let { ServiceLocator.repository.getAccountInfo(it) }
                if (account == null) {
                    onNoPrompt?.invoke()
                    return@launch
                }
                account
            } catch (e: CancellationException) {
                // Never swallow cancellation: let the caller's coroutine unwind cleanly.
                throw e
            } catch (e: Exception) {
                onNoPrompt?.invoke()
                return@launch
            }

            val days = info.daysRemaining
            val expired = info.status.equals("Expired", true) || (days != null && days < 0)

            // Don't touch the window once the Activity is going away.
            if (shownThisSession || activity.isFinishing || activity.isDestroyed) {
                onNoPrompt?.invoke()
                return@launch
            }

            when {
                expired -> {
                    shownThisSession = true
                    showExpiredDialog(activity, info.expiryDateMs)
                }
                days != null && days in 0..WARN_WITHIN_DAYS -> {
                    val expiryMs = info.expiryDateMs
                    // Respect a prior "don't show again" for this exact expiry date.
                    if (expiryMs == null ||
                        ServiceLocator.settings.getSuppressedExpiryWarning() == expiryMs
                    ) {
                        onNoPrompt?.invoke()
                        return@launch
                    }
                    shownThisSession = true
                    showWarningDialog(activity, expiryMs)
                }
                else -> onNoPrompt?.invoke()
            }
        }
    }

    /** Hard "subscription expired" notice; non-cancelable, single acknowledge. */
    private fun showExpiredDialog(activity: AppCompatActivity, expiryMs: Long?) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_expiry_expired, null)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)

        val dateView = view.findViewById<TextView>(R.id.expiredDate)
        if (expiryMs != null) {
            dateView.text = activity.getString(
                R.string.expiry_expired_date,
                DateFormat.getDateInstance(DateFormat.LONG).format(Date(expiryMs))
            )
            dateView.visibility = View.VISIBLE
        } else {
            dateView.visibility = View.GONE
        }

        val okButton = view.findViewById<View>(R.id.expiredOkButton)
        okButton.setOnClickListener { dialog.dismiss() }

        dialog.show()
        okButton.requestFocus()
    }

    /** Soft "expiring soon" reminder with OK + "don't show again". */
    private fun showWarningDialog(activity: AppCompatActivity, expiryMs: Long) {
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
