"use strict";
const { UUID, redact } = require("./security");
const CONTENT_KEY = /^[a-f0-9]{64}$/i;
const WINDOW_MS = 120000;
const states = new Set(["STARTING", "PLAYING", "BUFFERING", "PAUSED", "ENDED", "FAILED"]);
const triggers = new Set(["STARTUP_SLOW", "BUFFERING", "VIDEO_STALL", "PLAYBACK_ERROR", "USER_REPORT"]);
const integer = value => Number.isSafeInteger(value) && value >= 0;
const closed = (object, keys) => object && typeof object === "object" && !Array.isArray(object) && Object.keys(object).every(key => keys.includes(key));

function incidentPayload(input, sampledAtMs) {
  if (!closed(input, ["incidentId", "sessionId", "contentKey", "trigger", "triggeredAtMs", "complete", "truncated", "points"])) return null;
  if (typeof input.incidentId !== "string" || !UUID.test(input.incidentId) || typeof input.sessionId !== "string" || !UUID.test(input.sessionId)) return null;
  if (!triggers.has(input.trigger) || !integer(input.triggeredAtMs) || input.triggeredAtMs > sampledAtMs + 300000 || input.triggeredAtMs < sampledAtMs - 7 * 86400000 || typeof input.complete !== "boolean") return null;
  if (input.truncated !== undefined && typeof input.truncated !== "boolean") return null;
  if (input.contentKey !== undefined && (typeof input.contentKey !== "string" || !CONTENT_KEY.test(input.contentKey))) return null;
  if (!Array.isArray(input.points) || input.points.length < 1 || input.points.length > 31) return null;
  const mandatory = ["stateDurationMs", "sessionDurationMs", "rebufferCount", "rebufferDurationMs"];
  const optional = ["renderedFrames", "droppedFrames", "lastFrameAgeMs", "currentBufferMs"];
  let offset = -30001, elapsed = -1;
  for (const point of input.points) {
    if (!closed(point, ["offsetMs", "state", "framesKnown", ...mandatory, ...optional])) return null;
    if (!Number.isInteger(point.offsetMs) || point.offsetMs < -30000 || point.offsetMs > 30000 || point.offsetMs <= offset || !states.has(point.state) || typeof point.framesKnown !== "boolean") return null;
    if (mandatory.some(key => !integer(point[key])) || optional.some(key => point[key] !== undefined && !integer(point[key]))) return null;
    if (point.sessionDurationMs < elapsed || point.stateDurationMs > point.sessionDurationMs + 1000 || point.rebufferDurationMs > point.sessionDurationMs + 1000) return null;
    offset = point.offsetMs; elapsed = point.sessionDurationMs;
  }
  return { ...input, incidentId: input.incidentId.toLowerCase(), sessionId: input.sessionId.toLowerCase(), ...(input.contentKey ? { contentKey: input.contentKey.toLowerCase() } : {}), points: input.points.map(point => ({ ...point })) };
}

// Incident identity is immutable. Clients may advance a two-second sample bucket;
// merge actual observations while preserving older evidence and a fixed point cap.
function mergeIncident(previous, incoming, previousSampledAtMs, sampledAtMs) {
  if (!previous) return incoming;
  if (["sessionId", "contentKey", "trigger", "triggeredAtMs"].some(key => previous[key] !== incoming[key])) return previous;
  // A trigger and its immediate pause/end flush can share the same clock millisecond.
  // Only completion may enrich that instant; normal snapshots still need newer time.
  if (sampledAtMs < previousSampledAtMs || (sampledAtMs === previousSampledAtMs && !incoming.complete) || previous.complete || incoming.points.at(-1).offsetMs < previous.points.at(-1).offsetMs) return previous;
  const points = new Map(previous.points.map(point => [point.offsetMs, point]));
  for (const point of incoming.points) {
    const old = points.get(point.offsetMs);
    if (old && ["sessionDurationMs", "rebufferCount", "rebufferDurationMs"].some(key => point[key] < old[key])) return previous;
    points.set(point.offsetMs, point);
  }
  let merged = [...points.values()].sort((a, b) => a.offsetMs - b.offsetMs);
  if (merged.some((point, index) => index && ["sessionDurationMs", "rebufferCount", "rebufferDurationMs"].some(key => point[key] < merged[index - 1][key]))) return previous;
  const thinned = merged.length > 31;
  if (thinned) {
    const chosen = new Set([0, merged.length - 1, merged.reduce((best, point, index) => Math.abs(point.offsetMs) < Math.abs(merged[best].offsetMs) ? index : best, 0)]);
    for (let i = 0; i < 28; i++) chosen.add(Math.round(i * (merged.length - 1) / 27));
    merged = [...chosen].sort((a, b) => a - b).map(index => merged[index]);
  }
  return { ...incoming, points: merged, ...(thinned || previous.truncated || incoming.truncated ? { truncated: true } : {}) };
}

