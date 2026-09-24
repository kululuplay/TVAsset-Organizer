package com.iptv.player.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.iptv.player.data.model.BufferMode
import com.iptv.player.data.model.PlayerMode
import org.json.JSONArray
import org.json.JSONObject

/** Small, fail-safe playback kill switches delivered by the heartbeat response. */
object PlaybackRemotePolicy {

    data class Snapshot(
        val policyVersion: Int = 1,
        val expiresAtEpochMs: Long = 0L,
        val disablePixelCopyValidation: Boolean = false,
        val vodConnectTimeoutMs: Int = DEFAULT_VOD_CONNECT_TIMEOUT_MS,
        val vodReadTimeoutMs: Int = DEFAULT_VOD_READ_TIMEOUT_MS,
        val allowSourceEngineFallback: Boolean = true,
    )

    /**
     * Per-device-class playback overrides resolved from the policy's
     * `deviceOverrides` rules. Every field is null when no rule targets this
     * device (or the policy expired); consumers fall back to their own default.
     */
    data class DeviceOverrides(
        val bufferMode: BufferMode? = null,
        val startEngine: PlayerMode? = null,
        val tunneling: Boolean? = null,
        val livePreviewEnabled: Boolean? = null,
        val allowSoftwareHdFallback: Boolean? = null,
        val compatibilityProfile: Boolean? = null,
        /** true = libVLC's default deinterlacer, false = off; null = device rule. */
        val vlcDeinterlace: Boolean? = null,
    )

    @Volatile private var current = Snapshot()
    @Volatile private var currentOverrides = DeviceOverrides()
    @Volatile private var cachedFacts: DeviceOverrideMatcher.DeviceFacts? = null

    fun snapshot(nowMs: Long = System.currentTimeMillis()): Snapshot =
        effective(current, nowMs)

    /**
     * Merged overrides of every rule matching this device (resolved once at
     * [init]/[apply] time, so this is a cheap field read). All-null once the
     * policy expires, mirroring [snapshot].
     */
    fun deviceOverrides(nowMs: Long = System.currentTimeMillis()): DeviceOverrides =
        if (current.expiresAtEpochMs > nowMs) currentOverrides else DeviceOverrides()

