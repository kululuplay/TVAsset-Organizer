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
import com.iptv.player.util.PlaybackSupportReporter
import com.iptv.player.playback.core.PlaybackObservation
import com.iptv.player.playback.core.PlaybackIncidentRecorder
import com.iptv.player.playback.core.PlaybackIncidentTrigger
import android.os.SystemClock
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
    private val labels = java.util.concurrent.ConcurrentHashMap<PlaybackSessionId, String>()
    private val keys = java.util.concurrent.ConcurrentHashMap<PlaybackSessionId, String>()
    private val incidents = PlaybackIncidentRecorder()
    private val recent = java.util.concurrent.ConcurrentHashMap<PlaybackSessionId, PlaybackQoeRecord>()
    private val recentOrder = java.util.ArrayDeque<PlaybackSessionId>()
    private fun fields(record: PlaybackQoeRecord): Map<String, Any> = com.iptv.player.playback.core.PlaybackSupportFields.from(record).toMutableMap().apply {
        labels[record.session.id]?.let { put("content_label", it) }
        this@PlaybackQoeRuntime.keys[record.session.id]?.let { put("content_key", it) }
    }
    fun supportSnapshot(): List<Map<String, Any>> = recorder.activeSnapshot().map(::fields)
    fun pauseForBackground() {
        recorder.pauseAll()
        sampleIncidents()
        recorder.activeSnapshot().forEach { incidents.flush(it.session.id) }
        publishIncidents()
    }
    fun resetOutputEvidence(id: PlaybackSessionId?) { id?.let(recorder::resetOutputEvidence) }
    fun setPaused(id: PlaybackSessionId?, paused: Boolean) { id?.let { recorder.setPaused(it, paused) } }
    fun observe(id: PlaybackSessionId?, sample: PlaybackObservation) {
        id?.let { recorder.observe(it, sample) }
    }

    /** Samples only the existing in-memory recorder: never queries a player, decoder or socket. */
    fun sampleIncidents() {
        val now = SystemClock.elapsedRealtime()
        val epoch = System.currentTimeMillis()
        recorder.activeSnapshot().forEach { incidents.observe(it, keys[it.session.id], now, epoch) }
        publishIncidents()
    }

    /** Sessions whose current row travelled inside an incident envelope; callers skip a duplicate boundary for them. */
    private fun publishIncidents(): Set<PlaybackSessionId> {
        val carried = HashSet<PlaybackSessionId>()
        incidents.drain().forEach { incident ->
            val id = PlaybackSessionId.from(incident.sessionId)
            val active = recorder.activeSnapshot()
            val record = active.firstOrNull { it.session.id == id } ?: recent[id]
            // A report about a just-ended channel must not hide a newer currently playing session.
            val rows = (active + listOfNotNull(record?.takeIf { ended -> active.none { it.session.id == ended.session.id } }))
            PlaybackSupportReporter.capture(
                rows.map(::fields),
                incident = incident.toSafeFields(),
            )
            rows.forEach { carried += it.session.id }
        }
        return carried
    }

    data class ProblemReportContext(val sessionId: String, val contentKey: String?, val incidentId: String, val label: String?)

    /** Same explicit report within one minute reuses an incident and therefore the same ticket fingerprint. */
    fun reportProblem(id: PlaybackSessionId?, contentKey: String?): ProblemReportContext? {
        val record = id?.let { recorder.snapshotActive(it) ?: recent[it] }
            ?: contentKey?.let { key ->
                latestReportableSession(recorder.activeSnapshot() + recent.values, key, { keys[it] }, System.currentTimeMillis())
            } ?: return null
        val session = record.session.id
        val incidentId = incidents.trigger(record, keys[session], PlaybackIncidentTrigger.USER_REPORT,
            SystemClock.elapsedRealtime(), System.currentTimeMillis()) ?: return null
        publishIncidents()
        return ProblemReportContext(session.value, keys[session], incidentId, labels[session])
    }
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
        contentKey: String? = null,
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
        val started = recorder.start(session)
        if (started) ledgerOpen(session)
        if (started) {
            contentLabel?.take(512)?.let { labels[id] = it }
            contentKey?.takeIf { Regex("[a-f0-9]{64}").matches(it) }?.let { keys[id] = it }
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
        id?.let(recorder::snapshotActive)?.let {
            incidents.trigger(it, keys[it.session.id], PlaybackIncidentTrigger.PLAYBACK_ERROR,
                SystemClock.elapsedRealtime(), System.currentTimeMillis())
            // One envelope per failure: an incident capture already carries this session's failed row.
            if (it.session.id !in publishIncidents()) PlaybackSupportReporter.capture(listOf(fields(it)))
        }
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
        recent[record.session.id] = record
        synchronized(recentOrder) {
            recentOrder.addLast(record.session.id)
            while (recentOrder.size > 16) {
                val removed = recentOrder.removeFirst()
                recent.remove(removed); labels.remove(removed); keys.remove(removed)
            }
        }
        incidents.observe(record, keys[record.session.id], SystemClock.elapsedRealtime(), System.currentTimeMillis())
        incidents.flush(record.session.id)
        // The final row travels inside a closing incident window when there is one; otherwise it is its own boundary.
        if (record.session.id !in publishIncidents()) PlaybackSupportReporter.capture(listOf(fields(record)))
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

/** A Cast preview reports without a session id; only playback still active or ended this recently may be linked. */
internal const val REPORT_LINK_WINDOW_MS = 2 * 60_000L

/** Newest session with [contentKey] that is active or ended within [REPORT_LINK_WINDOW_MS]; orphans carry no evidence. */
internal fun latestReportableSession(
    candidates: Collection<PlaybackQoeRecord>,
    contentKey: String,
    keyOf: (PlaybackSessionId) -> String?,
    nowEpochMs: Long,
): PlaybackQoeRecord? = candidates.filter { record ->
    keyOf(record.session.id) == contentKey && record.endReason != PlaybackEndReason.ABANDONED &&
        (record.endedAtEpochMs?.let { ended -> nowEpochMs - ended in 0..REPORT_LINK_WINDOW_MS } ?: !record.isFinal)
}.maxByOrNull { it.session.startedAtEpochMs }
