/*
 * AppInfo.kt
 * App-wide identity constants. The User-Agent is shared by every network layer
 * (OkHttp REST/update calls, ExoPlayer HTTP data source, libVLC stream fetch) so
 * upstream servers always see a single, consistent client name.
 */
package com.iptv.player.util

object AppInfo {
    /** Reported on every outgoing HTTP request and stream pull. */
    const val USER_AGENT = "KULULUPLAY"
}
