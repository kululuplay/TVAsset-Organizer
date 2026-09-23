/*
 * ReleaseCompatibilityPolicy.kt
 * Decides which GitHub releases this device can run. A release's minimum
 * Android API travels in the release body ("Min-Android-API: 23", written by
 * CI as the first line) rather than in update-rollout.json: every client checks
 * that policy against a strict key allowlist, so a new key there would make
 * the whole fleet fail closed. Clients before 1.5.97 ignore the body line.
 */
package com.iptv.player.update

internal object ReleaseCompatibilityPolicy {
    /** First release built with minSdk 23; everything before it ran on API 21. */
    const val FIRST_API_23_RELEASE = "1.5.96"
    const val DEFAULT_RELEASE_MIN_API = 23
    const val LEGACY_RELEASE_MIN_API = 21

    /**
     * A whole body line of the form `Min-Android-API: 23`. Matching the entire
     * line keeps prose such as "Min-Android-API 26 will be required next year"
     * from being misread, while tolerating a heading a human added above it.
     */
    private val MIN_API_LINE =
        Regex("^Min-Android-API\\s*:\\s*(\\d{1,3})$", RegexOption.IGNORE_CASE)

    /** The declared minimum API, or null when the body carries no marker line. */
    fun parseMinAndroidApi(body: String?): Int? =
        body?.lineSequence()
            ?.map { it.trim() }
            ?.mapNotNull { line ->
                MIN_API_LINE.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
            }
            ?.firstOrNull { it > 0 }

    /** Releases published before the marker existed are classified by version. */
    fun defaultMinAndroidApi(versionName: String): Int =
        if (UpdateRolloutGatePolicy.compareVersions(versionName, FIRST_API_23_RELEASE) >= 0) {
            DEFAULT_RELEASE_MIN_API
        } else {
            LEGACY_RELEASE_MIN_API
        }

    fun minAndroidApi(versionName: String, body: String?): Int =
        parseMinAndroidApi(body) ?: defaultMinAndroidApi(versionName)

    /**
     * Marketing Android version for the "requires Android N or newer" text:
     * 23 -> 6, 26 -> 8, 28 -> 9, 34 -> 14. Levels after 36 extrapolate.
     */
    fun androidVersion(api: Int): Int = when {
        api <= 20 -> 4
        api <= 22 -> 5
        api == 23 -> 6
        api <= 25 -> 7
        api <= 27 -> 8
        api == 28 -> 9
        api <= 31 -> api - 19
        api == 32 -> 12
        else -> api - 20
    }
}

/** Pure selection over the parsed release list, newest first. */
internal object UpdateCandidatePolicy {
    /**
     * Releases the device can install, in the original (newest first) order. A
     * release whose minimum API exceeds the device is dropped so the next older
     * production release is considered instead of stranding the device; the
     * rollout gate still decides whether that older release may be offered.
     */
    fun installable(releases: List<UpdateInfo>, deviceSdkInt: Int): List<UpdateInfo> =
        releases.filter { it.minAndroidApi <= deviceSdkInt }
}
