/*
 * CastController.kt
 * Per-activity glue around [CastHelper]. Listens for Cast sessions and pushes the
 * current stream to the connected device; surfaces a route chooser when the user
 * taps the cast button. Every call is guarded so devices without Play services
 * (most TV boxes) simply get a "cast unavailable" toast instead of a crash.
 */
package com.iptv.player.cast

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.mediarouter.app.MediaRouteChooserDialog
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.iptv.player.R

class CastController(
    private val activity: Activity,
    private val mediaProvider: () -> CastMedia?
) {

    data class CastMedia(
        val url: String,
        val title: String,
        val imageUrl: String?,
        val isLive: Boolean
    )

    private var attached = false

    private val isTelevision: Boolean
        get() =
            (activity.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    val isAvailable: Boolean
        get() = CastDevicePolicy.shouldInitialize(isTelevision) &&
            CastHelper.isAvailable(activity)

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = loadCurrent()
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = loadCurrent()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    fun attach() {
        if (!isAvailable || attached) return
        val sessionManager = CastHelper.castContext(activity)?.sessionManager ?: return
        runCatching {
            sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        }.onSuccess { attached = true }
    }

    fun detach() {
        if (!attached) return
        attached = false
        runCatching {
            CastHelper.castContext(activity)
                ?.sessionManager
                ?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    /** Cast button handler: load if connected, otherwise show the route chooser. */
    fun onCastButtonClicked() {
        if (!isAvailable) {
            toast(R.string.cast_unavailable)
            return
        }
        val ctx = CastHelper.castContext(activity)
        if (ctx == null) {
            toast(R.string.cast_unavailable)
            return
        }
        if (CastHelper.isConnected(activity)) {
            loadCurrent()
            return
        }
        val selector = runCatching { ctx.mergedSelector }.getOrNull()
        if (selector == null) {
            toast(R.string.cast_unavailable)
            return
        }
        runCatching {
            MediaRouteChooserDialog(activity).apply { routeSelector = selector }.show()
        }.onFailure { toast(R.string.cast_unavailable) }
    }

    private fun loadCurrent() {
        val media = mediaProvider() ?: return
        val ok = CastHelper.loadMedia(activity, media.url, media.title, media.imageUrl, media.isLive)
        toast(if (ok) R.string.cast_started else R.string.cast_unavailable)
    }

    private fun toast(resId: Int) {
        Toast.makeText(activity, resId, Toast.LENGTH_SHORT).show()
    }
}
