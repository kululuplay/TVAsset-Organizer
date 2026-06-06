/*
 * PlayerScreenGuard.kt
 * Bundles the two power-related concerns shared by the Live TV and VOD players:
 *
 *  - Keep-screen-on: toggles the window FLAG_KEEP_SCREEN_ON so the panel never
 *    dims, sleeps or shows the lock screen while content is actually playing.
 *    It is play-state driven (set on play, cleared on pause/stop/leave) and
 *    uses the window flag rather than a manual PowerManager wake lock, so there
 *    is nothing to leak. Buffering / auto-reconnect keep the flag held.
 *
 *  - Power-off teardown: registers a runtime receiver for ACTION_SCREEN_OFF
 *    (the real remote Power OFF / device screen-off). Short navigations and
 *    in-app dialogs never fire this broadcast, so only a genuine screen-off
 *    invokes [onScreenOff], where the caller fully releases the player (freeing
 *    the single stream connection) and returns to the home screen.
 */
package com.iptv.player.ui.common

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager

class PlayerScreenGuard(
    private val activity: Activity,
    private val onScreenOff: () -> Unit
) {

    private var receiver: BroadcastReceiver? = null
    private var fired = false

    /** Start listening for a real device screen-off. Idempotent. */
    fun register() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF && !fired) {
                    // Guard against multiple deliveries triggering teardown twice.
                    fired = true
                    onScreenOff()
                }
            }
        }
        activity.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF))
        receiver = r
    }

    /** Stop listening + drop the keep-screen-on flag. Safe to call repeatedly. */
    fun unregister() {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
        keepScreenOn(false)
    }

    /** Hold (or release) the screen-on window flag. */
    fun keepScreenOn(on: Boolean) {
        if (on) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
