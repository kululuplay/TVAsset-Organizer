package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.playback.core.PlaybackFailure

/**
 * Bounded, format-aware engine selection for movies, episodes and catch-up.
 *
 * User engine/decoder settings are preferences. AUTO uses Media3 for formats
 * where Android provides the best seek/ABR integration, while VLC remains the
 * compatibility route for opaque/legacy containers. A playback item may visit a
 * route at most once, preventing EXO/VLC or hardware/software ping-pong.
 */
internal object VodPlaybackRoutingPolicy {

    enum class Route { EXO, VLC_HARDWARE, VLC_SOFTWARE }

    /** URL-free source shape consumed by the coordinator/reducer boundary. */
    enum class ContentHint { MEDIA3_PREFERRED, VLC_COMPATIBILITY }

    enum class Failure {
        SOURCE,
        STARTUP,
        VIDEO_OUTPUT,
        DECODER,
        AUDIO,
        SOFTWARE_TOO_SLOW,
    }

    fun initialRoute(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        url: String,
    ): Route = initialRoute(
        mode = mode,
        decoderMode = decoderMode,
        contentHint = if (isMedia3Preferred(url)) {
            ContentHint.MEDIA3_PREFERRED
        } else {
            ContentHint.VLC_COMPATIBILITY
        },
    )

    /** Select an initial route without putting a URL into reducer state/events. */
    fun initialRoute(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        contentHint: ContentHint,
    ): Route = when (mode) {
        PlayerMode.EXOPLAYER -> Route.EXO
        PlayerMode.VLC -> if (decoderMode == DecoderMode.SOFTWARE) {
            Route.VLC_SOFTWARE
        } else {
            Route.VLC_HARDWARE
        }
        PlayerMode.AUTO -> when {
            decoderMode == DecoderMode.SOFTWARE -> Route.VLC_SOFTWARE
            contentHint == ContentHint.MEDIA3_PREFERRED -> Route.EXO
            else -> Route.VLC_HARDWARE
        }
    }

    fun nextRoute(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        current: Route,
        failure: Failure,
        tried: Set<Route>,
    ): Route? = candidates(mode, decoderMode, current, failure)
        .firstOrNull { it != current && it !in tried }

    /** Translate the shared typed engine failure into the bounded VOD ladder. */
    fun routeFailure(failure: PlaybackFailure): Failure = when (failure.category) {
        PlaybackFailure.Category.DECODER -> Failure.DECODER
        PlaybackFailure.Category.OUTPUT -> when (failure.component) {
            PlaybackFailure.Component.AUDIO -> Failure.AUDIO
            else -> Failure.VIDEO_OUTPUT
        }
        PlaybackFailure.Category.FORMAT -> Failure.DECODER
        PlaybackFailure.Category.SOURCE -> when (failure.code) {
            PlaybackFailure.Code.CODEC_UNSUPPORTED,
            PlaybackFailure.Code.SOURCE_UNSUPPORTED -> Failure.DECODER
            else -> Failure.SOURCE
        }
        PlaybackFailure.Category.TIMEOUT -> when (failure.code) {
            PlaybackFailure.Code.STARTUP_TIMEOUT -> Failure.STARTUP
            else -> Failure.SOURCE
        }
        PlaybackFailure.Category.RESOURCE -> Failure.SOFTWARE_TOO_SLOW
        else -> Failure.SOURCE
    }

    /** URL query/fragment never participates in the container decision. */
    fun isMedia3Preferred(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return MEDIA3_EXTENSIONS.any(path::endsWith)
    }

    private fun candidates(
        mode: PlayerMode,
        decoderMode: DecoderMode,
        current: Route,
        failure: Failure,
    ): List<Route> {
        // DNS/HTTP/CDN errors are not decoder evidence. The owner retries the
        // same route with backoff and must not churn native engines for them.
        if (failure == Failure.SOURCE) return emptyList()

        if (failure == Failure.SOFTWARE_TOO_SLOW) {
            return when (current) {
                Route.VLC_SOFTWARE -> listOf(Route.EXO, Route.VLC_HARDWARE)
                else -> emptyList()
            }
        }

        return when (mode) {
            PlayerMode.AUTO -> when (current) {
                Route.EXO -> listOf(Route.VLC_HARDWARE, Route.VLC_SOFTWARE)
                Route.VLC_HARDWARE -> listOf(Route.EXO, Route.VLC_SOFTWARE)
                Route.VLC_SOFTWARE -> listOf(Route.EXO, Route.VLC_HARDWARE)
            }
            PlayerMode.EXOPLAYER -> when (current) {
                Route.EXO -> listOf(Route.VLC_HARDWARE, Route.VLC_SOFTWARE)
                Route.VLC_HARDWARE -> listOf(Route.VLC_SOFTWARE)
                Route.VLC_SOFTWARE -> emptyList()
            }
            PlayerMode.VLC -> when (current) {
                Route.VLC_HARDWARE -> listOf(Route.VLC_SOFTWARE, Route.EXO)
                Route.VLC_SOFTWARE -> listOf(Route.VLC_HARDWARE, Route.EXO)
                Route.EXO -> emptyList()
            }
        }.filter { route ->
            // Explicit hardware never falls into software for a generic startup
            // delay; confirmed decoder/output/audio evidence may still rescue it.
            decoderMode != DecoderMode.HARDWARE ||
                route != Route.VLC_SOFTWARE ||
                failure in setOf(Failure.VIDEO_OUTPUT, Failure.DECODER, Failure.AUDIO)
        }
    }

    private val MEDIA3_EXTENSIONS = setOf(
        ".m3u8",
        ".mpd",
        ".mp4",
        ".m4v",
        ".mov",
        ".webm",
    )
}
