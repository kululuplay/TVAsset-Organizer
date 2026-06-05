/*
 * LocaleManager.kt
 * Per-app language switching that works down to minSdk 21 (we don't rely on the
 * Android 13 per-app language API). The chosen language tag is persisted by
 * SettingsStore; BaseActivity wraps its context with the right locale on create.
 */
package com.iptv.player.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {

    /** Supported UI languages. "" / system means follow the device. */
    val SUPPORTED = listOf("en", "tr", "de", "fr", "nl", "ar")

    fun displayName(tag: String): String = when (tag) {
        "en" -> "English"
        "tr" -> "Türkçe"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "nl" -> "Nederlands"
        "ar" -> "العربية"
        else -> tag
    }

    /** Returns a context configured for [languageTag] (falls back to system if blank). */
    fun wrap(base: Context, languageTag: String?): Context {
        if (languageTag.isNullOrBlank()) return base
        val locale = Locale(languageTag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale) // enables RTL for Arabic
        return base.createConfigurationContext(config)
    }

    fun isRtl(languageTag: String?): Boolean = languageTag == "ar"
}
