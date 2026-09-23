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
import com.iptv.player.util.PlaybackSupportReporter
import com.iptv.player.playback.core.PlaybackObservation
import com.iptv.player.playback.core.PlaybackQoeRecord
import java.util.concurrent.Executors

/** Process-local bridge between player events and the disk-backed heartbeat spool. */
object PlaybackQoeRuntime {

    private val recorder = PlaybackQoeRecorder()
    private val labels = java.util.concurrent.ConcurrentHashMap<PlaybackSessionId, String>()
    private fun fields(record: PlaybackQoeRecord): Map<String, Any> = record.toSafeFields().toMutableMap().apply {
        labels[record.session.id]?.let { put("content_label", it) }
    }
    fun supportSnapshot(): List<Map<String, Any>> = recorder.activeSnapshot().map(::fields)
    fun pauseForBackground() { recorder.pauseAll() }
    fun resetOutputEvidence(id: PlaybackSessionId?) { id?.let(recorder::resetOutputEvidence) }
    fun setPaused(id: PlaybackSessionId?, paused: Boolean) { id?.let { recorder.setPaused(it, paused) } }
    fun observe(id: PlaybackSessionId?, sample: PlaybackObservation) { id?.let { recorder.observe(it, sample) } }
    private val diagnostics = com.iptv.player.playback.core.PlaybackDiagnosticState()
    fun diagnosticSnapshot(): Map<String, String> = diagnostics.snapshot()
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
            PlaybackSupportReporter.init(app)
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
        contentLabel: String? = null,
    ): PlaybackSessionId {
        val id = PlaybackSessionId.random()
        diagnostics.start(id, engine, transport, System.currentTimeMillis())
        val started = recorder.start(
            PlaybackSession(
                id = id,
                kind = kind,
                startedAtEpochMs = System.currentTimeMillis(),
                initialEngine = engine,
                transport = transport,
                capabilityFingerprint = capabilityFingerprint,
            ),
        )
        if (started) {
            contentLabel?.take(512)?.let { labels[id] = it }
            recorder.snapshotActive(id)?.let { PlaybackSupportReporter.capture(listOf(fields(it))) }
        }
        return id
    }

    fun markEngine(id: PlaybackSessionId?, engine: PlaybackEngineKind) {
        diagnostics.engine(id, engine)
        id?.let { recorder.markEngine(it, engine) }
    }

    fun markTransport(id: PlaybackSessionId?, transport: PlaybackTransportKind) {
        diagnostics.transport(id, transport)
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
        diagnostics.failure(id, failure)
        id?.let { recorder.recordFailure(it, failure) }
        id?.let(recorder::snapshotActive)?.let { PlaybackSupportReporter.capture(listOf(fields(it))) }
    }

    fun finish(id: PlaybackSessionId?, reason: PlaybackEndReason) {
        diagnostics.finish(id)
        val record = id?.let {
            recorder.finish(it, reason, System.currentTimeMillis())
        } ?: return
        PlaybackSupportReporter.capture(listOf(fields(record)))
        labels.remove(id)
        StabilityTelemetry.recordQoe(record)
    }
}
