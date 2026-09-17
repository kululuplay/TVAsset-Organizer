/*
 * PolicyPublicKey.kt
 * Build-time P-256 SPKI PEM the heartbeat uses to verify signed playback
 * policies (see PolicySignature). Empty in builds without a key file, which
 * keeps the legacy unsigned-policy path for dev/local use. Indirected through
 * this object so tests can stub the key without touching BuildConfig.
 */
package com.iptv.player.security

import com.iptv.player.BuildConfig

object PolicyPublicKey {

    /** Test hook: non-null replaces the build-time PEM (including "" = no key). */
    @Volatile
    internal var overrideForTests: String? = null

    val pem: String
        get() = overrideForTests ?: runCatching { BuildConfig.POLICY_PUBLIC_KEY_PEM }.getOrDefault("")

    /** True when this build enforces signatures on remote playback policies. */
    val isConfigured: Boolean get() = pem.isNotBlank()
}
