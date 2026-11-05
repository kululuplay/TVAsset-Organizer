package com.iptv.player.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerUriPolicyTest {

    @Test
    fun `API 23 uses file scheme to avoid Marshmallow PackageInstaller parse error`() {
        assertTrue(ApkInstallerUriPolicy.useFileUri(23))
        assertTrue(ApkInstallerUriPolicy.useFileUri(22))
        assertFalse(ApkInstallerUriPolicy.useFileUri(24))
        assertFalse(ApkInstallerUriPolicy.useFileUri(30))
        assertFalse(ApkInstallerUriPolicy.useFileUri(34))
    }

    @Test
    fun `known OEM and TV package installers are included in explicit URI grants`() {
        assertTrue("com.android.packageinstaller" in ApkInstallerUriPolicy.KNOWN_INSTALLER_PACKAGES)
        assertTrue("com.google.android.packageinstaller" in ApkInstallerUriPolicy.KNOWN_INSTALLER_PACKAGES)
        assertTrue("com.amazon.packageinstaller" in ApkInstallerUriPolicy.KNOWN_INSTALLER_PACKAGES)
    }
}
