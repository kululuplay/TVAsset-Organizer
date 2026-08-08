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
 * One atomic snapshot of the two settings that define live playback routing.
 *
 * Keeping the pair together prevents a controller from being built with values
 * read from two different DataStore revisions while the user changes Settings.
 */
data class PlaybackSelection(
    val player: PlayerMode,
    val decoder: DecoderMode,
)

/**
 * Hardware/software decode policy shared by live TV and on-demand playback. In
 * AUTO, live TV follows the tested engine ladder and VOD may rebuild VLC on
 * software after a confirmed hardware failure. Manual choices are preferences,
 * not traps: a confirmed green/frozen/unsupported path may use one bounded
 * compatibility fallback for the affected stream.
 */
enum class DecoderMode {
    /** Hardware first, with a bounded fallback after confirmed playback failure. */
    AUTO,
    /** Prefer the hardware decoder; allow emergency recovery if output is invalid. */
    HARDWARE,
    /** Prefer VLC software decode — most compatible, heaviest on weak sticks. */
    SOFTWARE;

    companion object {
        // AUTO stays the safe default: hardware performance first, bounded
        // evidence-based recovery second. Device-model guesses are deliberately
        // excluded because they misrouted healthy streams on real hardware.
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
    /** Learns a bounded per-session target from real rebuffers and device RAM. */
    ADAPTIVE(5000, 3000, 12000, 1500, 2500),
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
