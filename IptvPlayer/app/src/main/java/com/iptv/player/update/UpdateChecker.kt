/*
 * UpdateChecker.kt
 * Checks the project's GitHub Releases for a newer build and, when found, exposes
 * the APK asset download URL. Network + JSON parsing run on Dispatchers.IO.
 */
package com.iptv.player.update

import android.content.Context
import com.google.gson.JsonParser
import com.iptv.player.BuildConfig
import com.iptv.player.util.DeviceId
import com.iptv.player.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    /** Canonical asset size reported by GitHub, or -1 when unavailable. */
    val apkSize: Long,
    /** Canonical lowercase SHA-256 reported by GitHub, or null when unavailable. */
    val apkSha256: String?,
    val releaseUrl: String,
    val notes: String?
)

sealed class UpdateResult {
    data class Available(val info: UpdateInfo) : UpdateResult()
    data class Deferred(val targetVersion: String) : UpdateResult()
    object UpToDate : UpdateResult()
    object Failed : UpdateResult()
}

internal object UpdateRolloutGatePolicy {
    sealed interface Outcome {
        object Allow : Outcome
        data class Hold(val stableVersion: String?) : Outcome
    }

    fun parse(httpCode: Int, body: String?): Outcome {
        if (httpCode == 404) return Outcome.Allow
        if (httpCode !in 200..299) return Outcome.Hold(null)
        return runCatching {
            val root = JsonParser.parseString(body.orEmpty()).asJsonObject
            if (root.get("decision")?.asString == "allow") {
                Outcome.Allow
            } else {
                Outcome.Hold(
                    root.get("stableVersion")
                        ?.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(Outcome.Hold(null))
    }
}

class UpdateChecker(
    private val httpClient: OkHttpClient,
    context: Context? = null,
) {
    private val appContext = context?.applicationContext

    private data class ReleaseRecord(
        val versionName: String,
        val info: UpdateInfo,
    )

    private sealed interface RolloutGate {
        object Allow : RolloutGate
        data class Hold(val stableVersion: String?) : RolloutGate
    }

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
                val records = (0 until releases.length())
                    .asSequence()
                    .map { releases.getJSONObject(it) }
                    .filterNot { it.optBoolean("draft", false) }
                    .map { it to it.optString("tag_name").ifBlank { it.optString("name") } }
                    .filter { (_, tag) -> tag.isNotBlank() }
                    .map { (json, tag) -> releaseRecord(json, tag) }
                    .toList()
                val candidate = records.firstOrNull()
                    ?: return@withContext UpdateResult.Failed
                if (!isNewer(candidate.versionName, currentVersionName)) {
                    return@withContext UpdateResult.UpToDate
                }
                when (val gate = rolloutGate(candidate.versionName, currentVersionName)) {
                    RolloutGate.Allow -> UpdateResult.Available(candidate.info)
                    is RolloutGate.Hold -> {
                        val stable = gate.stableVersion?.let { version ->
                            records.firstOrNull { it.versionName == version }
                        }
                        if (stable != null && isNewer(stable.versionName, currentVersionName)) {
                            UpdateResult.Available(stable.info)
                        } else {
                            UpdateResult.Deferred(candidate.versionName)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // Never swallow cancellation: let the caller's coroutine unwind cleanly.
            throw e
        } catch (e: Exception) {
            UpdateResult.Failed
        }
    }

    private fun releaseRecord(json: JSONObject, tag: String): ReleaseRecord {
        val apkAsset = json.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        }
        val version = tag.removePrefix("v").removePrefix("V")
        return ReleaseRecord(
            versionName = version,
            info = UpdateInfo(
                versionName = version,
                apkUrl = apkAsset?.optString("browser_download_url")
                    ?.takeIf { it.isNotBlank() },
                apkSize = apkAsset?.optLong("size", -1L)?.takeIf { it > 0L } ?: -1L,
                apkSha256 = ApkIntegrityPolicy.normalizeSha256(
                    apkAsset?.optString("digest"),
                ),
                releaseUrl = json.optString("html_url"),
                notes = ReleaseNotesPolicy.customerFacing(json.optString("body")),
            ),
        )
    }

    /** Fail closed when the managed rollout service cannot be reached. */
    private suspend fun rolloutGate(
        candidateVersion: String,
        currentVersionName: String,
    ): RolloutGate {
        val context = appContext ?: return RolloutGate.Allow
        if (!Telemetry.isEnabled) return RolloutGate.Allow
        return runCatching {
            val json = JSONObject().apply {
                put("deviceId", DeviceId.get(context))
                put("candidateVersion", candidateVersion)
                put("currentVersion", currentVersionName)
                put("currentVersionCode", BuildConfig.VERSION_CODE)
            }
            val request = Request.Builder()
                .url(Telemetry.UPDATE_POLICY_ENDPOINT)
                .header("X-Kululu-Key", Telemetry.INGEST_KEY)
                .post(
                    json.toString().toRequestBody(
                        "application/json; charset=utf-8".toMediaType(),
                    ),
                )
                .build()
            httpClient.newCall(request).execute().use { response ->
                when (
                    val outcome = UpdateRolloutGatePolicy.parse(
                        response.code,
                        response.body?.string(),
                    )
                ) {
                    UpdateRolloutGatePolicy.Outcome.Allow -> RolloutGate.Allow
                    is UpdateRolloutGatePolicy.Outcome.Hold ->
                        RolloutGate.Hold(outcome.stableVersion)
                }
            }
        }.getOrDefault(RolloutGate.Hold(null))
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
