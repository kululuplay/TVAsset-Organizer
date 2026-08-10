package com.iptv.player.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpAppErrorPolicyTest {

    @Test
    fun `maps authentication responses without hiding access denial`() {
        assertEquals(AppError.BAD_CREDENTIALS, HttpAppErrorPolicy.fromStatus(401))
        assertEquals(AppError.ACCESS_DENIED, HttpAppErrorPolicy.fromStatus(403))
    }

    @Test
    fun `maps retryable provider responses to actionable errors`() {
        assertEquals(AppError.REQUEST_TIMEOUT, HttpAppErrorPolicy.fromStatus(408))
        assertEquals(AppError.TOO_MANY_REQUESTS, HttpAppErrorPolicy.fromStatus(429))
        assertEquals(AppError.SERVER_UNAVAILABLE, HttpAppErrorPolicy.fromStatus(503))
    }

    @Test
    fun `maps missing endpoint and unknown client response separately`() {
        assertEquals(AppError.SERVICE_NOT_FOUND, HttpAppErrorPolicy.fromStatus(404))
        assertEquals(AppError.CANNOT_CONNECT, HttpAppErrorPolicy.fromStatus(418))
    }
}
