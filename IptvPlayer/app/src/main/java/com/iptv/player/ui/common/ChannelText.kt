/*
 * ChannelText.kt
 * Cleans up provider channel names for display. IPTV feeds almost always prefix
 * every channel with a country/group code (e.g. "TR: KANAL D HD", "DE | Sport",
 * "EN: BBC") which is redundant next to the category and makes the list look
 * amateurish + truncates badly on TV.
 *
 * To avoid mangling real brand names that happen to start with a short token and
 * a colon (e.g. "FOX: News", "Sky: Sports"), we only strip the prefix when the
 * leading code is a known country/language code. If stripping would empty the
 * name, we keep the original so nothing ever shows blank.
 */
package com.iptv.player.ui.common

object ChannelText {

    // Common IPTV country/language grouping codes (compared upper-cased).
    private val COUNTRY_CODES = setOf(
        "TR", "DE", "FR", "NL", "EN", "AR", "UK", "US", "USA", "ES", "IT",
        "RU", "PL", "PT", "GR", "RO", "BG", "AL", "AZ", "IR", "IQ", "SA",
        "AE", "EG", "MA", "TN", "DZ", "SE", "NO", "DK", "FI", "BE", "CH",
        "AT", "CZ", "SK", "HU", "HR", "RS", "BA", "MK", "UA", "SI", "LT",
        "LV", "EE", "IN", "PK", "BD", "CA", "AU", "BR", "MX", "KU", "EX"
    )

    // Leading "<code><sep>" where sep is ':' or '|', optionally surrounded by space.
    private val PREFIX = Regex("^\\s*([A-Za-z]{2,3})\\s*[:|]\\s*")

    fun clean(name: String?): String {
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return raw
        val match = PREFIX.find(raw) ?: return raw
        val code = match.groupValues[1].uppercase()
        if (code !in COUNTRY_CODES) return raw
        val stripped = raw.substring(match.range.last + 1).trim()
        return stripped.ifEmpty { raw }
    }
}
