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
 * session, and is fully D-pad driven. [onComplete] is invoked after dismissal or
 * when no dialog is due, so callers can chain another launch dialog (e.g. the
 * update prompt) without skipping it or overlapping the two. Expiry is
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
import androidx.lifecycle.withResumed
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
     * [onComplete] after the notice is dismissed, or when no notice is shown.
     */
    fun maybeShow(activity: AppCompatActivity, onComplete: (() -> Unit)? = null) {
        var completed = false
        val complete: () -> Unit = {
            if (!completed && !activity.isFinishing && !activity.isDestroyed) {
                completed = true
                activity.lifecycleScope.launch {
                    // A user may press Home while the account check is running or
                    // the notice is closing. Wait for the dashboard to be visible.
                    activity.lifecycle.withResumed {
                        if (!activity.isFinishing) onComplete?.invoke()
                    }
                }
            }
        }
        if (shownThisSession) {
            complete()
            return
        }
        activity.lifecycleScope.launch {
            val info = try {
                val config = ServiceLocator.settings.getSourceConfig()
                val account = config?.let { ServiceLocator.repository.getAccountInfo(it) }
                if (account == null) {
                    complete()
                    return@launch
                }
                account
            } catch (e: CancellationException) {
                // Never swallow cancellation: let the caller's coroutine unwind cleanly.
                throw e
            } catch (e: Exception) {
                complete()
                return@launch
            }

            val days = info.daysRemaining
            val expired = info.status.equals("Expired", true) || (days != null && days < 0)

            // Don't touch the window once the Activity is going away.
            if (shownThisSession || activity.isFinishing || activity.isDestroyed) {
                complete()
                return@launch
            }

            when {
                expired -> {
                    activity.lifecycle.withResumed {
                        if (shownThisSession || activity.isFinishing) complete()
                        else {
                            shownThisSession = true
                            showExpiredDialog(activity, info.expiryDateMs, complete)
                        }
                    }
                }
                days != null && days in 0..WARN_WITHIN_DAYS -> {
                    val expiryMs = info.expiryDateMs
                    // Respect a prior "don't show again" for this exact expiry date.
                    if (expiryMs == null ||
                        ServiceLocator.settings.getSuppressedExpiryWarning() == expiryMs
                    ) {
                        complete()
                        return@launch
                    }
                    activity.lifecycle.withResumed {
                        if (shownThisSession || activity.isFinishing) complete()
                        else {
                            shownThisSession = true
                            showWarningDialog(activity, expiryMs, days, complete)
                        }
                    }
                }
                else -> complete()
            }
        }
    }

    /** Hard "subscription expired" notice; non-cancelable, single acknowledge. */
    private fun showExpiredDialog(
        activity: AppCompatActivity,
        expiryMs: Long?,
        onDismiss: () -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_expiry_expired, null)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener { onDismiss() }

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
    private fun showWarningDialog(
        activity: AppCompatActivity,
        expiryMs: Long,
        days: Long,
        onDismiss: () -> Unit,
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_expiry_warning, null)

        val dialog = AlertDialog.Builder(activity, R.style.ThemeOverlay_Iptv_Dialog)
            .setView(view)
            .create()
        // Covers OK, "don't show again", Back and outside-touch cancellation.
        dialog.setOnDismissListener { onDismiss() }

        // Show the real remaining-days count (min 1, so the final <24h window
        // never reads "0 days") instead of the old static "5 days or less" text.
        view.findViewById<TextView>(R.id.expiryMessage).text = activity.getString(
            R.string.expiry_warning_message,
            days.coerceAtLeast(1L).toInt()
        )

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
