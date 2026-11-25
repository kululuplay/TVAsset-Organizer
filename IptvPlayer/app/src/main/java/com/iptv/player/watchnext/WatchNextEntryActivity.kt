/*
 * WatchNextEntryActivity.kt
 * Entry point of a "Continue watching" card in the Watch Next row of the TV home
 * screen. It shows nothing: it remembers which title the card asked for and
 * restarts the app through the splash, exactly like a profile switch does. The
 * dashboard then opens that title through the in-app Continue Watching screen,
 * so the login, profile and PIN checks of a normal start all apply.
 *
 * The request is kept in a small preferences file instead of an intent extra:
 * after a long absence the first screen behind the splash recycles the process
 * (StaleProcessGuard) and relaunches the plain launcher intent, which would drop
 * an extra. The request expires after a minute and is used once.
 */
package com.iptv.player.watchnext

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import com.iptv.player.ui.splash.SplashActivity

class WatchNextEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported: the id is outside input, and reading it must never crash.
        val id = WatchNextPolicy.validContentId(
            runCatching { intent?.getStringExtra(EXTRA_CONTENT_ID) }.getOrNull(),
        )
        prefs(this).edit {
            if (id == null) clear()
            else putString(KEY_ID, id).putLong(KEY_REQUESTED_AT, System.currentTimeMillis())
        }
        // Anything else than a well-formed id just opens the app.
        runCatching {
            startActivity(
                Intent(this, SplashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
        finish()
    }

    companion object {
        /** Content id of the requested title ("vod_<id>" or "ep_<id>"). */
        const val EXTRA_CONTENT_ID = "watch_next_content_id"

        private const val PREFS = "watch_next_request"
        private const val KEY_ID = "content_id"
        private const val KEY_REQUESTED_AT = "requested_at_ms"

        /** The intent a Watch Next card carries for [contentId]. */
        fun intent(context: Context, contentId: String): Intent =
            Intent(context, WatchNextEntryActivity::class.java)
                .putExtra(EXTRA_CONTENT_ID, contentId)

        /**
         * The title a card asked for, handed out once and only while the request
         * is recent. The caller still has to check that it is a resume row of the
         * active profile.
         */
        fun takeRequest(context: Context): String? {
            val prefs = prefs(context)
            val id = prefs.getString(KEY_ID, null) ?: return null
            val requestedAt = prefs.getLong(KEY_REQUESTED_AT, 0L)
            prefs.edit { clear() }
            return WatchNextPolicy.validContentId(id)?.takeIf {
                WatchNextPolicy.isRequestFresh(requestedAt, System.currentTimeMillis())
            }
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
