/*
 * DisplayModeSwitcher.kt
 * Applies automatic frame-rate matching to the playback Activity's window via
 * WindowManager.LayoutParams.preferredDisplayModeId (API 23+; no-op below).
 * The decision itself is pure (FrameRateMatchPolicy) and the once-per-family /
 * 2 s debounce and restore bookkeeping is pure too (FrameRateMatchSession);
 * this class only touches Display/Window and logs.
 *
 * It never touches the SurfaceView: a refresh-only switch keeps the surface
 * geometry, so the Amlogic surface rules are untouched. The switch is applied
 * only after the engine proved a real frame, so the "no first frame" watchdogs
 * are already satisfied and the brief HDMI re-sync blank is not misread.
 */
package com.iptv.player.playback.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import com.iptv.player.data.ServiceLocator
import com.iptv.player.playback.core.FrameRateMatchSession
import com.iptv.player.playback.core.FrameRateMatcher
import com.iptv.player.player.AfrMode
import com.iptv.player.player.DisplayModeInfo
import com.iptv.player.util.PlaybackLog
import java.util.Locale

class DisplayModeSwitcher internal constructor(
    private val context: Context,
    private val window: Window,
    private val session: FrameRateMatchSession = FrameRateMatchSession(),
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : FrameRateMatcher {

    /** Effective user setting; read from the settings mirror by [from]. */
    var afrMode: AfrMode = AfrMode.OFF
        set(value) {
            if (field != value) {
                field = value
                // A changed setting must be re-evaluated on the next format report.
                session.invalidate()
            }
        }

    /**
     * False while the owner renders an inline preview: a mode switch would blank
     * the whole Home screen for a thumbnail. A switch already applied is undone.
     */
    var allowed: Boolean = true
        set(value) {
            field = value
            if (!value) restore()
        }

    /** preferredDisplayModeId the window had before our first switch (0 = system default). */
    private var originalPreferredModeId: Int? = null
    private var deferGeneration = 0

    override fun onVideoFormat(fps: Float, width: Int, height: Int) {
        apply(fps, width, height)
    }

    fun apply(fps: Float, width: Int, height: Int) {
        ++deferGeneration
        if (!allowed || afrMode == AfrMode.OFF) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = currentDisplay() ?: return
        val modes = runCatching {
            display.supportedModes.map {
                DisplayModeInfo(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            }
        }.getOrNull().orEmpty()
        val currentModeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        when (
            val decision = session.request(
                nowMs = nowMs(),
                afrMode = afrMode,
                fps = fps,
                width = width,
                height = height,
                modes = modes,
                currentModeId = currentModeId,
            )
        ) {
            is FrameRateMatchSession.Decision.Switch -> switchTo(decision.targetModeId, modes, currentModeId, fps)
            is FrameRateMatchSession.Decision.Defer -> {
                val generation = deferGeneration
                handler.postDelayed({
                    if (generation == deferGeneration) apply(fps, width, height)
                }, decision.delayMs)
            }
            FrameRateMatchSession.Decision.Keep -> PlaybackLog.log(
                context,
                TAG,
                "fps=$fps ${width}x$height fits current mode $currentModeId -> keep",
            )
            FrameRateMatchSession.Decision.Ignore -> Unit
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun switchTo(targetModeId: Int, modes: List<DisplayModeInfo>, currentModeId: Int, fps: Float) {
        val params = window.attributes
        if (originalPreferredModeId == null) originalPreferredModeId = params.preferredDisplayModeId
        val target = modes.firstOrNull { it.id == targetModeId }
        val current = modes.firstOrNull { it.id == currentModeId }
        PlaybackLog.log(
            context,
            TAG,
            "fps=$fps mode ${describe(current)} -> ${describe(target)} (afr=$afrMode)",
        )
        runCatching {
            params.preferredDisplayModeId = targetModeId
            window.attributes = params
        }.onFailure {
            PlaybackLog.log(context, TAG, "switch failed: ${it.javaClass.simpleName}")
        }
    }

    /** Put the window back to its pre-switch preference. Idempotent. */
    override fun restore() {
        ++deferGeneration
        handler.removeCallbacksAndMessages(null)
        val hadSwitched = session.restore()
        val original = originalPreferredModeId ?: return
        originalPreferredModeId = null
        if (!hadSwitched || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val params = window.attributes
            params.preferredDisplayModeId = original
            window.attributes = params
            PlaybackLog.log(context, TAG, "restored preferred mode $original")
        }
    }

    fun release() = restore()

    @Suppress("DEPRECATION")
    private fun currentDisplay(): Display? = runCatching {
        window.decorView.display
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
    }.getOrNull()

    private fun describe(mode: DisplayModeInfo?): String =
        if (mode == null) "?" else "${mode.id}:${mode.width}x${mode.height}@${String.format(Locale.US, "%.3f", mode.refreshHz)}"

    companion object {
        private const val TAG = "AFR"

        /**
         * Build a switcher for the Activity hosting [container], or null when the
         * view's context does not unwrap to an Activity (nothing to switch then).
         * The user's setting is read synchronously from the settings mirror.
         */
        fun from(container: View): DisplayModeSwitcher? {
            val activity = activityOf(container.context) ?: return null
            return DisplayModeSwitcher(activity.applicationContext, activity.window).apply {
                afrMode = runCatching { ServiceLocator.settings.afrModeBlocking() }.getOrDefault(AfrMode.OFF)
            }
        }

        fun activityOf(context: Context?): Activity? {
            var current = context
            while (current != null) {
                if (current is Activity) return current
                current = (current as? ContextWrapper)?.baseContext
            }
            return null
        }
    }
}
