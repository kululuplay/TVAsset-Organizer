package com.iptv.player.update

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class UpdateHttpResponse(val code: Int, val body: String?) {
    val isSuccessful: Boolean get() = code in 200..299
}

/** Cancelling a focus-driven Settings check must also close its network request. */
internal suspend fun Call.awaitUpdateResponse(): UpdateHttpResponse =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.close()
                    return
                }
                try {
                    val result = response.use { UpdateHttpResponse(it.code, it.body?.string()) }
                    if (continuation.isActive) continuation.resume(result)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
