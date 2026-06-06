/*
 * WeatherProvider.kt
 * Fetches the current weather for the dashboard pill without any API key or
 * location permission: it geolocates by public IP (ipapi.co) and then reads the
 * current conditions from Open-Meteo. Both endpoints are keyless HTTPS. The
 * result is cached for the session (30 min) so we don't hit the network on every
 * resume. Any failure returns null and the UI falls back to "weather unavailable".
 */
package com.iptv.player.util

import com.iptv.player.R
import com.iptv.player.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

object WeatherProvider {

    data class Weather(
        val tempC: Int,
        val conditionRes: Int,
        val iconRes: Int
    )

    private const val TTL_MS = 30 * 60 * 1000L
    private const val GEO_URL = "https://ipapi.co/json/"
    private const val WEATHER_URL =
        "https://api.open-meteo.com/v1/forecast?current_weather=true&latitude=%s&longitude=%s"

    @Volatile private var cached: Weather? = null
    @Volatile private var cachedAt = 0L

    suspend fun fetch(): Weather? = withContext(Dispatchers.IO) {
        cached?.let {
            if (System.currentTimeMillis() - cachedAt < TTL_MS) return@withContext it
        }
        val result = runCatching { load() }.getOrNull()
        if (result != null) {
            cached = result
            cachedAt = System.currentTimeMillis()
        }
        result
    }

    private fun load(): Weather? {
        val client = ServiceLocator.httpClient

        val geo = client.newCall(Request.Builder().url(GEO_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty())
        }
        val lat = geo.optDouble("latitude", Double.NaN)
        val lon = geo.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return null

        val url = String.format(WEATHER_URL, lat.toString(), lon.toString())
        val weather = client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            JSONObject(resp.body?.string().orEmpty())
        }
        val current = weather.optJSONObject("current_weather") ?: return null
        if (!current.has("temperature")) return null
        val temp = Math.round(current.optDouble("temperature", 0.0)).toInt()
        val code = current.optInt("weathercode", 0)
        return Weather(temp, conditionFor(code), iconFor(code))
    }

    private fun conditionFor(code: Int): Int = when (code) {
        0, 1 -> R.string.weather_clear
        2 -> R.string.weather_partly
        3 -> R.string.weather_cloudy
        45, 48 -> R.string.weather_fog
        71, 73, 75, 77, 85, 86 -> R.string.weather_snow
        95, 96, 99 -> R.string.weather_storm
        else -> R.string.weather_rain
    }

    private fun iconFor(code: Int): Int = when (code) {
        0, 1 -> R.drawable.ic_weather_clear
        2, 3, 45, 48 -> R.drawable.ic_weather_clouds
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
        else -> R.drawable.ic_weather_rain
    }
}
