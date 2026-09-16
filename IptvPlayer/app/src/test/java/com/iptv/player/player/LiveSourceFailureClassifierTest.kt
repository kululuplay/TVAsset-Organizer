package com.iptv.player.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.iptv.player.playback.core.PlaybackFailure
import java.net.UnknownHostException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(markerClass = [UnstableApi::class])
class LiveSourceFailureClassifierTest {
    private fun failure(code: Int, cause: Throwable? = null) =
        LiveSourceFailureClassifier.classify(PlaybackException("private provider text", cause, code))

    @Test fun `HTTP response codes survive exception wrapping without body or URL`() {
        listOf(401, 403, 404, 429, 503).forEach { status ->
            val cause = HttpDataSource.InvalidResponseCodeException(status, "private body", null, emptyMap(), mock(DataSpec::class.java), byteArrayOf())
            val result = failure(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, cause)
            assertEquals(status, result.httpStatus)
            assertFalse(result.toString().contains("private"))
        }
    }

    @Test fun `DNS and timeout stay distinct from unsupported format`() {
        assertEquals(PlaybackFailure.Code.DNS_LOOKUP_FAILED,
            failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, UnknownHostException()).code)
        assertEquals(PlaybackFailure.Code.READ_TIMEOUT,
            failure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).code)
        assertEquals(PlaybackFailure.Code.SOURCE_UNSUPPORTED,
            failure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).code)
    }

    @Test fun `disguised HLS rejection is terminal format failure not a network outage`() {
        val result = failure(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, MediaTransportPolicy.UnsupportedTransportException())
        assertEquals(PlaybackFailure.Code.SOURCE_UNSUPPORTED, result.code)
        assertEquals(LiveSourceRecoveryPolicy.Action.STOP, LiveSourceRecoveryPolicy.action(result))
    }

    @Test fun `unidentified native failures do not invent a server or authorization cause`() {
        assertEquals(PlaybackFailure.Category.UNKNOWN, failure(PlaybackException.ERROR_CODE_UNSPECIFIED).category)
    }
}
