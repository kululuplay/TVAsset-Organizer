package com.iptv.player.playback.android

import android.content.Context
import com.iptv.player.playback.core.CapabilityFingerprint
import com.iptv.player.playback.core.PlaybackContentKind
import com.iptv.player.playback.core.PlaybackEndReason
import com.iptv.player.playback.core.PlaybackEngineKind
import com.iptv.player.playback.core.PlaybackFailure
import com.iptv.player.playback.core.PlaybackQoeRecord
import com.iptv.player.playback.core.PlaybackQoeRecorder
import com.iptv.player.playback.core.PlaybackSession
import com.iptv.player.playback.core.PlaybackSessionId
import com.iptv.player.playback.core.PlaybackTransportKind
import com.iptv.player.playback.core.DevicePlaybackProfile
import com.iptv.player.util.Logger
import com.iptv.player.util.QoeSpool
import com.iptv.player.util.Telemetry
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Process-local bridge between player events and the disk-backed QoE spool the
 * heartbeat drains (`"qoe": [...]`, see HeartbeatReporter / QoeSpool).
 *
 * Session lifetime safety nets, because the UI owns finish() and a missed
 * teardown must not lose or pin a session:
 *  - starting a session of a kind that already has one open closes the older
 *    one as REPLACED (a player can only render one Live or one VOD at a time);
 *  - any session open for more than PlaybackQoeRecorder.ABANDON_AFTER_MS is
 *    swept as ABANDONED (checked on every start and every heartbeat drain);
 *  - open sessions are mirrored to a tiny ledger file; on the next process
 *    start, entries older than that same limit are spooled as ABANDONED.
 */
object PlaybackQoeRuntime {

    private const val TAG = "PlaybackQoe"
    private const val OPEN_LEDGER_FILE = "qoe-open.jsonl"

