package com.iptv.player.player

import com.iptv.player.data.model.DecoderMode
import com.iptv.player.data.model.PlayerMode
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailureClassifier
import java.net.URI

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

    /** Native evidence available when libVLC emits its otherwise-untyped error. */
    data class VlcErrorEvidence(
        val playbackStarted: Boolean,
        val voutSeen: Boolean,
        val decodedVideo: Int?,
        val displayedPictures: Int?,
        val readBytes: Int?,
        val videoCodec: String?,
    )

    enum class CustomerMessage {
        AUTHORIZATION,
        ACCESS_DENIED,
        CONTENT_UNAVAILABLE,
        RANGE_REJECTED,
        RATE_LIMITED,
        SERVER_UNAVAILABLE,
        TIMEOUT,
        TLS,
        DNS,
        DECODER,
        VIDEO_OUTPUT,
        SOURCE,
        GENERIC,
    }

    data class CustomerError(
        val message: CustomerMessage,
        val supportCode: String,
    )

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

    /**
     * libVLC exposes no structured cause with EncounteredError. Do not blindly
     * call every native error a network failure: input plus an announced video
     * pipeline is useful decoder evidence, and decoded-but-never-displayed frames
     * are output evidence. This is deliberately conservative so a CDN reset after
     * healthy playback is not mislabeled as a codec failure.
     */
    fun classifyVlcEncounteredError(
        evidence: VlcErrorEvidence,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure {
        val decoded = evidence.decodedVideo?.coerceAtLeast(0)
        val displayed = evidence.displayedPictures?.coerceAtLeast(0)
        val inputSeen = (evidence.readBytes ?: 0) > 0
        val decodedButNotDisplayed =
            decoded != null && decoded > 0 && displayed != null && displayed == 0

        if (evidence.voutSeen && decodedButNotDisplayed) {
            return PlaybackFailureClassifier.classify(
                FailureSignal.Output(PlaybackFailure.Component.VIDEO),
                phase,
            )
        }

        val hevcInputFailed =
            inputSeen &&
                !evidence.playbackStarted &&
                isHevc(evidence.videoCodec) &&
                (decoded ?: 0) == 0 &&
                (displayed ?: 0) == 0
        val videoPipelineFailed =
            inputSeen &&
                evidence.voutSeen &&
                decoded != null &&
                decoded == 0 &&
                !evidence.playbackStarted
        if (hevcInputFailed || videoPipelineFailed) {
            return PlaybackFailureClassifier.classify(
                FailureSignal.Decoder(
                    FailureSignal.DecoderKind.RUNTIME,
                    PlaybackFailure.Component.VIDEO,
                ),
                phase,
            )
        }

        return PlaybackFailureClassifier.classify(
            FailureSignal.Network(FailureSignal.NetworkKind.CONNECT),
            phase,
        )
    }

    fun customerError(failure: PlaybackFailure): CustomerError {
        val status = failure.httpStatus
        return when {
            status == 401 -> CustomerError(CustomerMessage.AUTHORIZATION, "HTTP-401")
            status == 403 -> CustomerError(CustomerMessage.ACCESS_DENIED, "HTTP-403")
            status == 404 || status == 410 ->
                CustomerError(CustomerMessage.CONTENT_UNAVAILABLE, "HTTP-$status")
            status == 416 -> CustomerError(CustomerMessage.RANGE_REJECTED, "HTTP-416")
            status == 429 -> CustomerError(CustomerMessage.RATE_LIMITED, "HTTP-429")
            status != null && status in 500..599 ->
                CustomerError(CustomerMessage.SERVER_UNAVAILABLE, "HTTP-$status")
            failure.code == PlaybackFailure.Code.READ_TIMEOUT ||
                failure.code == PlaybackFailure.Code.STARTUP_TIMEOUT ||
                failure.code == PlaybackFailure.Code.PLAYBACK_STALL ||
                failure.code == PlaybackFailure.Code.SEEK_TIMEOUT ||
                failure.code == PlaybackFailure.Code.HTTP_REQUEST_TIMEOUT ->
                CustomerError(CustomerMessage.TIMEOUT, "VOD-TIMEOUT")
            failure.code == PlaybackFailure.Code.TLS_FAILED ->
                CustomerError(CustomerMessage.TLS, "VOD-TLS")
            failure.code == PlaybackFailure.Code.DNS_LOOKUP_FAILED ->
                CustomerError(CustomerMessage.DNS, "VOD-DNS")
            failure.category == PlaybackFailure.Category.DECODER ||
                failure.code == PlaybackFailure.Code.CODEC_UNSUPPORTED ->
                CustomerError(CustomerMessage.DECODER, "VOD-DECODER")
            failure.code == PlaybackFailure.Code.VIDEO_OUTPUT_FAILED ->
                CustomerError(CustomerMessage.VIDEO_OUTPUT, "VOD-OUTPUT")
            failure.category == PlaybackFailure.Category.NETWORK ||
                failure.category == PlaybackFailure.Category.SOURCE ||
                failure.category == PlaybackFailure.Category.FORMAT ->
                CustomerError(CustomerMessage.SOURCE, "VOD-SOURCE")
            else -> CustomerError(CustomerMessage.GENERIC, "VOD-UNKNOWN")
        }
    }

    fun isHevc(codec: String?): Boolean {
        val normalized = codec?.trim()?.lowercase().orEmpty()
        return normalized.contains("hevc") ||
            normalized.contains("h265") ||
            normalized.contains("h.265") ||
            normalized.contains("hev1") ||
            normalized.contains("hvc1")
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
        // DNS/HTTP/CDN errors are not decoder evidence. The coordinator retries
        // the same route first, then permits one different engine (never another
        // decoder of the same engine) for provider-specific transport quirks.
        if (failure == Failure.SOURCE) {
            return when (current) {
                Route.EXO -> listOf(
                    if (decoderMode == DecoderMode.SOFTWARE) {
                        Route.VLC_SOFTWARE
                    } else {
                        Route.VLC_HARDWARE
                    },
                )
                Route.VLC_HARDWARE,
                Route.VLC_SOFTWARE -> listOf(Route.EXO)
            }
        }

        if (failure == Failure.SOFTWARE_TOO_SLOW) {
            return when (current) {
                Route.VLC_SOFTWARE -> listOf(Route.EXO, Route.VLC_HARDWARE)
                else -> emptyList()
            }
        }

        return when (mode) {
            PlayerMode.AUTO -> when (current) {
                Route.EXO -> listOf(Route.VLC_HARDWARE, Route.VLC_SOFTWARE)
                // A VLC hardware decoder failure should reach software directly;
                // this is the bounded rescue path for HEVC-broken TV chipsets.
                Route.VLC_HARDWARE -> listOf(Route.VLC_SOFTWARE, Route.EXO)
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

/** Redirect policy used by the Media3 VOD data source without exposing URLs. */
internal object VodHttpRedirectPolicy {
    private val REDIRECT_STATUSES = setOf(300, 301, 302, 303, 307, 308)

    /**
     * Same-scheme redirects remain owned by HttpURLConnection. Only a single
     * HTTP -> HTTPS upgrade is accepted here; HTTPS -> HTTP is never returned.
     */
    fun httpsUpgradeTarget(
        originalUrl: String,
        status: Int,
        location: String?,
    ): String? {
        if (status !in REDIRECT_STATUSES || location.isNullOrBlank()) return null
        val original = runCatching { URI(originalUrl) }.getOrNull() ?: return null
        if (!original.scheme.equals("http", ignoreCase = true)) return null
        val target = runCatching { original.resolve(location.trim()) }.getOrNull() ?: return null
        if (!target.scheme.equals("https", ignoreCase = true) || target.host.isNullOrBlank()) {
            return null
        }
        return target.toASCIIString()
    }
}
