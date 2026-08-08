package com.iptv.player.ui.player

import java.text.Normalizer
import java.util.Locale

/** Stable, testable audio/subtitle preference matching across inconsistent files. */
internal object VodTrackPreference {

    private const val PREFIX = "v2;"

    private data class Key(
        val language: String?,
        val roles: Set<String>,
        val normalizedName: String,
    )

    data class ApplicationPlan(
        val audioIndex: Int?,
        val subtitleIndex: Int?,
        val disableSubtitles: Boolean,
        /** False means a saved preference is still waiting for track discovery. */
        val complete: Boolean,
    )

    /** Encode a VLC display label into a versioned semantic preference token. */
    fun encode(label: String): String {
        val key = keyFor(label)
        return buildString {
            append(PREFIX)
            append("l=").append(key.language.orEmpty())
            append(";r=").append(key.roles.sorted().joinToString(","))
            append(";n=").append(key.normalizedName.replace(' ', '_'))
        }
    }

    /** Best matching candidate index, accepting both v2 tokens and legacy labels. */
    fun bestMatchIndex(saved: String?, candidates: List<String>): Int? {
        val token = saved?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (candidates.isEmpty()) return null

        if (!token.startsWith(PREFIX)) {
            val legacy = normalize(token)
            candidates.indexOfFirst { normalize(it) == legacy }
                .takeIf { it >= 0 }
                ?.let { return it }
            // Older versions persisted VLC's raw display label. Preserve those
            // users across files whose labels changed from e.g. "English" to
            // "ENG · AAC" by extracting the same semantic key on first read.
            val legacyKey = keyFor(token)
            if (legacyKey.language == null && legacyKey.roles.isEmpty()) return null
            return bestSemanticMatch(legacyKey, candidates)
        }

        val preferred = decode(token) ?: return null
        return bestSemanticMatch(preferred, candidates)
    }

    /**
     * Build a generation-local application plan without treating an early empty
     * Media3 track snapshot as final. onTracksChanged can publish audio and text
     * groups incrementally; only a resolved (or absent) saved preference closes
     * the generation.
     */
    fun applicationPlan(
        savedAudio: String?,
        savedSubtitle: String?,
        audioCandidates: List<String>,
        subtitleCandidates: List<String>,
        disabledSubtitleToken: String,
    ): ApplicationPlan {
        val wantsAudio = !savedAudio.isNullOrBlank()
        val disableSubtitles = savedSubtitle == disabledSubtitleToken
        val wantsSubtitle = !savedSubtitle.isNullOrBlank() && !disableSubtitles
        val audioIndex = bestMatchIndex(savedAudio, audioCandidates)
        val subtitleIndex = if (wantsSubtitle) {
            bestMatchIndex(savedSubtitle, subtitleCandidates)
        } else {
            null
        }
        return ApplicationPlan(
            audioIndex = audioIndex,
            subtitleIndex = subtitleIndex,
            disableSubtitles = disableSubtitles,
            complete = (!wantsAudio || audioIndex != null) &&
                (!wantsSubtitle || subtitleIndex != null),
        )
    }

    private fun bestSemanticMatch(preferred: Key, candidates: List<String>): Int? =
        candidates
            .mapIndexed { index, label -> index to score(preferred, keyFor(label)) }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { -it.first })
            ?.first

    private fun score(preferred: Key, candidate: Key): Int {
        var score = 0
        if (
            preferred.language != null &&
            preferred.language == candidate.language
        ) {
            score += 100
        }
        if (preferred.normalizedName == candidate.normalizedName) score += 80
        if (preferred.roles.isNotEmpty()) {
            score += if (preferred.roles == candidate.roles) 35
            else preferred.roles.intersect(candidate.roles).size * 10
        } else if (candidate.roles.isEmpty()) {
            score += 5
        }
        // Do not select an unrelated same-role track when neither its language nor
        // its normalized label matches the saved preference.
        if (
            preferred.language != null &&
            preferred.language != candidate.language &&
            preferred.normalizedName != candidate.normalizedName
        ) {
            return 0
        }
        return score
    }

    private fun decode(token: String): Key? {
        val fields = token.removePrefix(PREFIX)
            .split(';')
            .mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null
                else field.substring(0, separator) to field.substring(separator + 1)
            }
            .toMap()
        val name = fields["n"]?.replace('_', ' ')?.takeIf { it.isNotBlank() } ?: return null
        return Key(
            language = fields["l"]?.takeIf { it.isNotBlank() },
            roles = fields["r"]
                ?.split(',')
                ?.filterTo(linkedSetOf()) { it.isNotBlank() }
                .orEmpty(),
            normalizedName = name,
        )
    }

    private fun keyFor(label: String): Key {
        val normalized = normalize(label)
        val words = normalized.split(' ').filter { it.isNotBlank() }.toSet()
        val language = LANGUAGE_ALIASES.entries.firstOrNull { (_, aliases) ->
            aliases.any { alias -> alias in words || normalized == alias }
        }?.key
        val roles = buildSet {
            if (ROLE_COMMENTARY.any { it in normalized }) add("commentary")
            if (ROLE_DESCRIPTIVE.any { it in normalized }) add("descriptive")
            if (ROLE_FORCED.any { it in normalized }) add("forced")
            if (ROLE_HEARING_IMPAIRED.any { it in normalized }) add("hearing_impaired")
        }
        return Key(language, roles, normalized)
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    private val LANGUAGE_ALIASES = linkedMapOf(
        "tur" to setOf("turkce", "turkish", "tur"),
        "eng" to setOf("english", "eng"),
        "deu" to setOf("deutsch", "german", "deu", "ger"),
        "fra" to setOf("francais", "french", "fra", "fre"),
        "spa" to setOf("espanol", "spanish", "spa"),
        "ita" to setOf("italiano", "italian", "ita"),
        "ara" to setOf("arabic", "arabisch", "arapca", "ara"),
        "rus" to setOf("russian", "russisch", "rusca", "rus"),
        "nld" to setOf("nederlands", "dutch", "nld", "dut"),
        "por" to setOf("portugues", "portuguese", "por"),
        "pol" to setOf("polski", "polish", "pol"),
    )
    private val ROLE_COMMENTARY = setOf("commentary", "kommentar", "yorum")
    private val ROLE_DESCRIPTIVE = setOf("audio description", "descriptive", "audiodeskription")
    private val ROLE_FORCED = setOf("forced", "forciert", "zorunlu")
    private val ROLE_HEARING_IMPAIRED = setOf("sdh", "hearing impaired", "horspiel")
}
