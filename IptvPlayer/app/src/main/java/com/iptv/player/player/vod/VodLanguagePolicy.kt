package com.iptv.player.player.vod

import java.util.Locale

/** Pure provider-language normalization shared by persisted preferences and tracks. */
object VodLanguagePolicy {

    /**
     * Converts common `ll_CC`/BCP-47 forms into one stable language tag. Provider
     * labels such as "Deutsch" are intentionally rejected instead of being stored
     * as a fragile display-name preference.
     */
    fun normalize(value: String?): String? {
        val candidate = value
            ?.trim()
            ?.replace('_', '-')
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val normalized = Locale.forLanguageTag(candidate).toLanguageTag()
        return normalized.takeUnless { it.equals(UNDEFINED, ignoreCase = true) }
    }

    /** Exact tag first, then primary-language match (e.g. `de-DE` matches `de-AT`). */
    fun matches(preferred: String?, candidate: String?): Boolean {
        val expected = normalize(preferred) ?: return false
        val actual = normalize(candidate) ?: return false
        if (expected.equals(actual, ignoreCase = true)) return true
        return expected.substringBefore('-').equals(
            actual.substringBefore('-'),
            ignoreCase = true,
        )
    }

    private const val UNDEFINED = "und"
}
