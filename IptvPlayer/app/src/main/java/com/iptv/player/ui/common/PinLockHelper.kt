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
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.VodItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object PinLockHelper {

    /**
     * @param isAdult whether the target content/category is flagged adult.
     * @param onDenied  optional, run when the user cancels/fails the prompt.
     * @param onAllowed run when access is granted (no lock, or correct PIN).
     */
    fun guard(
        activity: AppCompatActivity,
        isAdult: Boolean,
        onDenied: () -> Unit = {},
        onAllowed: () -> Unit
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

    /**
     * Keyword heuristic for adult content. Word-boundary anchored so common
     * substrings (e.g. "Essex", "Sussex") don't trip the "sex" token, while
     * still catching "Sex TV", "Porno", "XXX", "Erotic", "18+/+18".
     */
    private val ADULT_REGEX = Regex(
        "\\b(adult|xxx|porn|erotic|sex)",
        RegexOption.IGNORE_CASE
    )

    /** Heuristic helper: detect adult hints in a category/channel/item name. */
    fun looksAdult(name: String?): Boolean {
        val n = name ?: return false
        if (ADULT_REGEX.containsMatchIn(n)) return true
        return n.contains("18+") || n.contains("+18")
    }
}

/** True when the channel itself, or its category, looks like adult content. */
fun Channel.isAdult(): Boolean =
    PinLockHelper.looksAdult(name) || PinLockHelper.looksAdult(categoryName)

/** True when the movie itself, or its category, looks like adult content. */
fun VodItem.isAdult(): Boolean =
    PinLockHelper.looksAdult(name) || PinLockHelper.looksAdult(categoryName)

/** True when the series itself, or its category, looks like adult content. */
fun Series.isAdult(): Boolean =
    PinLockHelper.looksAdult(name) || PinLockHelper.looksAdult(categoryName)

/** True when the category name looks like adult content. */
fun Category.isAdult(): Boolean = PinLockHelper.looksAdult(name)
