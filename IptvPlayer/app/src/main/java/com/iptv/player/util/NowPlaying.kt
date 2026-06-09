/*
 * NowPlaying.kt
 * Holds what the user is watching RIGHT NOW so the heartbeat can surface it on the
 * ops panel. The player screens set it on (re)start and clear it on stop.
 *
 * Uses an owner token (the activity instance) for clearing: VOD "next episode"
 * launches a new player activity and then finishes the old one, so the old screen's
 * onStop would otherwise wipe the value the new screen just set. clear() is a no-op
 * unless the caller still owns the slot.
 */
package com.iptv.player.util

object NowPlaying {

    @Volatile private var owner: Any? = null

    @Volatile
    var title: String? = null
        private set

    @Volatile
    var kind: String? = null
        private set

    /** Mark [title] (with optional [kind]) as the current playback, owned by [owner]. */
    fun set(owner: Any, title: String, kind: String?) {
        this.owner = owner
        this.title = title.takeIf { it.isNotBlank() }
        this.kind = kind?.takeIf { it.isNotBlank() }
    }

    /** Clear only if [owner] still holds the slot (a newer owner takes precedence). */
    fun clear(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            this.title = null
            this.kind = null
        }
    }
}
