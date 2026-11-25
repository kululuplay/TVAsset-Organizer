/*
 * WatchNextPolicy.kt
 * Framework-free rules for the "Continue watching" cards the app shows in the
 * Watch Next row of the Android TV / Google TV home screen. That row is visible
 * to everyone in front of the TV, so the rules are deliberately strict.
 */
package com.iptv.player.watchnext

import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.ui.common.PinLockHelper

object WatchNextPolicy {

    /** Cards shown on the home screen at most. */
    const val MAX_ITEMS = 5

    /** Recent resume rows read as candidates; spare ones replace skipped titles. */
    const val CANDIDATE_LIMIT = 20

    /** Playback saves the position every few seconds; the same cards are rewritten at most this often. */
    const val MIN_WRITE_INTERVAL_MS = 60_000L

    /** How long a card click stays valid while the app starts (it survives a process recycle). */
    const val REQUEST_TTL_MS = 60_000L

    /** "vod_<id>" for films, "ep_<id>" for episodes: the only ids a card may carry. */
    private val CONTENT_ID = Regex("(vod|ep)_[A-Za-z0-9._-]{1,64}")

    /**
     * The titles to show, newest first. Nothing for a protected profile; never an
     * adult title ([adultByCategory] answers for the title's catalogue category,
     * and must say true when it does not know); only films and episodes that were
     * started and not finished, with a known length for the progress bar.
     */
    inline fun select(
        items: List<ContinueItem>,
        profileProtected: Boolean,
        adultByCategory: (ContinueItem) -> Boolean,
    ): List<ContinueItem> {
        val selected = ArrayList<ContinueItem>(MAX_ITEMS)
        if (profileProtected) return selected
        for (item in items.sortedByDescending { it.updatedAt }) {
            if (selected.size == MAX_ITEMS) break
            if (item.kind == ResumeKind.CATCHUP) continue
            if (item.durationMs <= 0L || item.positionMs !in 1 until item.durationMs) continue
            // A card that could not be opened again is not shown.
            if (validContentId(item.contentId) == null) continue
            if (PinLockHelper.looksAdult(item.title) || adultByCategory(item)) continue
            selected += item
        }
        return selected
    }

    /** [raw] comes from outside the app (a card intent): only a well-formed id passes. */
    fun validContentId(raw: String?): String? = raw?.takeIf(CONTENT_ID::matches)

    fun isRequestFresh(requestedAtMs: Long, nowMs: Long): Boolean =
        nowMs - requestedAtMs in 0..REQUEST_TTL_MS
}
