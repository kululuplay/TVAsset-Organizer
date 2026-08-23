package com.iptv.player.playback.core

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Engine-independent failure information that is safe to persist in QoE telemetry.
 *
 * The model deliberately has no free-form message, URL or request-header field. An
 * engine adapter should translate its native exception into a typed [FailureSignal]
 * at the player boundary and keep the original exception in the local redacted log.
 */
data class PlaybackFailure(
    val category: Category,
    val code: Code,
    val phase: Phase = Phase.UNKNOWN,
    val component: Component = Component.UNKNOWN,
    val retryAdvice: RetryAdvice = RetryAdvice.UNKNOWN,
    val httpStatus: Int? = null,
    /** Closed, credential-free evidence present only for a proven audio-path fault. */
    val audioEvidence: AudioFailureEvidence? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 400..599) {
            "HTTP failure status must be in the inclusive 400..599 range"
        }
        require(httpStatus == null || code in HTTP_CODES) {
            "HTTP status can only accompany an HTTP failure code"
        }
        require(code !in HTTP_CODES || httpStatus != null) {
            "HTTP failure codes must include the numeric status"
        }
        require(audioEvidence == null || component == Component.AUDIO) {
            "Audio evidence can only accompany an AUDIO component failure"
        }
    }

    enum class Category {
        NETWORK,
        AUTHORIZATION,
        SOURCE,
        FORMAT,
        DECODER,
        OUTPUT,
        DRM,
        TIMEOUT,
        RESOURCE,
        CANCELLED,
        UNKNOWN,
    }

    enum class Code {
        NETWORK_UNAVAILABLE,
        DNS_LOOKUP_FAILED,
        CONNECTION_FAILED,
        CONNECTION_RESET,
        READ_TIMEOUT,
        END_OF_STREAM,
        TLS_FAILED,
        HTTP_UNAUTHORIZED,
        HTTP_FORBIDDEN,
        HTTP_NOT_FOUND,
        HTTP_REQUEST_TIMEOUT,
        HTTP_RATE_LIMITED,
        HTTP_CLIENT_ERROR,
        HTTP_SERVER_ERROR,
        SOURCE_MALFORMED,
        SOURCE_UNSUPPORTED,
        CODEC_UNSUPPORTED,
        DECODER_INIT_FAILED,
        DECODER_RUNTIME_FAILED,
        DECODER_RESOURCES_RECLAIMED,
        AUDIO_SINK_FAILED,
        AUDIO_STALL,
        VIDEO_OUTPUT_FAILED,
        DRM_PROVISIONING_FAILED,
        DRM_LICENSE_FAILED,
        DRM_CONTENT_RESTRICTED,
        STARTUP_TIMEOUT,
        PLAYBACK_STALL,
        SEEK_TIMEOUT,
        OUT_OF_MEMORY,
        RESOURCE_EXHAUSTED,
        CANCELLED,
        UNKNOWN,
    }

    enum class Phase {
        RESOLVE,
        CONNECT,
        OPEN_SOURCE,
        STARTUP,
        PLAYBACK,
        SEEK,
        TRACK_SELECTION,
        SHUTDOWN,
        UNKNOWN,
    }

    enum class Component {
        TRANSPORT,
        MANIFEST,
        CONTAINER,
        VIDEO,
        AUDIO,
        SUBTITLE,
        DRM,
        PLAYER,
        UNKNOWN,
    }

    /** A hint only; the session routing policy remains the final authority. */
    enum class RetryAdvice {
        WAIT_FOR_NETWORK,
        RETRY_SAME_ROUTE,
        TRY_ALTERNATE_TRANSPORT,
        TRY_ALTERNATE_DECODER,
        TRY_ALTERNATE_ENGINE,
        DO_NOT_RETRY,
        UNKNOWN,
    }

    companion object {
        private val HTTP_CODES = setOf(
            Code.HTTP_UNAUTHORIZED,
            Code.HTTP_FORBIDDEN,
            Code.HTTP_NOT_FOUND,
            Code.HTTP_REQUEST_TIMEOUT,
            Code.HTTP_RATE_LIMITED,
            Code.HTTP_CLIENT_ERROR,
            Code.HTTP_SERVER_ERROR,
        )
    }
}

/**
 * Privacy-reviewed audio evidence suitable for the bounded QoE payload.
 *
 * Raw MIME strings, decoder names and exception messages deliberately cannot be
 * represented here. Media3 adapters reduce them to these closed enums before the
 * evidence leaves the engine boundary.
 */
