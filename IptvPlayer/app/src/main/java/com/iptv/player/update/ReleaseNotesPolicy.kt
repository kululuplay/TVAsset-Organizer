package com.iptv.player.update

/**
 * Turns GitHub release bodies into short customer-facing notes.
 *
 * CI metadata, commit identifiers, pull-request references and URLs are useful
 * on GitHub but look broken in a ten-foot update prompt. Only plain-language
 * lines survive, and their count/length is bounded before reaching the UI.
 */
object ReleaseNotesPolicy {
    private const val MAX_ITEMS = 5
    private const val MAX_ITEM_CHARS = 160

    private val sha = Regex("(?i)\\b[0-9a-f]{7,40}\\b")
    private val markdownLink = Regex("\\[([^]]+)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val pullRequest = Regex("\\s*\\(#\\d+\\)")
    private val formatting = Regex("[`*_~]")

    fun customerFacing(raw: String?): String? {
        val items = raw
            ?.replace('\r', '\n')
            ?.lineSequence()
            ?.mapNotNull(::cleanLine)
            ?.distinct()
            ?.take(MAX_ITEMS)
            ?.toList()
            .orEmpty()

        return items.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }
    }

    private fun cleanLine(rawLine: String): String? {
        val original = rawLine.trim()
        if (original.isBlank()) return null

        var line = original
            .trimStart('#', '-', '*', '+', ' ', '\t')
            .trim()
        if (line.isBlank() || isTechnical(line)) return null

        line = markdownLink.replace(line, "$1")
        line = url.replace(line, "")
        line = pullRequest.replace(line, "")
        line = formatting.replace(line, "")
        line = line.replace(Regex("\\s{2,}"), " ").trim().trimEnd('.', ';')
        if (line.isBlank() || isTechnical(line)) return null
        return line.take(MAX_ITEM_CHARS).trimEnd()
    }

    private fun isTechnical(line: String): Boolean {
        val normalized = line.lowercase()
        return normalized in GENERIC_HEADINGS ||
            normalized.contains("commit") ||
            normalized.contains("merge pull request") ||
            normalized.contains("full changelog") ||
            normalized.contains("github actions") ||
            normalized.startsWith("compare ") ||
            normalized.startsWith("build:") ||
            normalized.startsWith("ci:") ||
            normalized.startsWith("chore:") ||
            (normalized.contains("automated") &&
                (normalized.contains("build") || normalized.contains("release"))) ||
            sha.containsMatchIn(line)
    }

    private val GENERIC_HEADINGS = setOf(
        "what's changed",
        "what’s changed",
        "what's new",
        "what’s new",
        "release notes",
        "changes",
    )
}
