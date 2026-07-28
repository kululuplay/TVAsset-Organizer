/*
 * BaseActivity.kt
 * Common activity base that applies the saved UI language before the views are
 * created. All screens extend this so language + RTL are consistent app-wide.
 * The language tag is read synchronously from SettingsStore at attach time.
 */
package com.iptv.player.ui.common

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iptv.player.data.ServiceLocator
import com.iptv.player.ui.screensaver.ScreensaverActivity
import com.iptv.player.util.LocaleManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

abstract class BaseActivity : AppCompatActivity() {

    /**
     * Playback-only screens override this with false. Keeping this as a screen
     * policy (instead of checking concrete Activity classes here) makes future
     * players opt out explicitly and keeps the common lifecycle code reusable.
     */
    protected open val idleScreensaverEnabledForScreen: Boolean
        get() = true

    /**
     * A normally eligible screen can temporarily block the saver while it owns
     * active media (Home's in-panel live preview is the current example).
     */
    protected open fun isIdleScreensaverTemporarilyBlocked(): Boolean = false

    private var idleWatcher: IdleWatcher? = null
    private var screensaverLaunchInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep Android's own dim/sleep policy out of the way. Our persisted,
        // app-owned idle timer below controls the burn-in screensaver instead.
        // The window flag holds no wake lock to leak and Android drops it as soon
        // as this window is no longer visible.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (idleScreensaverEnabledForScreen) {
            // Start disabled until DataStore emits the persisted value. This avoids
            // briefly arming the old default when the user's real setting is Off.
            val watcher = IdleWatcher(timeoutMs = 0L, onIdle = ::onIdleTimeout)
            idleWatcher = watcher
            observeWindowInteraction(window)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    ServiceLocator.settings.screensaverMinutes
                        .distinctUntilChanged()
                        .collect { minutes -> watcher.setTimeoutMinutes(minutes) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        screensaverLaunchInFlight = false
        idleWatcher?.start()
    }

    override fun onPause() {
        // Cancel first so a callback already queued for this loop cannot put the
        // saver over a new Activity that is taking the foreground.
        idleWatcher?.stop()
        super.onPause()
    }

    override fun onDestroy() {
        idleWatcher?.stop()
        idleWatcher = null
        super.onDestroy()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        idleWatcher?.notifyInteraction()
    }

    /**
     * Several TV screens intentionally consume D-pad events in dispatchKeyEvent
     * before calling super. Activity.onUserInteraction is therefore not guaranteed
     * for those keys. Observing the AppCompat window callback gives the common
     * timer every remote, touch and pointer event without forcing each screen to
     * duplicate reset code.
     */
    // AppCompat's wrapper is the API-21-safe way to forward the full callback
    // surface. It is library-group restricted, so keep the lint exemption local.
    @SuppressLint("RestrictedApi")
    private fun observeWindowInteraction(target: Window) {
        val delegate = target.callback ?: return
        target.callback = object : WindowCallbackWrapper(delegate) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                idleWatcher?.notifyInteraction()
                return delegate.dispatchKeyEvent(event)
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                idleWatcher?.notifyInteraction()
                return delegate.dispatchTouchEvent(event)
            }

            override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
                idleWatcher?.notifyInteraction()
                return delegate.dispatchGenericMotionEvent(event)
            }
        }
    }

    /**
     * Dialogs own a separate Window, so Activity interaction callbacks do not see
     * remote or pointer input handled inside them. Settings and shared PIN dialogs
     * register here after showing to keep the same idle deadline accurate.
     */
    internal fun trackIdleInteractions(dialog: Dialog) {
        if (!idleScreensaverEnabledForScreen) return
        dialog.window?.let(::observeWindowInteraction)
    }

    private fun onIdleTimeout() {
        if (
            screensaverLaunchInFlight ||
            isFinishing ||
            isDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return

        if (isIdleScreensaverTemporarilyBlocked()) {
            // Re-check after another complete idle period. Interaction meanwhile
            // also restarts this one-shot timer in the usual way.
            idleWatcher?.start()
            return
        }

        screensaverLaunchInFlight = true
        val launched = runCatching {
            startActivity(
                Intent(this, ScreensaverActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.isSuccess
        if (!launched) {
            screensaverLaunchInFlight = false
            idleWatcher?.start()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        // Read the persisted language synchronously (non-blocking SharedPreferences
        // mirror) to avoid a flash of the wrong locale without stalling the UI
        // thread. ServiceLocator is initialised in IptvApp.onCreate.
        val tag = runCatching {
            ServiceLocator.init(newBase)
            ServiceLocator.settings.languageTagBlocking()
        }.getOrNull()
        super.attachBaseContext(LocaleManager.wrap(newBase, tag))
    }
}