    internal fun effective(candidate: Snapshot, nowMs: Long): Snapshot =
        candidate.takeIf { it.expiresAtEpochMs > nowMs } ?: Snapshot()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        current = Snapshot(
            policyVersion = prefs.getInt(KEY_POLICY_VERSION, 1),
            expiresAtEpochMs = prefs.getLong(KEY_EXPIRES_AT, 0L),
            disablePixelCopyValidation = prefs.getBoolean(KEY_DISABLE_PIXEL_COPY, false),
            vodConnectTimeoutMs = prefs.getInt(
                KEY_VOD_CONNECT_TIMEOUT,
                DEFAULT_VOD_CONNECT_TIMEOUT_MS,
            ).coerceIn(MIN_TIMEOUT_MS, MAX_CONNECT_TIMEOUT_MS),
            vodReadTimeoutMs = prefs.getInt(
                KEY_VOD_READ_TIMEOUT,
                DEFAULT_VOD_READ_TIMEOUT_MS,
            ).coerceIn(MIN_TIMEOUT_MS, MAX_READ_TIMEOUT_MS),
            allowSourceEngineFallback = prefs.getBoolean(KEY_SOURCE_ENGINE_FALLBACK, true),
        )
        currentOverrides = resolveOverrides(context, prefs.getString(KEY_DEVICE_OVERRIDES, null))
    }

    fun apply(context: Context, json: JSONObject) {
        val next = sanitize(
            policyVersion = json.optInt("policyVersion", 1),
            expiresAtEpochMs = json.optLong("expiresAtEpochMs", 0L),
            disablePixelCopyValidation = json.optBoolean(
                "disablePixelCopyValidation",
                current.disablePixelCopyValidation,
            ),
            vodConnectTimeoutMs = json.optInt(
                "vodConnectTimeoutMs",
                current.vodConnectTimeoutMs,
            ),
            vodReadTimeoutMs = json.optInt(
                "vodReadTimeoutMs",
                current.vodReadTimeoutMs,
            ),
            allowSourceEngineFallback = json.optBoolean(
                "allowSourceEngineFallback",
                current.allowSourceEngineFallback,
            ),
        )
        // The rules array is authoritative per response: a policy without it
        // clears any previously stored overrides instead of keeping stale ones.
        val rulesJson = json.optJSONArray("deviceOverrides")
            ?.toString()
            ?.takeIf { it.length <= MAX_RULES_JSON_CHARS }
        current = next
        currentOverrides = resolveOverrides(context, rulesJson)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_POLICY_VERSION, next.policyVersion)
            .putLong(KEY_EXPIRES_AT, next.expiresAtEpochMs)
            .putBoolean(KEY_DISABLE_PIXEL_COPY, next.disablePixelCopyValidation)
            .putInt(KEY_VOD_CONNECT_TIMEOUT, next.vodConnectTimeoutMs)
            .putInt(KEY_VOD_READ_TIMEOUT, next.vodReadTimeoutMs)
            .putBoolean(KEY_SOURCE_ENGINE_FALLBACK, next.allowSourceEngineFallback)
            .putString(KEY_DEVICE_OVERRIDES, rulesJson)
            .apply()
    }

    internal fun sanitize(
        policyVersion: Int = 1,
        expiresAtEpochMs: Long = 0L,
        disablePixelCopyValidation: Boolean,
        vodConnectTimeoutMs: Int,
        vodReadTimeoutMs: Int,
        allowSourceEngineFallback: Boolean,
    ): Snapshot = Snapshot(
        policyVersion = policyVersion.coerceIn(1, 1),
        expiresAtEpochMs = expiresAtEpochMs.coerceAtLeast(0L),
        disablePixelCopyValidation = disablePixelCopyValidation,
        vodConnectTimeoutMs = vodConnectTimeoutMs.coerceIn(
            MIN_TIMEOUT_MS,
            MAX_CONNECT_TIMEOUT_MS,
        ),
        vodReadTimeoutMs = vodReadTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_READ_TIMEOUT_MS),
        allowSourceEngineFallback = allowSourceEngineFallback,
    )

    /**
     * Static facts about this device used for rule matching and reported by the
     * heartbeat so operators can author rules from what the panel shows. Build
     * fields never change at runtime, so the result is computed once.
     */
    fun deviceFacts(context: Context): DeviceOverrideMatcher.DeviceFacts {
        cachedFacts?.let { return it }
        val am = runCatching {
            context.applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        val totalMem = am?.let {
            runCatching { ActivityManager.MemoryInfo().also(it::getMemoryInfo).totalMem }
                .getOrNull()
        }
        val facts = DeviceOverrideMatcher.DeviceFacts(
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            hardware = Build.HARDWARE ?: "",
            board = Build.BOARD ?: "",
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            } else {
                null
            },
            sdk = Build.VERSION.SDK_INT,
            lowRam = am?.let { runCatching { it.isLowRamDevice }.getOrNull() },
            totalRamMb = totalMem?.takeIf { it > 0 }?.let { it / (1024L * 1024L) },
        )
        cachedFacts = facts
        return facts
    }

    private fun resolveOverrides(context: Context, rulesJson: String?): DeviceOverrides {
        if (rulesJson.isNullOrBlank()) return DeviceOverrides()
        return runCatching {
            val raw = rawRules(JSONArray(rulesJson))
            DeviceOverrideMatcher.resolve(
                DeviceOverrideMatcher.parseRules(raw),
                deviceFacts(context),
            )
        }.getOrDefault(DeviceOverrides())
    }

    /** JSON -> plain maps so the matcher stays free of org.json (unit-testable). */
    private fun rawRules(array: JSONArray): List<DeviceOverrideMatcher.RawRule> {
        val out = ArrayList<DeviceOverrideMatcher.RawRule>()
        for (i in 0 until minOf(array.length(), DeviceOverrideMatcher.MAX_RULES)) {
            val rule = array.optJSONObject(i) ?: continue
            out += DeviceOverrideMatcher.RawRule(
                match = rule.optJSONObject("match")?.let(::scalarMap).orEmpty(),
                set = rule.optJSONObject("set")?.let(::scalarMap).orEmpty(),
            )
        }
        return out
    }

    private fun scalarMap(obj: JSONObject): Map<String, Any> {
        val out = HashMap<String, Any>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value: Any = obj.opt(key) ?: continue
            if (value is String || value is Boolean || value is Number) out[key] = value
        }
        return out
    }

    private const val PREFS = "playback_remote_policy"
    private const val KEY_POLICY_VERSION = "policy_version"
    private const val KEY_EXPIRES_AT = "expires_at_epoch_ms"
    private const val KEY_DISABLE_PIXEL_COPY = "disable_pixel_copy"
    private const val KEY_VOD_CONNECT_TIMEOUT = "vod_connect_timeout_ms"
    private const val KEY_VOD_READ_TIMEOUT = "vod_read_timeout_ms"
    private const val KEY_SOURCE_ENGINE_FALLBACK = "source_engine_fallback"
    private const val KEY_DEVICE_OVERRIDES = "device_overrides_json"
    private const val MIN_TIMEOUT_MS = 5_000
    private const val MAX_CONNECT_TIMEOUT_MS = 30_000
    private const val MAX_READ_TIMEOUT_MS = 60_000
    private const val DEFAULT_VOD_CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_VOD_READ_TIMEOUT_MS = 20_000
    private const val MAX_RULES_JSON_CHARS = 32 * 1024
}

