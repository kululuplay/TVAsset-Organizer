package com.iptv.player.playback.core

import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackFailureTest {

    @Test
    fun `HTTP failures have stable typed categories and retry advice`() {
        val unauthorized = PlaybackFailureClassifier.classify(
            FailureSignal.Http(401),
            PlaybackFailure.Phase.OPEN_SOURCE,
        )
        val missing = PlaybackFailureClassifier.classify(FailureSignal.Http(404))
        val server = PlaybackFailureClassifier.classify(FailureSignal.Http(503))

        assertEquals(PlaybackFailure.Category.AUTHORIZATION, unauthorized.category)
        assertEquals(PlaybackFailure.Code.HTTP_UNAUTHORIZED, unauthorized.code)
        assertEquals(PlaybackFailure.RetryAdvice.DO_NOT_RETRY, unauthorized.retryAdvice)
        assertEquals(401, unauthorized.httpStatus)
        assertEquals(PlaybackFailure.RetryAdvice.TRY_ALTERNATE_TRANSPORT, missing.retryAdvice)
        assertEquals(PlaybackFailure.Category.NETWORK, server.category)
        assertEquals(PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE, server.retryAdvice)
    }

    @Test
    fun `decoder failures retain media component without native exception text`() {
        val failure = PlaybackFailureClassifier.classify(
            FailureSignal.Decoder(
                kind = FailureSignal.DecoderKind.RUNTIME,
                component = PlaybackFailure.Component.VIDEO,
            ),
            phase = PlaybackFailure.Phase.PLAYBACK,
        )

        assertEquals(PlaybackFailure.Category.DECODER, failure.category)
        assertEquals(PlaybackFailure.Code.DECODER_RUNTIME_FAILED, failure.code)
        assertEquals(PlaybackFailure.Component.VIDEO, failure.component)
        assertEquals(PlaybackFailure.RetryAdvice.TRY_ALTERNATE_DECODER, failure.retryAdvice)
        assertFalse(failure.toString().contains("message="))
    }

    @Test
    fun `throwable classifier walks causes but never copies a credentialed message`() {
        val credentialedUrl = "https://user:password@example.test/stream/item.ts"
        val wrapped = IOException(credentialedUrl, SocketTimeoutException(credentialedUrl))

        val failure = PlaybackFailureClassifier.classifyThrowable(
            wrapped,
            PlaybackFailure.Phase.CONNECT,
        )

        assertEquals(PlaybackFailure.Code.READ_TIMEOUT, failure.code)
        assertEquals(PlaybackFailure.Phase.CONNECT, failure.phase)
        assertFalse(failure.toString().contains("password"))
        assertFalse(failure.toString().contains("example.test"))
    }

    @Test
    fun `TLS is never reclassified as a cleartext transport request`() {
        val failure = PlaybackFailureClassifier.classifyThrowable(
            SSLHandshakeException("certificate mismatch for https://secret.example"),
        )

        assertEquals(PlaybackFailure.Code.TLS_FAILED, failure.code)
        assertEquals(PlaybackFailure.RetryAdvice.RETRY_SAME_ROUTE, failure.retryAdvice)
        assertFalse(failure.toString().contains("secret.example"))
    }

    @Test
    fun `non-error HTTP responses cannot enter the failure model`() {
        assertThrows(IllegalArgumentException::class.java) {
            FailureSignal.Http(200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackFailure(
                category = PlaybackFailure.Category.SOURCE,
                code = PlaybackFailure.Code.HTTP_CLIENT_ERROR,
                httpStatus = 200,
            )
        }
    }

    @Test
    fun `audio stall retains only closed privacy-safe evidence`() {
        val evidence = AudioFailureEvidence(
            codec = AudioFailureEvidence.Codec.AC3,
            decoder = AudioFailureEvidence.Decoder.HARDWARE,
            sinkEvent = AudioFailureEvidence.SinkEvent.UNDERRUN,
            outputMode = AudioFailureEvidence.OutputMode.PCM,
        )
        val failure = PlaybackFailureClassifier.classify(
            FailureSignal.AudioStall(evidence),
            PlaybackFailure.Phase.PLAYBACK,
        )

        assertEquals(PlaybackFailure.Category.OUTPUT, failure.category)
        assertEquals(PlaybackFailure.Code.AUDIO_STALL, failure.code)
        assertEquals(PlaybackFailure.Component.AUDIO, failure.component)
        assertEquals(PlaybackFailure.RetryAdvice.TRY_ALTERNATE_ENGINE, failure.retryAdvice)
        assertEquals(evidence, failure.audioEvidence)
        assertFalse(failure.toString().contains("url", ignoreCase = true))
    }
}
