/*
 * UpdatePrompt.kt
 * Lightweight launch-time update check. The first time an update is found within a
 * process session it shows a single dialog and routes the user to AboutActivity to
 * download/install. Stays completely silent on failure or when already up to date,
 * so a flaky network never interrupts the user.
 */
package com.iptv.player.update

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
     * not already prompted this session, shows a non-blocking dialog. Safe to call
     * from any [AppCompatActivity] onCreate.
     */
    fun maybeShow(activity: AppCompatActivity) {
        if (shownThisSession) return
        activity.lifecycleScope.launch {
            val result = try {
                UpdateChecker(ServiceLocator.httpClient).check(BuildConfig.VERSION_NAME)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (result !is UpdateResult.Available) return@launch
            // Don't touch the window once the Activity is going away.
            if (shownThisSession || activity.isFinishing || activity.isDestroyed) return@launch
            shownThisSession = true

            AlertDialog.Builder(activity)
                .setTitle(R.string.update_available_title)
                .setMessage(
                    activity.getString(R.string.update_available_message, result.info.versionName)
                )
                .setPositiveButton(R.string.about_update_now) { _, _ ->
                    activity.startActivity(
                        Intent(activity, AboutActivity::class.java)
                            .putExtra(AboutActivity.EXTRA_AUTO_CHECK, true)
                    )
                }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }
}
