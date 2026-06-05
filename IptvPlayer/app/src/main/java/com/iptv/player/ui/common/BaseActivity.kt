/*
 * BaseActivity.kt
 * Common activity base that applies the saved UI language before the views are
 * created. All screens extend this so language + RTL are consistent app-wide.
 * The language tag is read synchronously from SettingsStore at attach time.
 */
package com.iptv.player.ui.common

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.iptv.player.data.ServiceLocator
import com.iptv.player.util.LocaleManager

abstract class BaseActivity : AppCompatActivity() {

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
