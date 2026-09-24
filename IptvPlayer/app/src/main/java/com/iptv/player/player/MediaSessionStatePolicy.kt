package com.iptv.player.player

/**
 * Pure dedupe for MediaSession playback-state publishing. Every publish is a
 * binder round trip fanned out to the launcher's now-playing card and the
 * Bluetooth AVRCP service; on a weak stick the engine's per-tick
 * `setPlaying(true)` calls turned into hundreds of identical updates per
 * engine switch. Only a change in state, actions or reported position is
 * worth publishing.
 */
internal object MediaSessionStatePolicy {

    data class Published(val state: Int, val actions: Long, val positionMs: Long)

    fun shouldPublish(last: Published?, next: Published): Boolean = last != next
}
