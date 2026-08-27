/*
 * UpdateChecker.kt
 * Checks the project's GitHub Releases for a newer build and, when found, exposes
 * the APK asset download URL. Network + JSON parsing run on Dispatchers.IO.
 */
package com.iptv.player.update

import android.content.Context
import com.google.gson.JsonParser
import com.iptv.player.util.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

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

    /** Mutable, public control plane; no application backend is required. */
    const val ROLLOUT_POLICY_URL =
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/update-rollout.json"
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

    fun decide(
        httpCode: Int,
        body: String?,
        candidateVersion: String,
        deviceId: String,
    ): Outcome {
        if (httpCode !in 200..299 || body.isNullOrBlank() || body.length > MAX_POLICY_BYTES) {
            return Outcome.Hold(null)
        }
        return runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            require(root.keySet().all(ALLOWED_KEYS::contains))
            require(root.get("schema")?.asString?.toIntOrNull() == POLICY_SCHEMA)
            val targetVersion = requiredString(root, "targetVersion", 32)
            require(VERSION.matches(targetVersion))
            require(VERSION.matches(candidateVersion))
            val stableVersion = optionalString(root, "stableVersion", 32)
            require(stableVersion == null || VERSION.matches(stableVersion))
            require(stableVersion == null || compareVersions(stableVersion, targetVersion) < 0)
            val rolloutPercent = root.get("rolloutPercent")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString
                ?.toIntOrNull()
            require(rolloutPercent != null && rolloutPercent in 0..100)
            val paused = requiredBoolean(root, "paused")
            val emergency = requiredBoolean(root, "emergency")
            val salt = requiredString(root, "salt", 64)
            require(deviceId.isNotBlank())

            when (compareVersions(candidateVersion, targetVersion)) {
                // Policy can be safely pre-armed before its release exists.
                -1 -> Outcome.Allow
                // A newer release without a matching policy is held fail-closed.
                1 -> Outcome.Hold(stableVersion)
                else -> {
                    val eligible = emergency ||
                        (!paused && rolloutBucket(deviceId, salt) < rolloutPercent * 100)
                    if (eligible) Outcome.Allow else Outcome.Hold(stableVersion)
                }
            }
        }.getOrDefault(Outcome.Hold(null))
    }

    private fun requiredString(
        root: com.google.gson.JsonObject,
        key: String,
        maxLength: Int,
    ): String {
        val value = root.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.trim().also { require(it.isNotEmpty() && it.length <= maxLength) }
    }

    private fun optionalString(
        root: com.google.gson.JsonObject,
        key: String,
        maxLength: Int,
    ): String? {
        val value = root.get(key) ?: return null
        if (value.isJsonNull) return null
        require(value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString.trim().takeIf { it.isNotEmpty() }
            ?.also { require(it.length <= maxLength) }
    }

    private fun requiredBoolean(root: com.google.gson.JsonObject, key: String): Boolean {
        val value = root.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }

    private fun rolloutBucket(deviceId: String, salt: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$salt:$deviceId".toByteArray(Charsets.UTF_8))
        var value = 0L
        repeat(4) { index ->
            value = (value shl 8) or (digest[index].toInt() and 0xff).toLong()
        }
        return (value % 10_000L).toInt()
    }

    private fun compareVersions(left: String, right: String): Int {
        val l = versionParts(left)
        val r = versionParts(right)
        repeat(maxOf(l.size, r.size)) { index ->
            val delta = l.getOrElse(index) { 0 }.compareTo(r.getOrElse(index) { 0 })
            if (delta != 0) return delta
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> =
        version.split('.').map { it.toIntOrNull() ?: -1 }

    private const val POLICY_SCHEMA = 1
    private const val MAX_POLICY_BYTES = 4_096
    private val VERSION = Regex("^\\d+(?:\\.\\d+){1,3}$")
    private val ALLOWED_KEYS = setOf(
        "schema",
        "targetVersion",
        "stableVersion",
        "rolloutPercent",
        "paused",
        "emergency",
        "salt",
    )
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
                when (val gate = rolloutGate(candidate.versionName)) {
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

    /** Fail closed when the public GitHub policy cannot be verified. */
    private suspend fun rolloutGate(
        candidateVersion: String,
    ): RolloutGate {
        val context = appContext ?: return RolloutGate.Allow
        return runCatching {
            val deviceId = DeviceId.get(context)
            val cacheSlot = System.currentTimeMillis() / POLICY_CACHE_WINDOW_MS
            val request = Request.Builder()
                .url(
                    "${UpdateConfig.ROLLOUT_POLICY_URL}" +
                        "?candidate=$candidateVersion&slot=$cacheSlot",
                )
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()
            httpClient.newCall(request).execute().use { response ->
                when (
                    val outcome = UpdateRolloutGatePolicy.decide(
                        response.code,
                        response.body?.string(),
                        candidateVersion,
                        deviceId,
                    )
                ) {
                    UpdateRolloutGatePolicy.Outcome.Allow -> RolloutGate.Allow
                    is UpdateRolloutGatePolicy.Outcome.Hold ->
                        RolloutGate.Hold(outcome.stableVersion)
                }
            }
        }.getOrDefault(RolloutGate.Hold(null))
    }

    private companion object {
        const val POLICY_CACHE_WINDOW_MS = 5L * 60L * 1_000L
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
