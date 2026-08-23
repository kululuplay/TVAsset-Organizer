package com.iptv.player.playback.android

import android.content.Context
import com.iptv.player.playback.core.PlaybackContentKind
import com.iptv.player.playback.core.PlaybackEndReason
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackQoeRecorder
import com.iptv.player.playback.core.PlaybackSession
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.playback.core.PlaybackTransportKind
import com.iptv.player.playback.core.DevicePlaybackProfile
import com.iptv.player.util.StabilityTelemetry
import java.util.concurrent.Executors

/** Process-local bridge between player events and the disk-backed heartbeat spool. */
object PlaybackQoeRuntime {

    private val recorder = PlaybackQoeRecorder()
    private val collector = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "player-capability").apply { isDaemon = true }
    }
    @Volatile private var initialized = false
    @Volatile private var capabilityFingerprint:
        com.iptv.player.playback.core.CapabilityFingerprint? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            // Best effort only: even thread creation can fail under severe memory
            // pressure on low-end sticks. QoE continues without a capability hash
            // and application startup must never fail because of telemetry.
            runCatching {
                collector.execute {
                    val snapshot = runCatching {
                        AndroidDevicePlaybackProfileCache.loadOrCollect(app)
                    }.getOrNull()
                    capabilityFingerprint = snapshot?.capabilityFingerprint
                }
            }
        }
    }

    /** Last cached profile; safe conservative fallback until the background load completes. */
    fun devicePlaybackProfile(): DevicePlaybackProfile =
        AndroidDevicePlaybackProfileCache.currentProfile()

    fun start(
        kind: PlaybackContentKind,
        engine: PlaybackEngineKind,
        transport: PlaybackTransportKind,
    ): PlaybackSessionId {
        val id = PlaybackSessionId.random()
        recorder.start(
            PlaybackSession(
                id = id,
                kind = kind,
                startedAtEpochMs = System.currentTimeMillis(),
                initialEngine = engine,
                transport = transport,
                capabilityFingerprint = capabilityFingerprint,
            ),
        )
        return id
    }

    fun markEngine(id: PlaybackSessionId?, engine: PlaybackEngineKind) {
        id?.let { recorder.markEngine(it, engine) }
    }

    fun markTransport(id: PlaybackSessionId?, transport: PlaybackTransportKind) {
        id?.let { recorder.markTransport(it, transport) }
    }

    fun markReady(id: PlaybackSessionId?) {
        id?.let(recorder::markReady)
    }

    fun markFirstFrame(id: PlaybackSessionId?) {
        id?.let(recorder::markFirstFrame)
    }

    fun setRebuffering(id: PlaybackSessionId?, buffering: Boolean) {
        id?.let { recorder.setRebuffering(it, buffering) }
    }

    fun recordFailure(id: PlaybackSessionId?, failure: PlaybackFailure) {
        id?.let { recorder.recordFailure(it, failure) }
    }

    fun finish(id: PlaybackSessionId?, reason: PlaybackEndReason) {
        val record = id?.let {
            recorder.finish(it, reason, System.currentTimeMillis())
        } ?: return
        StabilityTelemetry.recordQoe(record)
    }
}
