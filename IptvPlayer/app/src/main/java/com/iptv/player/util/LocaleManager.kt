/*
 * LocaleManager.kt
 * Per-app language switching that works down to minSdk 21 (we don't rely on the
 * Android 13 per-app language API). The chosen language tag is persisted by
 * SettingsStore; BaseActivity wraps its context with the right locale on create.
 */
package com.iptv.player.util

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import java.util.Locale
import kotlin.math.roundToInt

object LocaleManager {

    /** Supported UI languages. "" / system means follow the device. */
    val SUPPORTED = listOf("en", "tr", "de", "fr", "nl", "ar")

    /**
     * Reference layout width in dp. Every screen is laid out as if the display
     * were this many dp wide, regardless of the (often arbitrary) densityDpi a
     * TV box reports — otherwise dp/sp content looks zoomed on high-DPI boxes
     * and tiny on low-DPI ones.
     *
     * A wider reference = more dp to work with = a denser, less "zoomed-in" UI
     * (more poster columns + more visible category rows), which is what users
     * expect from a 10-foot IPTV grid. 1120dp fits a 4-column poster grid beside
     * the category rail on 16:9 panels while keeping text comfortably readable.
     */
    private const val DESIGN_WIDTH_DP = 1120f

    fun displayName(tag: String): String = when (tag) {
        "en" -> "English"
        "tr" -> "Türkçe"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "nl" -> "Nederlands"
        "ar" -> "العربية"
        else -> tag
    }

    /**
     * Returns a context configured for [languageTag] (falls back to system when
     * blank) AND normalised to the [DESIGN_WIDTH_DP] reference width so the UI
     * is never zoomed/shrunk by the box's reported densityDpi.
     */
    fun wrap(base: Context, languageTag: String?): Context {
        val config = Configuration(base.resources.configuration)

        if (!languageTag.isNullOrBlank()) {
            val locale = Locale(languageTag)
            Locale.setDefault(locale)
            config.setLocale(locale)
            config.setLayoutDirection(locale) // enables RTL for Arabic
        }

        // Lock the layout to a fixed dp width regardless of the device's reported
        // density. widthPixels/heightPixels can be swapped before the landscape
        // orientation is applied, so use the larger edge as the screen width.
        val dm = base.resources.displayMetrics
        val widthPx = maxOf(dm.widthPixels, dm.heightPixels)
        if (widthPx > 0) {
            config.densityDpi =
                ((widthPx / DESIGN_WIDTH_DP) * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        }

        return base.createConfigurationContext(config)
    }

    fun isRtl(languageTag: String?): Boolean = languageTag == "ar"
}
