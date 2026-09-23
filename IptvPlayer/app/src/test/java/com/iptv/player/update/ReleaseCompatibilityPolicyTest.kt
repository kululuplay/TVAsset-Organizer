package com.iptv.player.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseCompatibilityPolicyTest {

    @Test
    fun `marker line in the release body is parsed`() {
        assertEquals(26, ReleaseCompatibilityPolicy.parseMinAndroidApi("Min-Android-API: 26\n\nNotes"))
        assertEquals(23, ReleaseCompatibilityPolicy.parseMinAndroidApi("min-android-api:23\r\n- Faster zapping"))
        // A heading a human added above the CI line does not hide it.
        assertEquals(26, ReleaseCompatibilityPolicy.parseMinAndroidApi("## Notes\n Min-Android-API: 26 "))
    }

    @Test
    fun `prose and malformed markers are ignored`() {
        assertNull(ReleaseCompatibilityPolicy.parseMinAndroidApi(null))
        assertNull(ReleaseCompatibilityPolicy.parseMinAndroidApi(""))
        assertNull(
            ReleaseCompatibilityPolicy.parseMinAndroidApi(
                "Min-Android-API: 26 will be required next year",
            ),
        )
        assertNull(ReleaseCompatibilityPolicy.parseMinAndroidApi("Min-Android-API: abc"))
        assertNull(ReleaseCompatibilityPolicy.parseMinAndroidApi("Min-Android-API: 0"))
    }

    @Test
    fun `releases without a marker default by version`() {
        assertEquals(21, ReleaseCompatibilityPolicy.minAndroidApi("1.5.95", "Bug fixes."))
        assertEquals(23, ReleaseCompatibilityPolicy.minAndroidApi("1.5.96", null))
        assertEquals(23, ReleaseCompatibilityPolicy.minAndroidApi("1.5.97", ""))
        assertEquals(23, ReleaseCompatibilityPolicy.minAndroidApi("1.6.0", "Notes"))
        assertEquals(26, ReleaseCompatibilityPolicy.minAndroidApi("1.5.95", "Min-Android-API: 26"))
    }

    @Test
    fun `android version names follow the API level`() {
        assertEquals(5, ReleaseCompatibilityPolicy.androidVersion(21))
        assertEquals(6, ReleaseCompatibilityPolicy.androidVersion(23))
        assertEquals(7, ReleaseCompatibilityPolicy.androidVersion(24))
        assertEquals(8, ReleaseCompatibilityPolicy.androidVersion(26))
        assertEquals(9, ReleaseCompatibilityPolicy.androidVersion(28))
        assertEquals(10, ReleaseCompatibilityPolicy.androidVersion(29))
        assertEquals(12, ReleaseCompatibilityPolicy.androidVersion(31))
        assertEquals(12, ReleaseCompatibilityPolicy.androidVersion(32))
        assertEquals(13, ReleaseCompatibilityPolicy.androidVersion(33))
        assertEquals(14, ReleaseCompatibilityPolicy.androidVersion(34))
        assertEquals(16, ReleaseCompatibilityPolicy.androidVersion(36))
    }

    @Test
    fun `incompatible releases are skipped in favour of the next older one`() {
        val releases = listOf(
            release("1.6.0", minAndroidApi = 26),
            release("1.5.97", minAndroidApi = 23),
            release("1.5.96", minAndroidApi = 23),
            release("1.5.95", minAndroidApi = 21),
        )

        assertEquals(
            listOf("1.5.97", "1.5.96", "1.5.95"),
            UpdateCandidatePolicy.installable(releases, deviceSdkInt = 23).map { it.versionName },
        )
        assertEquals(
            listOf("1.5.97", "1.5.96", "1.5.95"),
            UpdateCandidatePolicy.installable(releases, deviceSdkInt = 25).map { it.versionName },
        )
        assertEquals(
            listOf("1.5.95"),
            UpdateCandidatePolicy.installable(releases, deviceSdkInt = 22).map { it.versionName },
        )
        assertEquals(releases, UpdateCandidatePolicy.installable(releases, deviceSdkInt = 26))
        assertEquals(
            emptyList<UpdateInfo>(),
            UpdateCandidatePolicy.installable(releases, deviceSdkInt = 19),
        )
    }

    private fun release(version: String, minAndroidApi: Int) = UpdateInfo(
        versionName = version,
        apkUrl = "https://example.test/KululuIPTV-v$version.apk",
        apkSize = 1L,
        apkSha256 = null,
        releaseUrl = "https://example.test/releases/v$version",
        notes = null,
        minAndroidApi = minAndroidApi,
    )
}