function interventionPayload(input) {
  if (!closed(input, ["requestId", "note", "contentKey"]) || typeof input.requestId !== "string" || !UUID.test(input.requestId) || typeof input.contentKey !== "string" || !CONTENT_KEY.test(input.contentKey) || typeof input.note !== "string" || !input.note.trim() || input.note.length > 300) return null;
  return { requestId: input.requestId.toLowerCase(), contentKey: input.contentKey.toLowerCase(), note: redact(input.note.trim()).replace(/\b[a-z][a-z0-9+.-]*:\/\/\S+/gi, "[adres gizlendi]").slice(0, 300) };
}

function compareChannelGroup(group) {
  const devices = Number(group.devices), problems = Number(group.problemDevices), healthy = Number(group.measuredDevices);
  let status = "insufficient";
  if (problems >= 2) status = "shared_problem";
  else if (problems >= 1 && healthy >= 1) status = "mixed";
  else if (healthy >= 2 && !problems) status = "no_problem_measured";
  const evidence = [`Aynı içerik için son iki dakika içinde ${devices} cihazdan ölçüm var; ${problems} cihazda sorun, ${healthy} cihazda ölçülebilen oynatma var.`];
  if (status === "shared_problem") evidence.push("Sorun birden fazla cihazda aynı zaman aralığında gözlendi. Ortak yayın/bağlantı yolunu inceleyin; bu karşılaştırma panelin arızalı olduğunu tek başına kanıtlamaz.");
  else if (status === "mixed") evidence.push("Aynı içerikte farklı sonuçlar var. Etkilenen cihazın ağı, çözücüsü ve hesap/yayın rotasını karşılaştırın; cihazın kesin neden olduğu söylenemez.");
  else if (status === "no_problem_measured") evidence.push("Karşılaştırılabilen cihazlarda son ölçümde sorun saptanmadı; başka kullanıcının yaşadığı kesinti dışlanamaz.");
  else evidence.push("Neden karşılaştırması için en az iki cihazdan yeterli ve güncel oynatma kanıtı gerekiyor.");
  return { ...group, devices, problemDevices: problems, measuredDevices: healthy, unknownDevices: Number(group.unknownDevices), status, confidence: status === "insufficient" ? "low" : "medium", evidence };
}

function measurementPoints(samples, contentKey) {
  return samples.flatMap(sample => (sample.payload?.sessions || []).filter(session => session.content_key === contentKey).sort((a, b) => (b.started_at_epoch_ms || 0) - (a.started_at_epoch_ms || 0)).slice(0, 1).map(session => ({ atMs: Number(sample.sampled_at_ms), receivedAtMs: new Date(sample.received_at).getTime(), session })));
}

