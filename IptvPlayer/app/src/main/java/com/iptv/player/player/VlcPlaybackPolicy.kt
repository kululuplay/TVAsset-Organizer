package com.iptv.player.player

/**
 * libVLC occasionally finishes a buffering cycle at 99.9 rather than exactly
 * 100. Treat that as complete so a healthy channel cannot remain behind the
 * buffering UI forever. Invalid native values remain buffering/fail-safe.
 */
internal fun isVlcBuffering(bufferingPercent: Float): Boolean =
    !bufferingPercent.isFinite() ||
        bufferingPercent < VLC_BUFFERING_COMPLETE_PERCENT

private const val VLC_BUFFERING_COMPLETE_PERCENT = 99.5f
