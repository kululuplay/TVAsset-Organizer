package com.iptv.player.player

/** Source rejections cannot be repaired by decoder retries. No transport fallback. */
internal object LiveTransportPolicy {
    fun isAuthoritativeHttpFailure(status: Int?): Boolean =
        status != null && status in 400..499 && status != 408 && status != 429
}