function windowMetrics(points, start, end) {
  const rows = points.filter(point => point.atMs >= start && point.atMs <= end).sort((a, b) => a.atMs - b.atMs);
  const metrics = { observedMs: 0, intervals: 0 };
  const excluded = { resets: 0, pauses: 0, gaps: 0, clockSkew: 0, sessionChanges: 0, unknownCounters: 0, contentChanges: 0, unknownPause: 0 };
  let rendered = 0, dropped = 0, frameIntervals = 0;
  for (let i = 1; i < rows.length; i++) {
    const previous = rows[i - 1], current = rows[i], a = previous.session, b = current.session;
    const wall = current.atMs - previous.atMs, elapsed = b.session_duration_ms - a.session_duration_ms;
    if (a.session_id !== b.session_id) { excluded.sessionChanges++; continue; }
    if (!CONTENT_KEY.test(a.content_key || "") || a.content_key !== b.content_key) { excluded.contentChanges++; continue; }
    if (![a, b].every(row => ["PLAYING", "BUFFERING"].includes(row.state) && !row.final)) { excluded.pauses++; continue; }
    if (![a, b].every(row => integer(row.paused_duration_ms))) { excluded.unknownPause++; continue; }
    if (b.paused_duration_ms < a.paused_duration_ms) { excluded.resets++; continue; }
    if (b.paused_duration_ms > a.paused_duration_ms) { excluded.pauses++; continue; }
    if (![previous, current].every(row => Number.isFinite(row.receivedAtMs) && Math.abs(row.atMs - row.receivedAtMs) <= 5000)) { excluded.clockSkew++; continue; }
    if (wall <= 0 || wall > 65000 || !Number.isSafeInteger(elapsed) || elapsed <= 0 || Math.abs(elapsed - wall) > 5000) { excluded.gaps++; continue; }
    if (![a, b].every(row => integer(row.rebuffer_count) && integer(row.rebuffer_duration_ms))) { excluded.unknownCounters++; continue; }
    const count = b.rebuffer_count - a.rebuffer_count, duration = b.rebuffer_duration_ms - a.rebuffer_duration_ms;
    if (count < 0 || duration < 0 || duration > elapsed + 1000) { excluded.resets++; continue; }
    metrics.observedMs += elapsed; metrics.intervals++; metrics.rebufferCount = (metrics.rebufferCount || 0) + count; metrics.bufferingMs = (metrics.bufferingMs || 0) + Math.min(duration, elapsed);
    if ([a, b].every(row => row.frames_known === true && integer(row.rendered_frames) && integer(row.dropped_frames))) {
      const shown = b.rendered_frames - a.rendered_frames, lost = b.dropped_frames - a.dropped_frames;
      if (shown >= 0 && lost >= 0) { rendered += shown; dropped += lost; frameIntervals++; }
    }
  }
  if (metrics.observedMs) { metrics.bufferingPercent = Math.round(metrics.bufferingMs / metrics.observedMs * 10000) / 100; metrics.rebuffersPerMinute = Math.round(metrics.rebufferCount * 6000000 / metrics.observedMs) / 100; }
  if (frameIntervals && rendered + dropped > 0) { metrics.renderedFrames = rendered; metrics.droppedFrames = dropped; metrics.droppedPercent = Math.round(dropped / (rendered + dropped) * 10000) / 100; }
  return { ...metrics, excluded };
}

function compareIntervention(beforePoints, afterPoints, createdAtMs, now = Date.now()) {
  const before = windowMetrics(beforePoints, createdAtMs - WINDOW_MS, createdAtMs);
  const after = windowMetrics(afterPoints, createdAtMs, createdAtMs + WINDOW_MS);
  const base = { before, after, windowMs: WINDOW_MS, evidence: ["Yalnızca aynı cihaz ve içerikte, aynı oturum içindeki artan sayaçların farkları karşılaştırılır. Duraklama, sayaç sıfırlanması, oturum geçişi ve saat uyumsuzluğu aralıkları dışlanır."] };
  if (now < createdAtMs + WINDOW_MS) return { ...base, status: "waiting", evidence: [...base.evidence, "Müdahaleden sonraki iki dakikalık ölçüm penceresi bekleniyor."] };
  const keys = new Set([...beforePoints, ...afterPoints].map(point => point.session?.content_key).filter(Boolean));
  if (keys.size !== 1) return { ...base, status: "insufficient", evidence: [...base.evidence, "İçerik kimlikleri eşleşmediği için karşılaştırma yapılamaz."] };
  if ([before, after].some(window => window.intervals < 2 || window.observedMs < 30000)) return { ...base, status: "insufficient", evidence: [...base.evidence, "Her iki tarafta en az iki geçerli aralık ve 30 saniyelik gözlem bulunmadığı için sonuç çıkarılamaz."] };
  const change = { bufferingPercentagePoints: Math.round((after.bufferingPercent - before.bufferingPercent) * 100) / 100, rebuffersPerMinute: Math.round((after.rebuffersPerMinute - before.rebuffersPerMinute) * 100) / 100 };
  if (before.droppedPercent !== undefined && after.droppedPercent !== undefined) change.droppedPercentagePoints = Math.round((after.droppedPercent - before.droppedPercent) * 100) / 100;
  return { ...base, status: "comparable", change, evidence: [...base.evidence, `Öncesinde ${Math.round(before.observedMs / 1000)} saniye, sonrasında ${Math.round(after.observedMs / 1000)} saniye geçerli gözlem karşılaştırıldı.`, "Ölçülen değişim, işlemin tek başına neden olduğunu veya kalıcı düzelme sağladığını kanıtlamaz."] };
}
module.exports = { CONTENT_KEY, WINDOW_MS, incidentPayload, mergeIncident, interventionPayload, compareChannelGroup, measurementPoints, windowMetrics, compareIntervention };
