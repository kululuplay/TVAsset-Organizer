package com.iptv.player.util

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLHandshakeException

class RetryInterceptorTest {
    private fun fixture(method: String = "GET"): Pair<Interceptor.Chain, Request> {
        val chain = mock(Interceptor.Chain::class.java)
        val request = Request.Builder().url("https://example.test/player_api.php")
            .method(method, if (method == "POST") "{}".toRequestBody() else null).build()
        `when`(chain.request()).thenReturn(request)
        `when`(chain.call()).thenReturn(mock(Call::class.java))
        return chain to request
    }
    private fun response(request: Request, code: Int, retry: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body("{}".toResponseBody()).apply { retry?.let { header("Retry-After", it) } }.build()

    @Test fun `read retries stop at budget and respect retry after`() {
        val (chain, request) = fixture()
        val delays = mutableListOf<Long>()
        `when`(chain.proceed(request)).thenAnswer { response(request, 429, "2") }
        RetryInterceptor(sleep = { delays += it }).intercept(chain).close()
        verify(chain, times(3)).proceed(request)
        assertEquals(listOf(2000L, 2000L), delays)
    }
    @Test fun `long server wait returns response without early retry`() {
        for (delay in listOf("120", "Thu, 01 Jan 1970 00:02:00 GMT")) {
            val (chain, request) = fixture()
            val expected = response(request, 503, delay)
            `when`(chain.proceed(request)).thenReturn(expected)
            assertSame(expected, RetryInterceptor(sleep = { fail("early retry") }, now = { 0L }).intercept(chain))
            verify(chain, times(1)).proceed(request)
            expected.close()
        }
    }
    @Test fun `auth missing range and successful partial responses are not retried`() {
        for (status in listOf(200, 206, 400, 401, 403, 404, 410, 416)) {
            val (chain, request) = fixture()
            `when`(chain.proceed(request)).thenReturn(response(request, status))
            assertEquals(status, RetryInterceptor(sleep = { fail("unexpected retry") }).intercept(chain).use { it.code })
            verify(chain, times(1)).proceed(request)
        }
    }
    @Test fun `post and TLS errors never replay`() {
        val (post, request) = fixture("POST")
        `when`(post.proceed(request)).thenReturn(response(request, 503))
        RetryInterceptor(sleep = { fail("POST replay") }).intercept(post).close()
        verify(post, times(1)).proceed(request)
        val (get, read) = fixture()
        `when`(get.proceed(read)).thenThrow(SSLHandshakeException("fixture"))
        assertThrows(SSLHandshakeException::class.java) { RetryInterceptor(sleep = { fail("TLS retry") }).intercept(get) }
        verify(get, times(1)).proceed(read)
    }
    @Test fun `cancellation during backoff prevents next request`() {
        val (chain, request) = fixture()
        `when`(chain.proceed(request)).thenThrow(IOException("fixture"))
        assertThrows(InterruptedIOException::class.java) {
            RetryInterceptor(sleep = { `when`(chain.call().isCanceled()).thenReturn(true) }).intercept(chain)
        }
        verify(chain, times(1)).proceed(request)
    }
    @Test fun `interrupted wait exits immediately and restores interrupt`() {
        val (chain, request) = fixture()
        `when`(chain.proceed(request)).thenReturn(response(request, 503))
        try {
            assertThrows(InterruptedIOException::class.java) {
                RetryInterceptor(sleep = { throw InterruptedException() }).intercept(chain)
            }
            assertTrue(Thread.currentThread().isInterrupted)
            verify(chain, times(1)).proceed(request)
        } finally { Thread.interrupted() }
    }
}
