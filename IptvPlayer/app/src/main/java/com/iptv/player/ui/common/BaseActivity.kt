/*
 * BaseActivity.kt
 * Common activity base that applies the saved UI language before the views are
 * created. All screens extend this so language + RTL are consistent app-wide.
 * The language tag is read synchronously from SettingsStore at attach time.
 */
package com.iptv.player.ui.common

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.iptv.player.data.ServiceLocator
import com.iptv.player.util.LocaleManager

abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App-wide power policy: never let ANY device (Fire TV, Sony, generic
        // Android TV, phones/tablets) dim, sleep or show a screensaver while a
        // screen of this app is in the foreground. The window flag holds no wake
        // lock to leak and is dropped automatically once the screen is no longer
        // visible. Every screen extends BaseActivity, so this covers the app.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
