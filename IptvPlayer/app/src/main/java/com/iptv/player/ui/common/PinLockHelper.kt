/*
 * PinLockHelper.kt
 * Parental gate for playback. Given that the content is flagged adult, and the
 * user has enabled settings.lockAdult AND set a PIN, it prompts for the PIN
 * before allowing the action. Otherwise it runs [onAllowed] immediately.
 *
 * Usage:
 *   PinLockHelper.guard(activity, isAdult = channel.isAdult()) { openPlayer(channel) }
 */
package com.iptv.player.ui.common

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object PinLockHelper {

    /**
     * @param isAdult whether the target content/category is flagged adult.
     * @param onAllowed run when access is granted (no lock, or correct PIN).
     * @param onDenied  optional, run when the user cancels/fails the prompt.
     */
    fun guard(
        activity: AppCompatActivity,
        isAdult: Boolean,
        onAllowed: () -> Unit,
        onDenied: () -> Unit = {}
    ) {
        if (!isAdult) {
            onAllowed()
            return
        }
        activity.lifecycleScope.launch {
            val settings = ServiceLocator.settings
            val locked = settings.lockAdult.first() && settings.hasPin()
            if (!locked) {
                onAllowed()
                return@launch
            }
            val pin = settings.getPin()
            PinPromptDialog.show(
                context = activity,
                expectedPin = pin,
                onSuccess = onAllowed,
                onCancel = onDenied
            )
        }
    }

    /** Heuristic helper: detect "adult/xxx" hints in a category/channel name. */
    fun looksAdult(name: String?): Boolean {
        val n = name?.lowercase().orEmpty()
        return n.contains("adult") || n.contains("xxx") || n.contains("+18") ||
            n.contains("18+") || n.contains("porn")
    }
}
