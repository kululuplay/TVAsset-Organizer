/*
 * UpdateChecker.kt
 * Checks the project's GitHub Releases for a newer build and, when found, exposes
 * the APK asset download URL. Network + JSON parsing run on Dispatchers.IO.
 */
package com.iptv.player.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** GitHub repository that publishes the APK releases. */
object UpdateConfig {
    const val OWNER = "kululuplay"
    const val REPO = "TVAsset-Organizer"

    /**
     * List endpoint (newest first) instead of `/releases/latest`: the latter only
     * returns full, non-prerelease releases and 404s when the newest publish is a
     * prerelease or when no full release exists yet. Listing lets us pick the most
     * recent published (non-draft) release, including prereleases.
     */
    const val RELEASES_API =
        "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=10"
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
                .url(UpdateConfig.RELEASES_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext UpdateResult.Failed
                val body = resp.body?.string() ?: return@withContext UpdateResult.Failed
                val releases = JSONArray(body)

                // Newest published (non-draft) release with a usable version tag.
                // The list is ordered newest first; skip drafts and malformed
                // entries (blank tag/name) instead of failing on the first one.
                val candidate = (0 until releases.length())
                    .asSequence()
                    .map { releases.getJSONObject(it) }
                    .filterNot { it.optBoolean("draft", false) }
                    .map { it to it.optString("tag_name").ifBlank { it.optString("name") } }
                    .firstOrNull { (_, tag) -> tag.isNotBlank() }
                    ?: return@withContext UpdateResult.Failed

                val json = candidate.first
                val tag = candidate.second

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
                    // The CI publishes a generic "Automated build from commit
                    // <sha>." body; that is noise to end users, so it is hidden
                    // from the update popup. Only genuine, human-written notes are
                    // shown. (Filtering here instead of in the workflow keeps the
                    // .github/workflows file untouched — pushing a workflow change
                    // requires an OAuth token with the `workflow` scope.)
                    notes = json.optString("body")
                        .takeIf { it.isNotBlank() }
                        ?.takeUnless {
                            it.trim().startsWith("Automated build from commit", ignoreCase = true)
                        }
                )

                if (isNewer(info.versionName, currentVersionName)) {
                    UpdateResult.Available(info)
                } else {
                    UpdateResult.UpToDate
                }
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: let the caller's coroutine unwind cleanly.
            throw e
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
