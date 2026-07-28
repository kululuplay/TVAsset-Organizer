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

import android.app.Dialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.iptv.player.data.ServiceLocator
import com.iptv.player.data.model.Category
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.FavoriteItem
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.VodItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object PinLockHelper {

    /**
     * Cancellable ownership handle for an in-flight PIN request.
     *
     * The request observes its Activity directly, so even older call sites that
     * ignore the returned handle cannot leave a PIN dialog (or a delayed
     * DataStore callback) alive after the screen has stopped.
     */
    class Request internal constructor(
        private val owner: LifecycleOwner,
        private val onDenied: () -> Unit,
    ) : DefaultLifecycleObserver {
        private var finished = false
        private var dialog: Dialog? = null

        init {
            owner.lifecycle.addObserver(this)
        }

        internal val isActive: Boolean
            get() = !finished

        internal fun attach(value: Dialog) {
            if (finished) value.dismiss() else dialog = value
        }

        internal fun allow(onAllowed: () -> Unit) {
            if (finished) return
            finished = true
            dialog = null
            owner.lifecycle.removeObserver(this)
            onAllowed()
        }

        internal fun deny() {
            if (finished) return
            finished = true
            dialog = null
            owner.lifecycle.removeObserver(this)
            onDenied()
        }

        fun cancel() {
            if (finished) return
            finished = true
            dialog?.dismiss()
            dialog = null
            owner.lifecycle.removeObserver(this)
            onDenied()
        }

        override fun onStop(owner: LifecycleOwner) {
            cancel()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            cancel()
        }
    }

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
    ): Request {
        val request = Request(activity, onDenied)
        activity.lifecycleScope.launch {
            // guard() can be reached after an async lookup that began in onCreate.
            // If that lookup completes while the Activity is stopped, wait for the
            // same screen to become visible again instead of opening a dialog or
            // starting playback behind another Activity.
            activity.lifecycle.withStarted { }
            if (!request.isActive) return@launch
            if (activity.isFinishing || activity.isDestroyed) {
                request.deny()
                return@launch
            }
            if (!isAdult) {
                request.allow(onAllowed)
                return@launch
            }

            val settings = ServiceLocator.settings
            val locked = settings.lockAdult.first() && settings.hasPin()
            if (!request.isActive) return@launch
            if (activity.isFinishing || activity.isDestroyed) {
                request.deny()
                return@launch
            }
            if (!locked) {
                request.allow(onAllowed)
                return@launch
            }
            val pin = settings.getPin()
            // The preference reads above can suspend across onStop. The Request's
            // observer cancels normal in-flight prompts; this second STARTED gate
            // also covers a Request that itself was created after ON_STOP.
            activity.lifecycle.withStarted {
                if (!request.isActive || activity.isFinishing || activity.isDestroyed) {
                    request.deny()
                    return@withStarted
                }
                val dialog = PinPromptDialog.show(
                    context = activity,
                    expectedPin = pin,
                    onSuccess = { request.allow(onAllowed) },
                    onCancel = request::deny,
                )
                request.attach(dialog)
            }
        }
        return request
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

/** True when the favorite itself, or its source category, looks like adult content. */
fun FavoriteItem.isAdult(): Boolean =
    PinLockHelper.looksAdult(title) || PinLockHelper.looksAdult(categoryName)
