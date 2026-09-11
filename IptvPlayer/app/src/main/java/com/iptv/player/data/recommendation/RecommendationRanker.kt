package com.iptv.player.data.recommendation

import java.util.Locale
import java.util.PriorityQueue
import kotlin.math.exp
import kotlin.math.ln

/** Metadata only: ranking never loads posters, descriptions or stream URLs. */
data class RecommendationCandidate(
    val id: String,
    val name: String,
    val genre: String?,
    val categoryId: String?,
    val rating: Double?,
    val addedAt: Long,
)

data class WatchPreference(
    val profileId: Long,
    val kind: String,
    val itemId: String,
    val day: Long,
    val watchedMs: Long,
    val lastWatchedAt: Long,
    val genre: String?,
    val categoryId: String?,
)

/** A small, local content-based recommender; it makes no external AI requests. */
internal class RecommendationRanker(
    profileId: Long,
    private val kind: String,
    history: List<WatchPreference>,
    private val nowMs: Long,
    private val excludedIds: Set<String> = emptySet(),
) {
    private val genres = mutableMapOf<String, Double>()
    private val categories = mutableMapOf<String, Double>()
    private val seen = mutableMapOf<String, Long>()
    private val confidence: Double
    private data class Scored(val item: RecommendationCandidate, val score: Double)
    private val order = compareBy<Scored> { it.score }.thenByDescending { it.item.name }
        .thenByDescending { it.item.id }
    private val bestFirst = Comparator<Scored> { first, second -> order.compare(second, first) }
    private val personal = PriorityQueue(201, order)
    private val discovery = PriorityQueue(101, order)

    init {
        val qualified = history.filter {
            it.profileId == profileId && it.watchedMs >= 120_000L &&
                nowMs - it.lastWatchedAt in 0..90L * DAY_MS
        }
        // A brief first session cannot dominate. Several days and at least an hour
        // of actual viewing gradually replace the cold-start quality ordering.
        confidence = (qualified.map { it.day }.distinct().size / 3.0).coerceAtMost(1.0) *
            (qualified.sumOf { it.watchedMs.coerceAtMost(7_200_000L) } / 3_600_000.0).coerceAtMost(1.0)
        qualified.forEach { h ->
            val ageDays = (nowMs - h.lastWatchedAt) / DAY_MS.toDouble()
            val weight = ln(1.0 + h.watchedMs.coerceAtMost(7_200_000L) / 60_000.0) * exp(-ageDays / 30.0)
            val tags = tags(h.genre)
            tags.forEach { genres[it] = (genres[it] ?: 0.0) + weight / tags.size }
            h.categoryId?.let { categories["${h.kind}:$it"] = (categories["${h.kind}:$it"] ?: 0.0) + weight }
            if (h.kind == kind) seen[h.itemId] = maxOf(seen[h.itemId] ?: 0, h.lastWatchedAt)
        }
        normalize(genres)
        normalize(categories)
    }

    fun offer(item: RecommendationCandidate) {
        if (item.id in excludedIds) return
        val lastSeen = seen[item.id]
        // Continue Watching owns unfinished titles. A series can reappear once
        // a newer episode arrives; viewing it once does not hide it forever.
        if (lastSeen != null && (kind == "movie" || item.addedAt <= lastSeen / 1000L)) return
        val age = ((nowMs / 1000 - item.addedAt).coerceAtLeast(0L) / 86_400.0)
        val fresh = if (item.addedAt in 1..nowMs / 1000) exp(-age / 30) else 0.0
        val rating = item.rating?.takeIf { it.isFinite() }?.coerceIn(0.0, 10.0) ?: 5.0
        val baseline = rating / 10 * 0.8 + fresh * 0.2
        val tags = tags(item.genre)
        val genreScore = if (tags.isEmpty()) 0.0 else tags.sumOf { genres[it] ?: 0.0 } / tags.size
        val categoryScore = categories["$kind:${item.categoryId}"] ?: 0.0
        keep(personal, Scored(item, baseline + confidence * (4.0 * genreScore + 1.5 * categoryScore)), 200)
        keep(discovery, Scored(item, baseline), 100)
    }

    fun results(limit: Int = 50): List<String> {
        val ranked = personal.sortedWith(bestFirst)
        val explore = discovery.sortedWith(bestFirst)
        val selected = linkedSetOf<String>()
        var rankIndex = 0
        var exploreIndex = 0
        val cap = limit.coerceIn(0, 50)
        while (selected.size < cap && (rankIndex < ranked.size || exploreIndex < explore.size)) {
            val discover = confidence > 0 && selected.size % 5 == 4
            val pool = if (discover) explore else ranked
            var index = if (discover) exploreIndex else rankIndex
            while (index < pool.size && pool[index].item.id in selected) index++
            if (index < pool.size) {
                selected += pool[index++].item.id
            } else {
                val fallback = (ranked + explore).firstOrNull { it.item.id !in selected } ?: break
                selected += fallback.item.id
            }
            if (discover) exploreIndex = index else rankIndex = index
        }
        return selected.toList()
    }

    private fun keep(queue: PriorityQueue<Scored>, item: Scored, cap: Int) {
        queue.offer(item)
        if (queue.size > cap) queue.poll()
    }

    private fun normalize(weights: MutableMap<String, Double>) {
        val max = weights.values.maxOrNull()?.takeIf { it > 0 } ?: return
        weights.keys.toList().forEach { weights[it] = weights.getValue(it) / max }
    }

    companion object {
        const val DAY_MS = 86_400_000L
        private val separators = Regex("[,/|;&]+")
        private val aliases = mapOf(
            "krimi" to "crime", "suç" to "crime", "mystery" to "mystery", "gizem" to "mystery",
            "komödie" to "comedy", "komedi" to "comedy", "dram" to "drama",
            "aksiyon" to "action", "macera" to "adventure", "abenteuer" to "adventure",
            "bilim kurgu" to "science fiction", "science-fiction" to "science fiction", "sci-fi" to "science fiction",
            "korku" to "horror", "aile" to "family", "familie" to "family",
            "belgesel" to "documentary", "dokumentarfilm" to "documentary", "romantik" to "romance",
        )
        private fun tags(value: String?): Set<String> = value.orEmpty().lowercase(Locale.ROOT)
            .split(separators).map { it.trim() }.filter { it.isNotBlank() }
            .map { aliases[it] ?: it }.toSet()
    }
}