/**
 * Pure (Android-free) matching of `deviceOverrides` rules against device facts.
 *
 * Rule shape (all `match` keys optional, AND semantics; unknown keys ignored):
 * `{"match":{"manufacturer":re,"model":re,"hardware":re,"board":re,"socModel":re,
 *   "sdkMin":21,"sdkMax":27,"lowRam":true,"totalRamMaxMb":1536},
 *  "set":{"bufferMode":"HIGH","startEngine":"EXOPLAYER","tunneling":true,
 *   "livePreview":false,"allowSoftwareHdFallback":false,"compatibilityProfile":true,
 *   "vlcDeinterlace":false}}`
 * Regexes are case-insensitive and matched with `find` (unanchored). A rule with
 * an invalid or over-long regex, or with nothing valid to set, is ignored.
 */
object DeviceOverrideMatcher {

    const val MAX_RULES = 32
    const val MAX_REGEX_CHARS = 128

    data class DeviceFacts(
        val manufacturer: String,
        val model: String,
        val hardware: String,
        val board: String,
        val socModel: String?,
        val sdk: Int,
        val lowRam: Boolean?,
        val totalRamMb: Long?,
    )

    /** Untyped rule as it arrives from JSON: scalar values only. */
    data class RawRule(val match: Map<String, Any>, val set: Map<String, Any>)

    data class Rule(
        val manufacturer: Regex? = null,
        val model: Regex? = null,
        val hardware: Regex? = null,
        val board: Regex? = null,
        val socModel: Regex? = null,
        val sdkMin: Int? = null,
        val sdkMax: Int? = null,
        val lowRam: Boolean? = null,
        val totalRamMaxMb: Long? = null,
        val set: PlaybackRemotePolicy.DeviceOverrides,
    )

    private val RE_PATTERN_KEYS = listOf("manufacturer", "model", "hardware", "board", "socModel")

    fun parseRules(raw: List<RawRule>): List<Rule> =
        raw.take(MAX_RULES).mapNotNull(::parseRule)

