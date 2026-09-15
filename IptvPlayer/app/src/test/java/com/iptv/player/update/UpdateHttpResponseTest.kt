package com.iptv.player.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHttpResponseTest {
    @Test
    fun `leaving settings cancels pending request and late response is closed`() = runBlocking {
        val call = PendingCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitUpdateResponse() }
        pending.cancelAndJoin()
        assertTrue(call.isCanceled())
        val body = ClosingBody("{}")
        call.respond(200, body)
        assertTrue(body.closed)
        assertTrue(pending.isCancelled)
    }

    @Test
    fun `successful response carries payload and closes network body`() = runBlocking {
        val call = PendingCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitUpdateResponse() }
        val body = ClosingBody("{\"schema\":1}")
        call.respond(200, body)
        val result = pending.await()
        assertTrue(result.isSuccessful)
        assertEquals("{\"schema\":1}", result.body)
        assertTrue(body.closed)
    }

    @Test
    fun `server failure stays failure rather than up to date`() = runBlocking {
        val call = PendingCall()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { call.awaitUpdateResponse() }
        val body = ClosingBody("Unavailable")
        call.respond(503, body)
        val result = pending.await()
        assertFalse(result.isSuccessful)
        assertEquals(503, result.code)
        assertTrue(body.closed)
    }

    private class PendingCall : Call {
        private lateinit var callback: Callback
        private var cancelled = false
        private val request = Request.Builder().url("https://example.invalid/update").build()
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun execute(): Response = error("No blocking request expected")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun clone(): Call = PendingCall()
        override fun timeout() = Timeout.NONE
        fun respond(code: Int, body: ResponseBody) {
            callback.onResponse(this, Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body).build())
        }
    }

    private class ClosingBody(content: String) : ResponseBody() {
        var closed = false
        private val data = object : ForwardingSource(Buffer().writeUtf8(content)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()
        override fun contentType() = "application/json".toMediaType()
        override fun contentLength() = -1L
        override fun source(): BufferedSource = data
    }
}
