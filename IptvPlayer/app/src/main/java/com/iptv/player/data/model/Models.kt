/*
 * Models.kt
 * Plain domain models used across the UI and player layers. These are decoupled
 * from both the Room entities and the Xtream/M3U network DTOs so the rest of the
 * app never depends on a specific source format.
 */
package com.iptv.player.data.model

/** A content category / group (Live TV group, VOD category, etc.). */
data class Category(
    val id: String,
    val name: String,
    val type: ContentType = ContentType.LIVE,
    /** Number of channels in this category (null = unknown / not shown). */
    val count: Int? = null
)

/** A single playable channel / stream. */
data class Channel(
    val id: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val epgChannelId: String? = null,
    val number: Int? = null,
    val type: ContentType = ContentType.LIVE,
    var isFavorite: Boolean = false,
    /** Catch-up / archive window in days (0 = no timeshift available). */
    val catchupDays: Int = 0,
    /** 0-based index in the source's stream list, preserving server order. */
    val position: Int = 0,
    /** 0-based index of this channel's category in the source's category list. */
    val categoryPosition: Int = Int.MAX_VALUE,
    /** True for radio stations; kept off the Live TV page (own Radio folder). */
    val isRadio: Boolean = false
)

/** A channel paired with its manager state (hidden flag) for the channel editor. */
data class ManagedChannel(
    val channel: Channel,
    val hidden: Boolean,
    val isFavorite: Boolean = false
)

/** A category paired with its manager state (hidden flag) for the content editor. */
data class ManagedCategory(
    val category: Category,
    val hidden: Boolean
)

enum class ContentType { LIVE, VOD, SERIES }

/** Where the channel list came from. Stored so we can refresh on next launch. */
enum class SourceType { XTREAM, M3U_URL }

/** Player engine selection exposed in Settings. */
enum class PlayerMode {
    /** ExoPlayer first, automatically fall back to libVLC on failure. */
    AUTO,
    EXOPLAYER,
    VLC;

    companion object {
        fun fromName(value: String?): PlayerMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * How aggressively to use the hardware decoder versus software (libVLC). In AUTO
 * the player starts on hardware and automatically drops a stream to software
 * decode when it greens / blanks or errors. Exposed in Settings.
 */
enum class DecoderMode {
    /** Hardware first, automatic software fallback on green/blank or failure. */
    AUTO,
    /** Always use the hardware decoder (no software fallback). */
    HARDWARE,
    /** Always decode in software (libVLC) — most compatible, heaviest on weak sticks. */
    SOFTWARE;

    companion object {
        // Default = AUTO (per user request): hardware-first with automatic
        // software fallback on decode failure -- the lightest path on weak
        // sticks while staying compatible. CAVEAT: on some Amlogic boxes (e.g.
        // Xiaomi Stick) the hardware decoder paints frames in the wrong colour
        // plane -> a GREEN picture with audio still fine, and that green is
        // undetectable in software (libVLC reports a healthy decode, frames keep
        // arriving, the opaque HW surface can't be read back) so there is no
        // reliable auto-fallback for it. Users hitting a green screen can switch
        // to Software in Settings; users wanting max performance can pick AUTO/
        // Hardware. Software's downside is possible macroblocking on heavy
        // channels (softened by avcodec-skiploopfilter=nonref + avcodec-fast).
        fun fromName(value: String?): DecoderMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * Live stream container format the user prefers. Xtream live channels are stored
 * with the default `.ts` extension; choosing HLS rewrites that to `.m3u8` at
 * playback time (some providers serve both and one path is smoother than the
 * other on a given network). VOD/series keep their real container extension.
 */
enum class StreamFormat(val extension: String) {
    /** MPEG-TS (`.ts`) — the most compatible Xtream live transport. */
    TS("ts"),
    /** HLS (`.m3u8`) — adaptive segmented delivery. */
    HLS("m3u8");

    companion object {
        fun fromName(value: String?): StreamFormat =
            entries.firstOrNull { it.name == value } ?: TS
    }
}

/**
 * Network/cache buffer size. A larger buffer trades a little zap latency for far
 * fewer stalls on jittery connections; a smaller one zaps faster. Carries the
 * concrete values for both engines so the choice maps cleanly onto libVLC's
 * network/live caching and ExoPlayer's DefaultLoadControl durations.
 */
enum class BufferMode(
    /**
     * libVLC --network-caching / --live-caching (ms) AND the basis for the
     * ExoPlayer buffer below. NOTE: the live VLC path clamps this to ~3000ms
     * (libVLC's audio timestamp conversion bound); values above that only grow
     * the ExoPlayer buffer, never the VLC one.
     */
    val networkCachingMs: Int,
    /** ExoPlayer DefaultLoadControl min buffer (ms). */
    val exoMinBufferMs: Int,
    /** ExoPlayer DefaultLoadControl max buffer (ms). */
    val exoMaxBufferMs: Int,
    /** ExoPlayer buffer required before (re)starting playback (ms). */
    val exoPlaybackMs: Int,
    /** ExoPlayer buffer required after a rebuffer (ms). */
    val exoRebufferMs: Int
) {
    LOW(1500, 1000, 4000, 500, 1000),
    NORMAL(5000, 3000, 12000, 1500, 2500),
    HIGH(10000, 6000, 20000, 3000, 5000);

    companion object {
        fun fromName(value: String?): BufferMode =
            entries.firstOrNull { it.name == value } ?: NORMAL
    }
}

/** Stored connection profile (one active source at a time for this milestone). */
data class SourceConfig(
    val type: SourceType,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = ""
)
