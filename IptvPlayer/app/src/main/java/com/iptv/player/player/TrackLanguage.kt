package com.iptv.player.player

import java.util.Locale

/** Normalises engine-specific language labels/codes to a stable ISO-639-1 tag. */
object TrackLanguage {
    private val aliases = mapOf(
        "tur" to "tr", "turkish" to "tr", "türkçe" to "tr", "turkce" to "tr",
        "deu" to "de", "ger" to "de", "german" to "de", "deutsch" to "de",
        "eng" to "en", "english" to "en", "englisch" to "en", "ingilizce" to "en",
        "fra" to "fr", "fre" to "fr", "french" to "fr", "français" to "fr",
        "nld" to "nl", "dut" to "nl", "dutch" to "nl", "nederlands" to "nl",
        "ara" to "ar", "arabic" to "ar", "العربية" to "ar",
        "spa" to "es", "spanish" to "es", "español" to "es",
        "ita" to "it", "italian" to "it", "italiano" to "it",
        "rus" to "ru", "russian" to "ru", "русский" to "ru",
    )

    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = value.lowercase(Locale.ROOT)
        aliases[lower]?.let { return it }
        val tokens = lower.split(Regex("[^\\p{L}]+"))
            .filter { it.isNotBlank() }
        tokens.forEach { token -> aliases[token]?.let { return it } }
        tokens.firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.let {
            return Locale.forLanguageTag(it).language.takeIf { code -> code.length == 2 }
        }
        tokens.firstOrNull { it.length == 3 && it.all(Char::isLetter) }?.let { iso3 ->
            Locale.getISOLanguages().firstOrNull { code ->
                runCatching { Locale(code).getISO3Language().equals(iso3, ignoreCase = true) }
                    .getOrDefault(false)
            }?.let { return it }
        }
        return null
    }
}
