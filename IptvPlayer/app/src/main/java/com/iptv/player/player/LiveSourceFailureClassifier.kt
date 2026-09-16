package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.iptv.player.playback.core.FailureSignal
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackFailureClassifier

@OptIn(markerClass = [UnstableApi::class])
internal object LiveSourceFailureClassifier {
    fun classify(error: PlaybackException): PlaybackFailure {
        val phase = PlaybackFailure.Phase.OPEN_SOURCE
        val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode?.takeIf { it in 400..599 }?.let {
                return PlaybackFailureClassifier.classify(FailureSignal.Http(it), phase)
            }
        if (causes.any { it is MediaTransportPolicy.UnsupportedTransportException }) {
            return PlaybackFailureClassifier.classify(FailureSignal.Source(FailureSignal.SourceKind.UNSUPPORTED_CONTAINER), phase)
                .copy(retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY)
        }
        val signal = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> FailureSignal.Network(FailureSignal.NetworkKind.READ_TIMEOUT)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> FailureSignal.Source(FailureSignal.SourceKind.MALFORMED)
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> FailureSignal.Source(FailureSignal.SourceKind.UNSUPPORTED_CONTAINER)
            else -> null
        }
        if (signal != null) return PlaybackFailureClassifier.classify(signal, phase)
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            return PlaybackFailureClassifier.classify(FailureSignal.Source(FailureSignal.SourceKind.UNSUPPORTED_CONTAINER), phase)
                .copy(component = PlaybackFailure.Component.TRANSPORT, retryAdvice = PlaybackFailure.RetryAdvice.DO_NOT_RETRY)
        }
        val failure = PlaybackFailureClassifier.classifyThrowable(error, phase)
        if (failure.category != PlaybackFailure.Category.UNKNOWN) return failure
        return if (error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) {
            PlaybackFailureClassifier.classify(FailureSignal.Network(FailureSignal.NetworkKind.CONNECT), phase)
        } else failure
    }
}
