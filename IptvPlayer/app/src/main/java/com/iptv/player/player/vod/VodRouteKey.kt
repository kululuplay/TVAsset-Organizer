package com.iptv.player.player.vod

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Builds a stable route-memory key without persisting URLs, credentials or IDs. */
internal object VodRouteKey {

    fun create(
        activeProfileId: Long,
        contentId: String?,
        streamUrl: String,
        playerMode: PlayerMode,
        decoderMode: DecoderMode,
    ): String? {
        val id = contentId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val host = runCatching { URI(streamUrl).host }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val opaqueItem = shortHash("$activeProfileId\u0000$host\u0000$id")
        return "$POLICY|${playerMode.name}|${decoderMode.name}|$opaqueItem"
    }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(HASH_BYTES)
            .joinToString(separator = "") { byte ->
                "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
            }

    private const val POLICY = "vod-route-v1"
    private const val HASH_BYTES = 12
}
