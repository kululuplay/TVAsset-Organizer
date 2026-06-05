/*
 * UpdateChecker.kt
 * Checks the project's GitHub Releases for a newer build and, when found, exposes
 * the APK asset download URL. Network + JSON parsing run on Dispatchers.IO.
 */
package com.iptv.player.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** GitHub repository that publishes the APK releases. */
object UpdateConfig {
    const val OWNER = "kululuplay"
    const val REPO = "TVAsset-Organizer"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
}

data class UpdateInfo(
    val versionName: String,
    /** Direct .apk asset URL, or null when the release has no APK attached. */
    val apkUrl: String?,
    val releaseUrl: String,
    val notes: String?
)

sealed class UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult()
    object UpToDate : UpdateResult()
    object Failed : UpdateResult()
}

class UpdateChecker(private val httpClient: OkHttpClient) {

    suspend fun check(currentVersionName: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(UpdateConfig.LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext UpdateResult.Failed
                val body = resp.body?.string() ?: return@withContext UpdateResult.Failed
                val json = JSONObject(body)

                val tag = json.optString("tag_name").ifBlank { json.optString("name") }
                if (tag.isBlank()) return@withContext UpdateResult.Failed

                val apkUrl = json.optJSONArray("assets")?.let { assets ->
                    (0 until assets.length())
                        .map { assets.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                        ?.optString("browser_download_url")
                }

                val info = UpdateInfo(
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    apkUrl = apkUrl?.takeIf { it.isNotBlank() },
                    releaseUrl = json.optString("html_url"),
                    notes = json.optString("body").takeIf { it.isNotBlank() }
                )

                if (isNewer(info.versionName, currentVersionName)) {
                    UpdateResult.Available(info)
                } else {
                    UpdateResult.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateResult.Failed
        }
    }

    /** True when [remote] is a strictly higher version than [current]. */
    private fun isNewer(remote: String, current: String): Boolean {
        val r = parts(remote)
        val c = parts(current)
        val max = maxOf(r.size, c.size)
        for (i in 0 until max) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.split('.', '-', '_', ' ')
            .mapNotNull { chunk -> chunk.filter { it.isDigit() }.toIntOrNull() }
}
