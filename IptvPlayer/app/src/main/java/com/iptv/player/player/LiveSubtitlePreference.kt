package com.iptv.player.player

/**
 * Durable live-subtitle choice.
 *
 * A missing language is not the same as an explicit OFF choice: AUTO lets each
 * stream/backend choose its default track, OFF disables text tracks across zaps
 * and engine fallbacks, and LANGUAGE reapplies a normalized ISO-639-1 language.
 */
sealed interface LiveSubtitlePreference {
    data object Auto : LiveSubtitlePreference
    data object Off : LiveSubtitlePreference
    class Language private constructor(val code: String) : LiveSubtitlePreference {
        override fun equals(other: Any?): Boolean =
            other is Language && code == other.code

        override fun hashCode(): Int = code.hashCode()

        override fun toString(): String = "Language($code)"

        companion object {
            fun from(raw: String?): Language? =
                TrackLanguage.normalize(raw)?.let(::Language)
        }
    }

    fun languageOrNull(): String? = (this as? Language)?.code

    fun storageValue(): String = when (this) {
        Auto -> AUTO_VALUE
        Off -> OFF_VALUE
        is Language -> LANGUAGE_PREFIX + code
    }

    companion object {
        private const val AUTO_VALUE = "auto"
        private const val OFF_VALUE = "off"
        private const val LANGUAGE_PREFIX = "lang:"

        /** Accepts both the new tagged values and legacy raw language codes. */
        fun fromStorage(raw: String?): LiveSubtitlePreference {
            val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return Auto
            return when {
                value.equals(AUTO_VALUE, ignoreCase = true) -> Auto
                value.equals(OFF_VALUE, ignoreCase = true) -> Off
                value.startsWith(LANGUAGE_PREFIX, ignoreCase = true) ->
                    Language.from(value.substring(LANGUAGE_PREFIX.length)) ?: Auto
                else -> Language.from(value) ?: Auto
            }
        }
    }
}
