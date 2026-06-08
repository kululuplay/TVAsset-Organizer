/*
 * PublicIpProvider.kt
 * Fetches the device's public (WAN) IPv4 address from keyless plain-text
 * endpoints. Shown in Settings > General Info so support can identify the
 * customer's home connection during a technical intervention. Tries several
 * providers for resilience and validates that the response is a real IPv4, so
 * an IPv6 reply or an HTML error page can never leak into the UI.
 */
package com.iptv.player.util

import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

object PublicIpProvider {

    private val ENDPOINTS = listOf(
        "https://api.ipify.org",
        "https://ipv4.icanhazip.com",
        "https://checkip.amazonaws.com"
    )

    private val IPV4 = Regex(
        "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"
    )

    /** Returns the public IPv4 (e.g. "85.103.12.7") or null if none could be read. */
    suspend fun fetchIpv4(): String? = withContext(Dispatchers.IO) {
        val client = ServiceLocator.httpClient
        for (url in ENDPOINTS) {
            val ip = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string()?.trim() else null
                }
            }.getOrNull()
            if (ip != null && IPV4.matches(ip)) return@withContext ip
        }
        null
    }
}