data class AudioFailureEvidence(
    val codec: Codec,
    val decoder: Decoder,
    val sinkEvent: SinkEvent,
    val outputMode: OutputMode,
) {
    enum class Codec { AC3, E_AC3, AAC, MPEG_AUDIO, OTHER, UNKNOWN }

    enum class Decoder { HARDWARE, SOFTWARE, UNKNOWN }

    enum class SinkEvent { CLOCK_STALL, UNDERRUN, SINK_ERROR, CODEC_ERROR }

    enum class OutputMode { PCM, PASSTHROUGH }
}

/** Media-library-free signals accepted by [PlaybackFailureClassifier]. */
sealed interface FailureSignal {
    data class Http(val status: Int) : FailureSignal {
        init {
            require(status in 400..599) { "HTTP failure status must be in 400..599" }
        }
    }

    data class Network(val kind: NetworkKind) : FailureSignal

    data class Source(val kind: SourceKind) : FailureSignal

    data class Decoder(
        val kind: DecoderKind,
        val component: PlaybackFailure.Component,
    ) : FailureSignal {
        init {
            require(
                component == PlaybackFailure.Component.VIDEO ||
                    component == PlaybackFailure.Component.AUDIO,
            ) { "Decoder failures must identify VIDEO or AUDIO" }
        }
    }

    data class Output(val component: PlaybackFailure.Component) : FailureSignal {
        init {
            require(
                component == PlaybackFailure.Component.VIDEO ||
                    component == PlaybackFailure.Component.AUDIO,
            ) { "Output failures must identify VIDEO or AUDIO" }
        }
    }

    data class AudioStall(val evidence: AudioFailureEvidence) : FailureSignal

    data class Drm(val kind: DrmKind) : FailureSignal

    data class Timeout(val kind: TimeoutKind) : FailureSignal

    data class Resource(val kind: ResourceKind) : FailureSignal

    data object Cancelled : FailureSignal

    data object Unknown : FailureSignal

    enum class NetworkKind {
        UNAVAILABLE,
        DNS,
        CONNECT,
        RESET,
        READ_TIMEOUT,
        END_OF_STREAM,
        TLS,
    }

    enum class SourceKind {
        MALFORMED,
        UNSUPPORTED_CONTAINER,
        UNSUPPORTED_CODEC,
    }

    enum class DecoderKind {
        INIT,
        RUNTIME,
        RESOURCES_RECLAIMED,
    }

    enum class DrmKind {
        PROVISIONING,
        LICENSE,
        CONTENT_RESTRICTED,
    }

    enum class TimeoutKind {
        STARTUP,
        STALL,
        SEEK,
    }

    enum class ResourceKind {
        OUT_OF_MEMORY,
        EXHAUSTED,
    }
}

/** Shared classification rules for ExoPlayer, VLC and future playback engines. */
object PlaybackFailureClassifier {

    fun classify(
        signal: FailureSignal,
        phase: PlaybackFailure.Phase = PlaybackFailure.Phase.UNKNOWN,
    ): PlaybackFailure = when (signal) {
        is FailureSignal.Http -> classifyHttp(signal.status, phase)
        is FailureSignal.Network -> classifyNetwork(signal.kind, phase)
        is FailureSignal.Source -> classifySource(signal.kind, phase)
        is FailureSignal.Decoder -> classifyDecoder(signal, phase)
        is FailureSignal.Output -> classifyOutput(signal.component, phase)
        is FailureSignal.AudioStall -> classifyAudioStall(signal.evidence, phase)
        is FailureSignal.Drm -> classifyDrm(signal.kind, phase)
        is FailureSignal.Timeout -> classifyTimeout(signal.kind, phase)
        is FailureSignal.Resource -> classifyResource(signal.kind, phase)
        FailureSignal.Cancelled -> PlaybackFailure(
            category = PlaybackFailure.Category.CANCELLED,
            code = PlaybackFailure.Code.CANCELLED,
            phase = phase,
            component = PlaybackFailure.Component.PLAYER,
            retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
        )
        FailureSignal.Unknown -> unknown(phase)
    }

    /**
     * Classifies common Java transport exceptions without retaining exception
     * messages, because messages frequently contain full credentialed URLs.
     */
    fun classifyThrowable(
        throwable: Throwable,
        phase: PlaybackFailure.Phase = PlaybackFailure.Phase.UNKNOWN,
    ): PlaybackFailure {
        val signal = generateSequence(throwable) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .toList()
            .asReversed()
            .mapNotNull(::signalForThrowable)
            .firstOrNull()
            ?: FailureSignal.Unknown
        return classify(signal, phase)
    }