    /** Returns null when the rule is malformed (bad regex) or sets nothing valid. */
    fun parseRule(raw: RawRule): Rule? {
        val patterns = HashMap<String, Regex>()
        for (key in RE_PATTERN_KEYS) {
            val value = raw.match[key] ?: continue
            val pattern = value as? String ?: return null
            if (pattern.isEmpty() || pattern.length > MAX_REGEX_CHARS) return null
            patterns[key] = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                .getOrElse { return null }
        }
        val set = PlaybackRemotePolicy.DeviceOverrides(
            bufferMode = (raw.set["bufferMode"] as? String)?.let { name ->
                BufferMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            },
            startEngine = (raw.set["startEngine"] as? String)?.let { name ->
                PlayerMode.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            },
            tunneling = raw.set["tunneling"] as? Boolean,
            livePreviewEnabled = raw.set["livePreview"] as? Boolean,
            allowSoftwareHdFallback = raw.set["allowSoftwareHdFallback"] as? Boolean,
            compatibilityProfile = raw.set["compatibilityProfile"] as? Boolean,
            vlcDeinterlace = raw.set["vlcDeinterlace"] as? Boolean,
        )
        if (set == PlaybackRemotePolicy.DeviceOverrides()) return null
        return Rule(
            manufacturer = patterns["manufacturer"],
            model = patterns["model"],
            hardware = patterns["hardware"],
            board = patterns["board"],
            socModel = patterns["socModel"],
            sdkMin = (raw.match["sdkMin"] as? Number)?.toInt(),
            sdkMax = (raw.match["sdkMax"] as? Number)?.toInt(),
            lowRam = raw.match["lowRam"] as? Boolean,
            totalRamMaxMb = (raw.match["totalRamMaxMb"] as? Number)?.toLong(),
            set = set,
        )
    }

    fun matches(rule: Rule, facts: DeviceFacts): Boolean {
        if (rule.manufacturer?.containsMatchIn(facts.manufacturer) == false) return false
        if (rule.model?.containsMatchIn(facts.model) == false) return false
        if (rule.hardware?.containsMatchIn(facts.hardware) == false) return false
        if (rule.board?.containsMatchIn(facts.board) == false) return false
        if (rule.socModel != null) {
            val soc = facts.socModel ?: return false
            if (!rule.socModel.containsMatchIn(soc)) return false
        }
        if (rule.sdkMin != null && facts.sdk < rule.sdkMin) return false
        if (rule.sdkMax != null && facts.sdk > rule.sdkMax) return false
        if (rule.lowRam != null && facts.lowRam != rule.lowRam) return false
        if (rule.totalRamMaxMb != null) {
            val ram = facts.totalRamMb ?: return false
            if (ram > rule.totalRamMaxMb) return false
        }
        return true
    }

    /** Merge every matching rule in order; later rules win per field. */
    fun resolve(rules: List<Rule>, facts: DeviceFacts): PlaybackRemotePolicy.DeviceOverrides {
        var merged = PlaybackRemotePolicy.DeviceOverrides()
        for (rule in rules.take(MAX_RULES)) {
            if (!matches(rule, facts)) continue
            val s = rule.set
            merged = merged.copy(
                bufferMode = s.bufferMode ?: merged.bufferMode,
                startEngine = s.startEngine ?: merged.startEngine,
                tunneling = s.tunneling ?: merged.tunneling,
                livePreviewEnabled = s.livePreviewEnabled ?: merged.livePreviewEnabled,
                allowSoftwareHdFallback = s.allowSoftwareHdFallback ?: merged.allowSoftwareHdFallback,
                compatibilityProfile = s.compatibilityProfile ?: merged.compatibilityProfile,
                vlcDeinterlace = s.vlcDeinterlace ?: merged.vlcDeinterlace,
            )
        }
        return merged
    }
}
