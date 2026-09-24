package com.iptv.player.player

import android.content.Context
import android.provider.Settings
import com.iptv.player.BuildConfig

/**
 * Device-test switches for LIVE_PLAYBACK_DIAGNOSTICS builds only, set over adb:
 * `settings put global kululu_tunnel 1` forces Media3 video tunneling on the next
 * play; `settings put global kululu_vlc_opts "--a --b"` appends libVLC options.
 * Normal builds never read them.
 */
internal object DiagnosticSwitches {
    fun forceTunneling(context: Context): Boolean =
        BuildConfig.LIVE_PLAYBACK_DIAGNOSTICS &&
            runCatching { Settings.Global.getInt(context.contentResolver, "kululu_tunnel", 0) }
                .getOrDefault(0) == 1

    fun extraVlcOptions(context: Context): List<String> =
        if (!BuildConfig.LIVE_PLAYBACK_DIAGNOSTICS) {
            emptyList()
        } else {
            runCatching { Settings.Global.getString(context.contentResolver, "kululu_vlc_opts") }
                .getOrNull()
                .orEmpty()
                .split(' ')
                .filter { it.startsWith("--") }
        }
}