    private fun signalForThrowable(throwable: Throwable): FailureSignal? = when (throwable) {
        is OutOfMemoryError -> FailureSignal.Resource(FailureSignal.ResourceKind.OUT_OF_MEMORY)
        is SocketTimeoutException -> FailureSignal.Network(FailureSignal.NetworkKind.READ_TIMEOUT)
        is UnknownHostException -> FailureSignal.Network(FailureSignal.NetworkKind.DNS)
        is SSLException -> FailureSignal.Network(FailureSignal.NetworkKind.TLS)
        is ConnectException -> FailureSignal.Network(FailureSignal.NetworkKind.CONNECT)
        is EOFException -> FailureSignal.Network(FailureSignal.NetworkKind.END_OF_STREAM)
        is SocketException -> FailureSignal.Network(FailureSignal.NetworkKind.RESET)
        is IOException -> FailureSignal.Network(FailureSignal.NetworkKind.CONNECT)
        else -> null
    }

    private fun classifyHttp(
        status: Int,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure {
        require(status in 400..599) { "HTTP failure status must be in 400..599" }
        val (category, code, advice) = when (status) {
            401 -> Triple(
                PlaybackFailure.Category.AUTHORIZATION,
                PlaybackFailure.Code.HTTP_UNAUTHORIZED,
                PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
            )
            403 -> Triple(
                PlaybackFailure.Category.AUTHORIZATION,
                PlaybackFailure.Code.HTTP_FORBIDDEN,
                PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
            )
            404, 410 -> Triple(
                PlaybackFailure.Category.SOURCE,
                PlaybackFailure.Code.HTTP_NOT_FOUND,
                PlaybackFailure.RetryAdvice.TRY_ALTERNATE_TRANSPORT,
            )
            408 -> Triple(
                PlaybackFailure.Category.TIMEOUT,
                PlaybackFailure.Code.HTTP_REQUEST_TIMEOUT,
                PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE,
            )
            429 -> Triple(
                PlaybackFailure.Category.NETWORK,
                PlaybackFailure.Code.HTTP_RATE_LIMITED,
                PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE,
            )
            in 500..599 -> Triple(
                PlaybackFailure.Category.NETWORK,
                PlaybackFailure.Code.HTTP_SERVER_ERROR,
                PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE,
            )
            else -> Triple(
                PlaybackFailure.Category.SOURCE,
                PlaybackFailure.Code.HTTP_CLIENT_ERROR,
                PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
            )
        }
        return PlaybackFailure(
            category = category,
            code = code,
            phase = phase,
            component = PlaybackFailure.Component.TRANSPORT,
            retryAdvice = advice,
            httpStatus = status,
        )
    }

    private fun classifyNetwork(
        kind: FailureSignal.NetworkKind,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure {
        val (code, advice) = when (kind) {
            FailureSignal.NetworkKind.UNAVAILABLE ->
                PlaybackFailure.Code.NETWORK_UNAVAILABLE to
                    PlaybackFailure.RetryAdvice.WAIT_FOR_NETWORK
            FailureSignal.NetworkKind.DNS ->
                PlaybackFailure.Code.DNS_LOOKUP_FAILED to
                    PlaybackFailure.RetryAdvice.WAIT_FOR_NETWORK
            FailureSignal.NetworkKind.CONNECT ->
                PlaybackFailure.Code.CONNECTION_FAILED to
                    PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE
            FailureSignal.NetworkKind.RESET ->
                PlaybackFailure.Code.CONNECTION_RESET to
                    PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE
            FailureSignal.NetworkKind.READ_TIMEOUT ->
                PlaybackFailure.Code.READ_TIMEOUT to
                    PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE
            FailureSignal.NetworkKind.END_OF_STREAM ->
                PlaybackFailure.Code.END_OF_STREAM to
                    PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE
            FailureSignal.NetworkKind.TLS ->
                PlaybackFailure.Code.TLS_FAILED to
                    PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE
        }
        return PlaybackFailure(
            category = PlaybackFailure.Category.NETWORK,
            code = code,
            phase = phase,
            component = PlaybackFailure.Component.TRANSPORT,
            retryAdvice = advice,
        )
    }

    private fun classifySource(
        kind: FailureSignal.SourceKind,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = when (kind) {
        FailureSignal.SourceKind.MALFORMED -> PlaybackFailure(
            category = PlaybackFailure.Category.FORMAT,
            code = PlaybackFailure.Code.SOURCE_MALFORMED,
            phase = phase,
            component = PlaybackFailure.Component.CONTAINER,
            retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_TRANSPORT,
        )
        FailureSignal.SourceKind.UNSUPPORTED_CONTAINER -> PlaybackFailure(
            category = PlaybackFailure.Category.FORMAT,
            code = PlaybackFailure.Code.SOURCE_UNSUPPORTED,
            phase = phase,
            component = PlaybackFailure.Component.CONTAINER,
            retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_ENGINE,
        )
        FailureSignal.SourceKind.UNSUPPORTED_CODEC -> PlaybackFailure(
            category = PlaybackFailure.Category.FORMAT,
            code = PlaybackFailure.Code.CODEC_UNSUPPORTED,
            phase = phase,
            component = PlaybackFailure.Component.UNKNOWN,
            retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_DECODER,
        )
    }

    private fun classifyDecoder(
        signal: FailureSignal.Decoder,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure {
        val code = when (signal.kind) {
            FailureSignal.DecoderKind.INIT -> PlaybackFailure.Code.DECODER_INIT_FAILED
            FailureSignal.DecoderKind.RUNTIME -> PlaybackFailure.Code.DECODER_RUNTIME_FAILED
            FailureSignal.DecoderKind.RESOURCES_RECLAIMED ->
                PlaybackFailure.Code.DECODER_RESOURCES_RECLAIMED
        }
        return PlaybackFailure(
            category = PlaybackFailure.Category.DECODER,
            code = code,
            phase = phase,
            component = signal.component,
            retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_DECODER,
        )
    }

    private fun classifyOutput(
        component: PlaybackFailure.Component,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = PlaybackFailure(
        category = PlaybackFailure.Category.OUTPUT,
        code = if (component == PlaybackFailure.Component.AUDIO) {
            PlaybackFailure.Code.AUDIO_SINK_FAILED
        } else {
            PlaybackFailure.Code.VIDEO_OUTPUT_FAILED
        },
        phase = phase,
        component = component,
        retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_ENGINE,
    )

    private fun classifyAudioStall(
        evidence: AudioFailureEvidence,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = PlaybackFailure(
        category = PlaybackFailure.Category.OUTPUT,
        code = PlaybackFailure.Code.AUDIO_STALL,
        phase = phase,
        component = PlaybackFailure.Component.AUDIO,
        retryAdvice = PlaybackFailure.RetryAdvice.TRY_ALTERNATE_ENGINE,
        audioEvidence = evidence,
    )

    private fun classifyDrm(
        kind: FailureSignal.DrmKind,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = PlaybackFailure(
        category = PlaybackFailure.Category.DRM,
        code = when (kind) {
            FailureSignal.DrmKind.PROVISIONING -> PlaybackFailure.Code.DRM_PROVISIONING_FAILED
            FailureSignal.DrmKind.LICENSE -> PlaybackFailure.Code.DRM_LICENSE_FAILED
            FailureSignal.DrmKind.CONTENT_RESTRICTED -> PlaybackFailure.Code.DRM_CONTENT_RESTRICTED
        },
        phase = phase,
        component = PlaybackFailure.Component.DRM,
        retryAdvice = if (kind == FailureSignal.DrmKind.CONTENT_RESTRICTED) {
            PlaybackFailure.RetryAdvice.DO_NOT_RETRY
        } else {
            PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE
        },
    )

    private fun classifyTimeout(
        kind: FailureSignal.TimeoutKind,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = PlaybackFailure(
        category = PlaybackFailure.Category.TIMEOUT,
        code = when (kind) {
            FailureSignal.TimeoutKind.STARTUP -> PlaybackFailure.Code.STARTUP_TIMEOUT
            FailureSignal.TimeoutKind.STALL -> PlaybackFailure.Code.PLAYBACK_STALL
            FailureSignal.TimeoutKind.SEEK -> PlaybackFailure.Code.SEEK_TIMEOUT
        },
        phase = phase,
        component = PlaybackFailure.Component.PLAYER,
        retryAdvice = PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE,
    )

    private fun classifyResource(
        kind: FailureSignal.ResourceKind,
        phase: PlaybackFailure.Phase,
    ): PlaybackFailure = PlaybackFailure(
        category = PlaybackFailure.Category.RESOURCE,
        code = if (kind == FailureSignal.ResourceKind.OUT_OF_MEMORY) {
            PlaybackFailure.Code.OUT_OF_MEMORY
        } else {
            PlaybackFailure.Code.RESOURCE_EXHAUSTED
        },
        phase = phase,
        component = PlaybackFailure.Component.PLAYER,
        retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY,
    )

    private fun unknown(phase: PlaybackFailure.Phase) = PlaybackFailure(
        category = PlaybackFailure.Category.UNKNOWN,
        code = PlaybackFailure.Code.UNKNOWN,
        phase = phase,
        component = PlaybackFailure.Component.UNKNOWN,
        retryAdvice = PlaybackFailure.RetryAdvice.UNKNOWN,
    )

    private const val MAX_CAUSE_DEPTH = 8
}
