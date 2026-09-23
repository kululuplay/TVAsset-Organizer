"use strict";
const crypto = require("node:crypto");
const { equal, redact } = require("./security");
const { RETENTION_MS, FRESH_MS, diagnose, presentDevice } = require("./playback");
const { WINDOW_MS, mergeIncident, compareChannelGroup, measurementPoints, compareIntervention } = require("./playback-analysis");
class PgStore {
  constructor(pool, caps = {}) { this.pool = pool; this.caps = { installations: 100000, tickets: 100000, logs: 10000, playbackDevices: 25000, playbackSamples: 100000, playbackPerDevice: 2000, playbackReceipts: 1000000, incidents: 50000, incidentsPerDevice: 200, interventions: 10000, interventionsPerDevice: 100, ...caps }; }
  async transaction(action) {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      // One local database lock makes capacity checks atomic, including across workers.
      await client.query("SELECT pg_advisory_xact_lock(584027163)");
      const result = await action(client);
      await client.query("COMMIT"); return result;
    } catch (error) { await client.query("ROLLBACK"); throw error; }
    finally { client.release(); }
  }
  capacity() { const error = new Error("Support capacity reached"); error.code = "support_capacity"; throw error; }
  async healthy() { await this.pool.query("SELECT 1"); }
  async register(id, secretHash) {
    return this.transaction(async client => {
      const { rows } = await client.query("SELECT secret_hash FROM support_installations WHERE id=$1", [id]);
      if (rows[0]) return equal(rows[0].secret_hash, secretHash);
      const count = await client.query("SELECT count(*)::int AS n FROM support_installations");
      if (count.rows[0].n >= this.caps.installations) this.capacity();
      await client.query("INSERT INTO support_installations(id,secret_hash) VALUES($1,$2)", [id, secretHash]);
      return true;
    });
  }
  async authenticate(id, secretHash) {
    const { rows } = await this.pool.query("SELECT secret_hash FROM support_installations WHERE id=$1", [id]);
    return Boolean(rows[0] && equal(rows[0].secret_hash, secretHash));
  }
  async create(installationId, ticket) {
    return this.transaction(async client => {
      const existing = await client.query("SELECT id,code,status,created_at FROM support_tickets WHERE installation_id=$1 AND request_id=$2", [installationId, ticket.requestId]);
      if (existing.rows[0]) return existing.rows[0];
      const usage = await client.query("SELECT count(*)::int AS tickets, count(*) FILTER (WHERE log<>'')::int AS logs FROM support_tickets");
      if (usage.rows[0].tickets >= this.caps.tickets || (ticket.log && usage.rows[0].logs >= this.caps.logs)) this.capacity();
      const code = "K-" + crypto.randomBytes(8).toString("hex").toUpperCase();
      const { rows } = await client.query(`INSERT INTO support_tickets(code,installation_id,request_id,type,message,log,metadata)
        VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING id,code,status,created_at`, [code, installationId, ticket.requestId, ticket.type, ticket.message, ticket.log, ticket.metadata]);
      return rows[0];
    });
  }
  async maintain() {
    // Keep customer requests and ticket history; expire only diagnostic attachments.
    await this.pool.query("UPDATE support_tickets SET log='' WHERE log<>'' AND created_at < now()-interval '90 days'");
    await this.pool.query("DELETE FROM support_playback_samples WHERE received_at < now()-interval '7 days'");
    await this.pool.query("DELETE FROM support_playback_receipts WHERE received_at < now()-interval '7 days'");
    await this.pool.query("DELETE FROM support_playback_devices WHERE last_seen_at < now()-interval '7 days'");
    await this.pool.query("DELETE FROM support_playback_incidents WHERE received_at < now()-interval '7 days'");
    await this.pool.query("DELETE FROM support_playback_interventions WHERE created_at < now()-interval '7 days'");
    await this.pool.query("DELETE FROM support_installations i WHERE created_at < now()-interval '30 days' AND NOT EXISTS (SELECT 1 FROM support_tickets t WHERE t.installation_id=i.id) AND NOT EXISTS (SELECT 1 FROM support_playback_devices d WHERE d.installation_id=i.id) AND NOT EXISTS (SELECT 1 FROM support_playback_samples s WHERE s.installation_id=i.id) AND NOT EXISTS (SELECT 1 FROM support_playback_receipts r WHERE r.installation_id=i.id) AND NOT EXISTS (SELECT 1 FROM support_playback_incidents n WHERE n.installation_id=i.id) AND NOT EXISTS (SELECT 1 FROM support_playback_interventions m WHERE m.installation_id=i.id)");
  }
  async mine(id) {
    const { rows } = await this.pool.query(`SELECT id,code,type,message,status,created_at AS "createdAt" FROM support_tickets
      WHERE installation_id=$1 AND type<>'diagnostic' ORDER BY id DESC LIMIT 20`, [id]);
    return rows.map(row => ({ ...row, message: redact(row.message) }));
  }
  async ack(id, ids) { await this.pool.query("UPDATE support_tickets SET notified=true WHERE installation_id=$1 AND id=ANY($2::bigint[])", [id, ids]); }
  async list({ status, type, before, query, scope }) {
    const values = []; const where = [];
    const add = (sql, value) => { values.push(value); where.push(sql.replace("?", `$${values.length}`)); };
    if (status) add("status=?", status);
    if (type) add("type=?", type);
    if (scope === "requests") where.push("type<>'diagnostic'");
    if (before) add("id<?", before);
    if (query) add("(code || ' ' || message || ' ' || COALESCE(metadata->>'model','') || ' ' || COALESCE(metadata->>'appVersion','')) ILIKE ?", `%${query.replace(/[\\%_]/g, "\\$&")}%`);
    const { rows } = await this.pool.query(`SELECT id,code,type,message,status,metadata,created_at,updated_at,installation_id AS "installationId",installation_id AS "deviceCode" FROM support_tickets
      ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY id DESC LIMIT 31`, values);
    const more = rows.length > 30; const items = rows.slice(0, 30).map(row => ({ ...row, message: redact(row.message) }));
    return { items, nextCursor: more ? items.at(-1).id : null };
  }
  async detail(id) {
    const { rows } = await this.pool.query('SELECT id,code,type,message,log,status,metadata,created_at,updated_at,installation_id AS "installationId",installation_id AS "deviceCode" FROM support_tickets WHERE id=$1', [id]);
    return rows[0] ? { ...rows[0], message: redact(rows[0].message), log: redact(rows[0].log) } : null;
  }
  async status(id, status, actor) {
    const { rows } = await this.pool.query(`WITH changed AS (
      UPDATE support_tickets SET status=$2,updated_at=now(),notified=false WHERE id=$1 AND status<>$2 RETURNING id
    ) INSERT INTO support_audit(ticket_id,actor,status) SELECT id,$3,$2 FROM changed RETURNING ticket_id`, [id, status, actor]);
    return rows.length > 0 || Boolean(await this.detail(id));
  }
  async stats() {
    const { rows } = await this.pool.query(`SELECT count(*)::int AS total,
      count(*) FILTER(WHERE status<>'done')::int AS open,
      count(*) FILTER(WHERE type='diagnostic')::int AS reports,
      count(DISTINCT installation_id)::int AS devices FROM support_tickets`);
    return rows[0];
  }
  async playback(installationId, sample, now = Date.now()) {
    return this.transaction(async client => {
      const existing = await client.query("SELECT sample_id FROM support_playback_receipts WHERE installation_id=$1 AND sample_id=$2", [installationId, sample.sampleId]);
      // A retry acknowledges the original durable write without changing freshness.
      if (existing.rows.length) return sample.sampleId;
      await client.query("DELETE FROM support_playback_samples WHERE received_at < $1", [new Date(now - RETENTION_MS)]);
      await client.query("DELETE FROM support_playback_receipts WHERE received_at < $1", [new Date(now - RETENTION_MS)]);
      const usage = await client.query("SELECT count(*)::int AS total, count(*) FILTER (WHERE installation_id=$1)::int AS device FROM support_playback_samples", [installationId]);
      if ((await client.query("SELECT count(*)::int AS n FROM support_playback_receipts")).rows[0].n >= this.caps.playbackReceipts) this.capacity();
      const prior = (await client.query("SELECT * FROM support_playback_devices WHERE installation_id=$1", [installationId])).rows[0];
      if (!prior && (await client.query("SELECT count(*)::int AS n FROM support_playback_devices")).rows[0].n >= this.caps.playbackDevices) this.capacity();
      if (usage.rows[0].device >= this.caps.playbackPerDevice) {
        await client.query("DELETE FROM support_playback_samples WHERE id IN (SELECT id FROM support_playback_samples WHERE installation_id=$1 ORDER BY id ASC LIMIT $2)", [installationId, usage.rows[0].device - this.caps.playbackPerDevice + 1]);
        await client.query("UPDATE support_playback_devices SET history_limited=true WHERE installation_id=$1", [installationId]);
      } else if (usage.rows[0].total >= this.caps.playbackSamples) {
        await client.query(`WITH removed AS (DELETE FROM support_playback_samples WHERE id IN (SELECT id FROM support_playback_samples ORDER BY id ASC LIMIT $1) RETURNING installation_id)
          UPDATE support_playback_devices SET history_limited=true WHERE installation_id IN (SELECT installation_id FROM removed)`, [usage.rows[0].total - this.caps.playbackSamples + 1]);
      }
      await client.query("INSERT INTO support_playback_receipts(installation_id,sample_id,received_at) VALUES($1,$2,$3)", [installationId, sample.sampleId, new Date(now)]);
      await client.query("INSERT INTO support_playback_samples(installation_id,sample_id,sampled_at_ms,received_at,payload) VALUES($1,$2,$3,$4,$5)", [installationId, sample.sampleId, sample.sampledAtMs, new Date(now), sample]);
      for (const incident of sample.incidents || []) {
        const previous = (await client.query("SELECT payload,sampled_at_ms FROM support_playback_incidents WHERE installation_id=$1 AND incident_id=$2", [installationId, incident.incidentId])).rows[0];
        const merged = mergeIncident(previous?.payload, incident, Number(previous?.sampled_at_ms || 0), sample.sampledAtMs);
        if (previous && merged === previous.payload) continue;
        if (!previous) await this.trimObservations(client, "support_playback_incidents", "received_at", installationId, this.caps.incidents, this.caps.incidentsPerDevice, now);
        await client.query(`INSERT INTO support_playback_incidents(installation_id,incident_id,sampled_at_ms,received_at,payload) VALUES($1,$2,$3,$4,$5)
          ON CONFLICT(installation_id,incident_id) DO UPDATE SET sampled_at_ms=EXCLUDED.sampled_at_ms,payload=EXCLUDED.payload`, [installationId, incident.incidentId, sample.sampledAtMs, new Date(now), merged]);
      }
      // Offline retries and out-of-order samples cannot rewind a live device or session.
      if (!prior || sample.sampledAtMs > Number(prior.sampled_at_ms)) {
        const previous = new Map((prior?.sessions || []).map(row => [row.session_id, row]));
        const sessions = new Map();
        for (const row of sample.sessions) {
          const old = previous.get(row.session_id);
          if (old && (old.session_duration_ms > row.session_duration_ms || (old.final && !row.final))) { sessions.set(row.session_id, old); continue; }
          sessions.set(row.session_id, { ...row, sampledAt: new Date(sample.sampledAtMs).toISOString(), receivedAt: new Date(now).toISOString() });
        }
        const latest = [...sessions.values()].sort((a, b) => (b.started_at_epoch_ms || 0) - (a.started_at_epoch_ms || 0)).slice(0, 16);
        const statusSampledAt = sample.device.lowMemory || sample.device.networkConnected === false ? sample.sampledAtMs : Math.min(sample.sampledAtMs, latest[0] ? Date.parse(latest[0].sampledAt) : sample.sampledAtMs);
        const status = diagnose(sample.device, latest.filter(row => Math.abs(now - Date.parse(row.sampledAt)) <= FRESH_MS), Math.abs(now - sample.sampledAtMs) <= FRESH_MS).status;
        await client.query(`INSERT INTO support_playback_devices(installation_id,sampled_at_ms,last_seen_at,device,sessions,status,status_sampled_at_ms)
          VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(installation_id) DO UPDATE SET sampled_at_ms=EXCLUDED.sampled_at_ms,last_seen_at=EXCLUDED.last_seen_at,device=EXCLUDED.device,sessions=EXCLUDED.sessions,status=EXCLUDED.status,status_sampled_at_ms=EXCLUDED.status_sampled_at_ms`,
          [installationId, sample.sampledAtMs, new Date(now), sample.device, JSON.stringify(latest), status, statusSampledAt]);
      }
      return sample.sampleId;
    });
  }
  async playbackList({ status, query, before }, now = Date.now()) {
    const bounds = [new Date(now - RETENTION_MS), new Date(now - FRESH_MS), now - FRESH_MS, now + FRESH_MS];
    const values = [bounds[0]];
    const where = ["last_seen_at >= $1"];
    if (status === "problem") { values.push(...bounds.slice(1)); where.push("status='problem' AND last_seen_at >= $2 AND sampled_at_ms BETWEEN $3 AND $4 AND status_sampled_at_ms BETWEEN $3 AND $4"); }
    if (before) { values.push(before); where.push(`id < $${values.length}`); }
    if (query) { values.push(`%${query.replace(/[\\%_]/g, "\\$&")}%`); where.push(`(installation_id::text || ' ' || device::text || ' ' || sessions::text) ILIKE $${values.length}`); }
    const { rows } = await this.pool.query(`SELECT * FROM support_playback_devices WHERE ${where.join(" AND ")} ORDER BY id DESC LIMIT 31`, values);
    const summary = (await this.pool.query(`SELECT count(*)::int AS devices,
      count(*) FILTER(WHERE last_seen_at >= $2)::int AS online,
      count(*) FILTER(WHERE status='problem' AND last_seen_at >= $2 AND sampled_at_ms BETWEEN $3 AND $4 AND status_sampled_at_ms BETWEEN $3 AND $4)::int AS problems
      FROM support_playback_devices WHERE last_seen_at >= $1`, bounds)).rows[0];
    const page = rows.slice(0, 30);
    return { items: page.map(row => presentDevice(row, now)), nextCursor: rows.length > 30 ? String(page.at(-1).id) : null, summary, serverTime: new Date(now).toISOString() };
  }
  async playbackDetail(installationId, now = Date.now()) {
    const row = (await this.pool.query("SELECT * FROM support_playback_devices WHERE installation_id=$1 AND last_seen_at >= $2", [installationId, new Date(now - RETENTION_MS)])).rows[0];
    if (!row) return null;
    const { rows } = await this.pool.query("SELECT sample_id,sampled_at_ms,received_at,payload FROM support_playback_samples WHERE installation_id=$1 AND received_at >= $2 ORDER BY id DESC LIMIT 101", [installationId, new Date(now - RETENTION_MS)]);
    const timeline = rows.slice(0, 100).map(sample => ({ sampleId: sample.sample_id, sampledAt: new Date(Number(sample.sampled_at_ms)).toISOString(), receivedAt: new Date(sample.received_at).toISOString(), device: sample.payload.device, sessions: sample.payload.sessions, diagnosis: diagnose(sample.payload.device, sample.payload.sessions, true) }));
    const incidentRows = (await this.pool.query("SELECT payload,received_at FROM support_playback_incidents WHERE installation_id=$1 AND received_at >= $2 ORDER BY id DESC LIMIT 31", [installationId, new Date(now - RETENTION_MS)])).rows;
    const interventionRows = (await this.pool.query("SELECT * FROM support_playback_interventions WHERE installation_id=$1 AND created_at >= $2 ORDER BY id DESC LIMIT 11", [installationId, new Date(now - RETENTION_MS)])).rows;
    const interventions = [];
    for (const intervention of interventionRows.slice(0, 10)) interventions.push(await this.presentIntervention(intervention, now));
    return { device: presentDevice(row, now), timeline, timelineTruncated: rows.length > 100, historyLimited: row.history_limited, historyRetentionDays: 7,
      incidents: incidentRows.slice(0, 30).map(value => ({ ...value.payload, receivedAt: new Date(value.received_at).toISOString() })), incidentsTruncated: incidentRows.length > 30,
      interventions, interventionsTruncated: interventionRows.length > 10, serverTime: new Date(now).toISOString() };
  }

  async trimObservations(client, table, timestamp, installationId, globalCap, deviceCap, now) {
    // Names come only from the two fixed internal callers, never request input.
    if (!new Set(["support_playback_incidents:received_at", "support_playback_interventions:created_at"]).has(`${table}:${timestamp}`)) throw Error("Invalid observation table");
    await client.query(`DELETE FROM ${table} WHERE ${timestamp} < $1`, [new Date(now - RETENTION_MS)]);
    const { rows } = await client.query(`SELECT count(*)::int AS total,count(*) FILTER(WHERE installation_id=$1)::int AS device FROM ${table}`, [installationId]);
    if (rows[0].device >= deviceCap) await client.query(`WITH removed AS (DELETE FROM ${table} WHERE id IN (SELECT id FROM ${table} WHERE installation_id=$1 ORDER BY id ASC LIMIT $2) RETURNING installation_id) UPDATE support_playback_devices SET history_limited=true WHERE installation_id IN (SELECT installation_id FROM removed)`, [installationId, rows[0].device - deviceCap + 1]);
    else if (rows[0].total >= globalCap) await client.query(`WITH removed AS (DELETE FROM ${table} WHERE id IN (SELECT id FROM ${table} ORDER BY id ASC LIMIT $1) RETURNING installation_id) UPDATE support_playback_devices SET history_limited=true WHERE installation_id IN (SELECT installation_id FROM removed)`, [rows[0].total - globalCap + 1]);
  }

  async playbackChannels({ query, before }, now = Date.now()) {
    const values = [new Date(now - FRESH_MS), now - FRESH_MS, now + FRESH_MS];
    const filters = ["content_key ~ '^[a-f0-9]{64}$'"];
    let groupFilter = "";
    if (before) { values.push(before); filters.push(`content_key > $${values.length}`); }
    if (query) { values.push(`%${query.replace(/[\\%_]/g, "\\$&")}%`); groupFilter = `HAVING bool_or((content_key || ' ' || label) ILIKE $${values.length})`; }
    const { rows } = await this.pool.query(`WITH current AS (
      SELECT d.installation_id,d.device,d.last_seen_at,s.value,
        CASE WHEN d.status_sampled_at_ms BETWEEN $2 AND $3 THEN d.status ELSE 'unknown' END AS status
      FROM support_playback_devices d CROSS JOIN LATERAL (
        SELECT value FROM jsonb_array_elements(d.sessions) ORDER BY (value->>'started_at_epoch_ms')::bigint DESC NULLS LAST LIMIT 1
      ) s WHERE d.last_seen_at >= $1 AND d.sampled_at_ms BETWEEN $2 AND $3
        AND (s.value->>'sampledAt')::timestamptz >= $1
        AND s.value->>'content_kind' IN ('LIVE_TV','RADIO') AND s.value->>'state' IN ('PLAYING','BUFFERING','STARTING','FAILED')
    ), selected AS (
      SELECT *,value->>'content_key' AS content_key,COALESCE(NULLIF(value->>'content_label',''),'İsimsiz kanal') AS label FROM current
    ), ranked AS (
      SELECT *,row_number() OVER(PARTITION BY content_key ORDER BY last_seen_at DESC,installation_id) AS rank FROM selected WHERE ${filters.join(" AND ")}
    ) SELECT content_key AS "contentKey",min(label) AS label,count(*)::int AS devices,
      count(*) FILTER(WHERE status='problem')::int AS "problemDevices",count(*) FILTER(WHERE status='healthy')::int AS "measuredDevices",
      count(*) FILTER(WHERE status='unknown')::int AS "unknownDevices",
      jsonb_agg(jsonb_build_object('installationId',installation_id,'deviceCode',installation_id,'status',status,'model',device->>'model','lastSeenAt',last_seen_at) ORDER BY rank) FILTER(WHERE rank <= 20) AS installations,
      count(*) > 20 AS "installationsTruncated"
      FROM ranked GROUP BY content_key ${groupFilter} ORDER BY content_key ASC LIMIT 31`, values);
    const page = rows.slice(0, 30).map(compareChannelGroup);
    return { items: page, nextCursor: rows.length > 30 ? page.at(-1).contentKey : null, serverTime: new Date(now).toISOString() };
  }

  async interventionPoints(client, installationId, contentKey, start, end) {
    const { rows } = await client.query("SELECT sampled_at_ms,received_at,payload FROM support_playback_samples WHERE installation_id=$1 AND sampled_at_ms BETWEEN $2 AND $3 ORDER BY sampled_at_ms ASC,id ASC LIMIT 100", [installationId, start, end]);
    return measurementPoints(rows, contentKey);
  }
  async createIntervention(installationId, marker, actor, now = Date.now()) {
    const row = await this.transaction(async client => {
      const existing = (await client.query("SELECT * FROM support_playback_interventions WHERE installation_id=$1 AND request_id=$2", [installationId, marker.requestId])).rows[0];
      if (existing) return existing;
      const device = (await client.query("SELECT sessions FROM support_playback_devices WHERE installation_id=$1 AND last_seen_at >= $2", [installationId, new Date(now - RETENTION_MS)])).rows[0];
      if (!device) return null;
      const baseline = await this.interventionPoints(client, installationId, marker.contentKey, now - WINDOW_MS, now);
      if (!baseline.length && !device.sessions.some(session => session.content_key === marker.contentKey)) return null;
      await this.trimObservations(client, "support_playback_interventions", "created_at", installationId, this.caps.interventions, this.caps.interventionsPerDevice, now);
      return (await client.query(`INSERT INTO support_playback_interventions(installation_id,request_id,content_key,note,actor,created_at,baseline) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING *`, [installationId, marker.requestId, marker.contentKey, marker.note, actor, new Date(now), JSON.stringify(baseline)])).rows[0];
    });
    return row ? this.presentIntervention(row, now) : null;
  }
  async presentIntervention(row, now) {
    const created = new Date(row.created_at).getTime();
    let comparison = row.result;
    if (!comparison) {
      const after = await this.interventionPoints(this.pool, row.installation_id, row.content_key, created, created + WINDOW_MS);
      comparison = compareIntervention(row.baseline, after, created, now);
      // Keep final evidence even if ordinary heartbeat history is later trimmed.
      if (now >= created + WINDOW_MS + 30000) await this.pool.query("UPDATE support_playback_interventions SET result=$2 WHERE id=$1 AND result IS NULL", [row.id, comparison]);
    }
    return { id: String(row.id), requestId: row.request_id, contentKey: row.content_key, note: redact(row.note), createdAt: new Date(created).toISOString(), comparison };
  }
}
module.exports = { PgStore };