    private val recorder = PlaybackQoeRecorder()
    private val diagnostics = com.iptv.player.playback.core.PlaybackDiagnosticState()
    fun diagnosticSnapshot(): Map<String, String> = diagnostics.snapshot()
    private val collector = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "player-capability").apply { isDaemon = true }
    }
    private val ledgerIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qoe-ledger").apply { isDaemon = true }
    }
    @Volatile private var initialized = false
    @Volatile private var capabilityFingerprint: CapabilityFingerprint? = null
    @Volatile private var spool: QoeSpool? = null
    @Volatile private var ledgerFile: File? = null

    private val ledgerLock = Any()
    private val openLedger = LinkedHashMap<String, JSONObject>()

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            val app = context.applicationContext
            runCatching {
                val s = QoeSpool(File(app.filesDir, QoeSpool.FILE_NAME))
                s.init()
                spool = s
                ledgerFile = File(app.filesDir, OPEN_LEDGER_FILE)
                ledgerIo.execute { runCatching { flushOrphanedSessions(System.currentTimeMillis()) } }
            }
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
        val now = System.currentTimeMillis()
        sweepStale(now)
        // One renderer per kind: an older open session of the same kind was
        // never finished by its owner, so close it before it pins the recorder.
        recorder.activeSessionIds(kind).forEach { stale -> finish(stale, PlaybackEndReason.REPLACED) }
        val id = PlaybackSessionId.random()
        diagnostics.start(id, engine, transport, now)
        val session = PlaybackSession(
            id = id,
            kind = kind,
            startedAtEpochMs = now,
            initialEngine = engine,
            transport = transport,
            capabilityFingerprint = capabilityFingerprint,
        )
        recorder.start(session)
        ledgerOpen(session)
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
    }

    fun finish(id: PlaybackSessionId?, reason: PlaybackEndReason) {
        diagnostics.finish(id)
        val sessionId = id ?: return
        val record = recorder.finish(sessionId, reason, System.currentTimeMillis())
        // Close the ledger entry even when the recorder never tracked or already
        // closed this session (active cap hit / auto-REPLACED); otherwise the
        // entry outlives the session and resurfaces as a phantom ABANDONED.
        ledgerClose(sessionId)
        if (record != null) spoolRecord(record)
    }

    /** Alias for callers that think in "end" terms; same as [finish]. */
    fun endSession(id: PlaybackSessionId?, reason: PlaybackEndReason) = finish(id, reason)

    /**
     * Oldest finished summaries for the heartbeat, WITHOUT removing them; the
     * caller passes the ids back to [confirmUploaded] after a 2xx. Also runs the
     * stale sweep so an abandoned session surfaces within one beat of the limit.
     */
    fun pendingUploads(max: Int): List<QoeSpool.Entry> {
        sweepStale(System.currentTimeMillis())
        return spool?.snapshot(max).orEmpty()
    }

    fun confirmUploaded(ids: Collection<String>) {
        spool?.confirmUploaded(ids)
    }

    /** Summaries the spool had to drop to overflow that no beat has reported yet. */
    fun pendingDroppedCount(): Int = spool?.droppedCount() ?: 0

    fun confirmDropped(count: Int) {
        spool?.confirmDropped(count)
    }

    private fun sweepStale(nowEpochMs: Long) {
        runCatching {
            recorder.finishStale(PlaybackQoeRecorder.ABANDON_AFTER_MS, nowEpochMs).forEach { record ->
                diagnostics.finish(record.session.id)
                ledgerClose(record.session.id)
                spoolRecord(record)
            }
        }
    }

    private fun spoolRecord(record: PlaybackQoeRecord) {
        // No upload path without telemetry, so do not even fill the spool.
        if (!Telemetry.isEnabled) return
        val s = spool ?: return
        runCatching {
            val json = JSONObject().apply {
                record.toSafeFields().forEach { (key, value) -> put(key, value) }
            }.toString()
            if (!s.append(json)) Logger.w(TAG, "qoe summary refused by spool")
        }
    }

    // ---- Open-session ledger (orphan detection across process death) --------

    private fun ledgerOpen(session: PlaybackSession) {
        runCatching {
            val entry = JSONObject().apply {
                put("session_id", session.id.value)
                put("content_kind", session.kind.name)
                put("started_at_epoch_ms", session.startedAtEpochMs)
                put("initial_engine", session.initialEngine.name)
                put("transport", session.transport.name)
                session.capabilityFingerprint?.let { put("capability_fingerprint", it.value) }
            }
            synchronized(ledgerLock) { openLedger[session.id.value] = entry }
            persistLedgerAsync()
        }
    }

    private fun ledgerClose(id: PlaybackSessionId) {
        val removed = synchronized(ledgerLock) { openLedger.remove(id.value) != null }
        if (removed) persistLedgerAsync()
    }

    private fun persistLedgerAsync() {
        val f = ledgerFile ?: return
        ledgerIo.execute {
            runCatching {
                val snapshot = synchronized(ledgerLock) { openLedger.values.toList() }
                if (snapshot.isEmpty()) {
                    if (f.exists()) f.delete()
                } else {
                    val text = snapshot.joinToString("\n", postfix = "\n") { it.toString() }
                    // Temp + rename: a truncated ledger would drop/garble open
                    // sessions the next process start should flush as ABANDONED.
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(f)) {
                        f.writeText(text)
                        tmp.delete()
                    }
                }
            }
        }
    }

    /**
     * Sessions a previous process left open. Those older than the abandon limit
     * are spooled as ABANDONED and dropped from the ledger; younger ones stay
     * listed until they age past it (a later start will flush them).
     */
    private fun flushOrphanedSessions(nowEpochMs: Long) {
        val f = ledgerFile ?: return
        if (!f.exists()) return
        val kept = ArrayList<JSONObject>()
        f.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
            val session = runCatching { sessionFromLedger(obj) }.getOrNull() ?: return@forEach
            if (nowEpochMs - session.startedAtEpochMs >= PlaybackQoeRecorder.ABANDON_AFTER_MS) {
                spoolRecord(PlaybackQoeRecorder.abandoned(session, nowEpochMs))
            } else {
                kept.add(obj)
            }
        }
        synchronized(ledgerLock) {
            // Sessions started by this process since init() take precedence.
            kept.forEach { obj ->
                val id = obj.optString("session_id")
                if (id.isNotBlank() && !openLedger.containsKey(id)) openLedger[id] = obj
            }
        }
        persistLedgerAsync()
    }

    private fun sessionFromLedger(obj: JSONObject): PlaybackSession = PlaybackSession(
        id = PlaybackSessionId.from(obj.getString("session_id")),
        kind = PlaybackContentKind.valueOf(obj.getString("content_kind")),
        startedAtEpochMs = obj.getLong("started_at_epoch_ms"),
        initialEngine = PlaybackEngineKind.valueOf(obj.getString("initial_engine")),
        transport = PlaybackTransportKind.valueOf(obj.getString("transport")),
        capabilityFingerprint = obj.optString("capability_fingerprint")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { CapabilityFingerprint(it) }.getOrNull() },
    )
}
