"use strict";
const { UUID, redact } = require("./security");
const { incidentPayload } = require("./playback-analysis");

const RETENTION_MS = 7 * 86400000;
const FRESH_MS = 120000;
// The client refreshes an idle card only every five minutes, so a snapshot without an unfinished session
// stays fresh for six minutes; active playback keeps reporting every 30 seconds and the two-minute window.
const IDLE_FRESH_MS = 360000;
const freshWindowMs = sessions => (sessions || []).some(session => session?.final === false) ? FRESH_MS : IDLE_FRESH_MS;
const MAX_BYTES = 32768;
const states = new Set(["STARTING", "PLAYING", "BUFFERING", "PAUSED", "ENDED", "FAILED"]);
const sets = values => new Set(values.split(" "));
const enums = {
  content_kind: sets("LIVE_TV RADIO VOD_MOVIE VOD_EPISODE CATCH_UP"),
  initial_engine: sets("EXO_PLAYER VLC UNKNOWN"), final_engine: sets("EXO_PLAYER VLC UNKNOWN"),
  transport: sets("HLS MPEG_TS DASH PROGRESSIVE UNKNOWN"),
  video_codec: sets("H264 H265 MPEG2 MPEG4 VP8 VP9 AV1 OTHER UNKNOWN"),
  video_decoder: sets("HARDWARE SOFTWARE UNKNOWN"),
  source_timing_scope: sets("INITIAL_ATTEMPT"),
  end_reason: sets("USER_STOP COMPLETED REPLACED BACKGROUND FATAL_FAILURE APP_SHUTDOWN ABANDONED"), state: states,
};
const lists = {
  failure_codes: sets("NETWORK_UNAVAILABLE DNS_LOOKUP_FAILED CONNECTION_FAILED CONNECTION_RESET READ_TIMEOUT END_OF_STREAM TLS_FAILED HTTP_UNAUTHORIZED HTTP_FORBIDDEN HTTP_NOT_FOUND HTTP_REQUEST_TIMEOUT HTTP_RATE_LIMITED HTTP_CLIENT_ERROR HTTP_SERVER_ERROR SOURCE_MALFORMED SOURCE_UNSUPPORTED CODEC_UNSUPPORTED DECODER_INIT_FAILED DECODER_RUNTIME_FAILED DECODER_RESOURCES_RECLAIMED AUDIO_SINK_FAILED AUDIO_STALL VIDEO_OUTPUT_FAILED DRM_PROVISIONING_FAILED DRM_LICENSE_FAILED DRM_CONTENT_RESTRICTED STARTUP_TIMEOUT PLAYBACK_STALL SEEK_TIMEOUT OUT_OF_MEMORY RESOURCE_EXHAUSTED CANCELLED UNKNOWN"),
  failure_categories: sets("NETWORK AUTHORIZATION SOURCE FORMAT DECODER OUTPUT DRM TIMEOUT RESOURCE CANCELLED UNKNOWN"),
  failure_phases: sets("RESOLVE CONNECT OPEN_SOURCE STARTUP PLAYBACK SEEK TRACK_SELECTION SHUTDOWN UNKNOWN"),
  failure_components: sets("TRANSPORT MANIFEST CONTAINER VIDEO AUDIO SUBTITLE DRM PLAYER UNKNOWN"),
  failure_retry_advice: sets("WAIT_FOR_NETWORK RETRY_SAME_ROUTE TRY_ALTERNATE_TRANSPORT TRY_ALTERNATE_DECODER TRY_ALTERNATE_ENGINE DO_NOT_RETRY UNKNOWN"),
  audio_failure_codecs: sets("AC3 E_AC3 AAC MPEG_AUDIO OTHER UNKNOWN"),
  audio_failure_decoders: sets("HARDWARE SOFTWARE UNKNOWN"),
  audio_failure_sink_events: sets("CLOCK_STALL UNDERRUN SINK_ERROR CODEC_ERROR"),
  audio_failure_output_modes: sets("PCM PASSTHROUGH"),
};
const numbers = sets("started_at_epoch_ms ended_at_epoch_ms session_duration_ms time_to_ready_ms time_to_first_frame_ms rebuffer_count rebuffer_duration_ms engine_switch_count rendered_frames dropped_frames discarded_failure_count state_duration_ms last_frame_age_ms current_buffer_ms paused_duration_ms");
const sourceTimings = new Set(["source_open_ms", "time_to_first_byte_ms", "first_byte_to_first_frame_ms"]);
const sessionKeys = new Set(["schema", "session_id", "capability_fingerprint", "final", "frames_known", "content_label", "content_key", "failure_http_statuses", "video_width", "video_height", "frame_rate", ...sourceTimings, ...Object.keys(enums), ...Object.keys(lists), ...numbers]);
const deviceStrings = { manufacturer: 64, model: 96, androidVersion: 32, appVersion: 32 };
const deviceNumbers = { apiLevel: 1000, versionCode: 2147483647, availableMemoryMb: 1048576, totalMemoryMb: 1048576 };
const deviceBooleans = ["networkConnected", "metered", "lowMemory", "powerSave"];
const deviceKeys = new Set([...Object.keys(deviceStrings), ...Object.keys(deviceNumbers), ...deviceBooleans, "networkType"]);
const object = value => value && typeof value === "object" && !Array.isArray(value);
const integer = (value, max = Number.MAX_SAFE_INTEGER) => Number.isSafeInteger(value) && value >= 0 && value <= max;
const closed = (value, keys) => object(value) && Object.keys(value).every(key => keys.has(key));
// Labels are optional display hints. Drop URLs, credentials, e-mail addresses and control characters.
function safeLabel(value, max) {
  return redact(value).replace(/\b[a-z][a-z0-9+.-]*:\/\/\S+/gi, "[adres gizlendi]")
    .replace(/\b[^\s@]+@[^\s@]+\.[^\s@]+\b/g, "[gizlendi]")
    .replace(/\b(?:username|user|password|pass)\s*[:=]\s*[^\s,;]+/gi, "[gizlendi]")
    .replace(/[\r\n\t]+/g, " ").trim().slice(0, max);
}
function playbackPayload(input, now = Date.now()) {
  if (!closed(input, new Set(["schema", "sampleId", "sampledAtMs", "device", "sessions", "incidents"])) || input.schema !== 1 || typeof input.sampleId !== "string" || !UUID.test(input.sampleId)) return null;
  if (Buffer.byteLength(JSON.stringify(input)) > MAX_BYTES || !integer(input.sampledAtMs) || input.sampledAtMs < now - RETENTION_MS || input.sampledAtMs > now + 300000) return null;
  if (!closed(input.device, deviceKeys) || !Array.isArray(input.sessions) || input.sessions.length > 16) return null;
  const device = {};
  for (const [key, value] of Object.entries(input.device)) {
    if (deviceStrings[key]) { if (typeof value !== "string" || value.length > deviceStrings[key]) return null; device[key] = safeLabel(value, deviceStrings[key]); }
    else if (key in deviceNumbers) { if (!integer(value, deviceNumbers[key])) return null; device[key] = value; }
    else if (deviceBooleans.includes(key)) { if (typeof value !== "boolean") return null; device[key] = value; }
    else if (key === "networkType") { if (!sets("WIFI ETHERNET CELLULAR OTHER NONE UNKNOWN").has(value)) return null; device[key] = value; }
  }
  const ids = new Set(); const sessions = [];
  for (const session of input.sessions) {
    if (!closed(session, sessionKeys) || session.schema !== 1 || typeof session.session_id !== "string" || !UUID.test(session.session_id) || ids.has(session.session_id.toLowerCase())) return null;
    if (!enums.content_kind.has(session.content_kind) || !states.has(session.state) || !integer(session.session_duration_ms) || !integer(session.state_duration_ms) || typeof session.final !== "boolean" || typeof session.frames_known !== "boolean") return null;
    ids.add(session.session_id.toLowerCase()); const row = {};
    for (const [key, value] of Object.entries(session)) {
      if (numbers.has(key)) { if (!integer(value)) return null; row[key] = value; }
      else if (sourceTimings.has(key)) { if (!integer(value, 86400000)) return null; row[key] = value; }
      else if (key === "video_width" || key === "video_height") { if (!integer(value, 16384) || value < 1) return null; row[key] = value; }
      else if (key === "frame_rate") { if (typeof value !== "number" || !Number.isFinite(value) || value < 1 || value > 240) return null; row[key] = value; }
      else if (enums[key]) { if (!enums[key].has(value)) return null; row[key] = value; }
      else if (lists[key]) { if (typeof value !== "string" || value.length > 1600 || (value && (value.split(",").length > 50 || value.split(",").some(token => !lists[key].has(token))))) return null; row[key] = value; }
      else if (key === "failure_http_statuses") { if (typeof value !== "string" || value.split(",").length > 50 || value.split(",").some(token => token && !/^[45][0-9]{2}$/.test(token))) return null; row[key] = value; }
      else if (key === "content_label") { if (typeof value !== "string" || value.length > 160) return null; row[key] = safeLabel(value, 160); }
      else if (key === "content_key" || key === "capability_fingerprint") { if (typeof value !== "string" || !(key === "capability_fingerprint" ? /^(?:cap-v1-)?[a-f0-9]{64}$/i : /^[a-f0-9]{64}$/i).test(value)) return null; row[key] = value.toLowerCase(); }
      else if (key === "final" || key === "frames_known") { if (typeof value !== "boolean") return null; row[key] = value; }
      else row[key] = value;
    }
    if (["started_at_epoch_ms", "ended_at_epoch_ms"].some(key => row[key] !== undefined && row[key] > input.sampledAtMs + 300000)) return null;
    if ([...sourceTimings].some(key => row[key] !== undefined) && row.source_timing_scope !== "INITIAL_ATTEMPT") return null;
    row.session_id = row.session_id.toLowerCase(); sessions.push(row);
  }
  let incidents;
  if (input.incidents !== undefined) {
    if (!Array.isArray(input.incidents) || input.incidents.length > 1) return null;
    incidents = input.incidents.map(value => incidentPayload(value, input.sampledAtMs));
    if (incidents.some(value => !value)) return null;
  }
  return { schema: 1, sampleId: input.sampleId.toLowerCase(), sampledAtMs: input.sampledAtMs, device, sessions, ...(incidents === undefined ? {} : { incidents }) };
}

function diagnose(info, sessions, fresh = true) {
  const evidence = []; const recommendations = []; let confidence = "low";
  const add = (proof, advice, certainty = "medium") => { evidence.push(proof); if (advice && !recommendations.includes(advice)) recommendations.push(advice); if (certainty === "high" || confidence === "low") confidence = certainty; };
  const current = [...sessions].sort((a, b) => (b.started_at_epoch_ms || 0) - (a.started_at_epoch_ms || 0))[0];
  if (!fresh) return { status: "unknown", headline: "Güncel ölçüm yok", confidence: "low", evidence: ["Son ölçüm eski veya cihaz saati sunucuyla uyumsuz. Şu anki oynatma durumu bilinmiyor."], recommendations: ["Cihazın uygulamayı açmasını ve bağlantı kurmasını bekleyin; cihaz tarih/saatini kontrol edin."] };
  if (info.networkConnected === false) add("Cihaz ağ bağlantısı olmadığını bildiriyor.", "Cihazın ağ bağlantısını ve modem erişimini kontrol edin.", "high");
  if (info.lowMemory === true) add(`Cihaz düşük bellek bildiriyor${info.availableMemoryMb === undefined ? "." : ` (${info.availableMemoryMb} MB kullanılabilir).`}`, "Arka plandaki uygulamaları kapatın ve bellek ölçümünü tekrar karşılaştırın.", "high");
  if (current) {
    const seconds = ms => Math.round(ms / 1000);
    if (current.state === "STARTING" && current.state_duration_ms >= 15000) add(`İçerik ${seconds(current.state_duration_ms)} saniyedir açılış aşamasında; ilk görüntü henüz doğrulanmadı.`, "Aynı içeriğin başka cihazdaki açılışını ve kaynak yanıtını karşılaştırın.");
    if (current.state === "BUFFERING" && current.state_duration_ms >= 5000) add(`Oynatma ${seconds(current.state_duration_ms)} saniyedir veri bekliyor.`, "Aynı kanalı aynı anda başka cihazda karşılaştırın; cihaz ağı ve yayın kaynağını ayrı ayrı kontrol edin.", "high");
    if (current.state === "PLAYING" && current.frames_known && current.content_kind !== "RADIO" && current.last_frame_age_ms >= 8000) add(`Oynatıcı çalışıyor bildirirken ${seconds(current.last_frame_age_ms)} saniyedir yeni video karesi gözlenmedi.`, "Video çözücü/çıkış yolunu ve tampon seviyesini inceleyin; bu ölçüm tek başına panel arızasını kanıtlamaz.");
    if (current.frames_known && current.rendered_frames >= 100 && current.dropped_frames / (current.rendered_frames + current.dropped_frames) >= .1) add(`${current.dropped_frames} video karesi düşürüldü (${current.rendered_frames} görüntülenen kare).`, "Cihaz çözücüsünü, yayın çözünürlüğünü ve kaynak/bellek baskısını karşılaştırın.");
    const codes = (current.failure_codes || "").split(",").filter(Boolean);
    if (["FAILED", "STARTING", "BUFFERING"].includes(current.state) && codes.length) {
      const relevant = [...new Set(codes)].slice(-8); const http = [...new Set((current.failure_http_statuses || "").split(",").filter(Boolean))];
      add(`Oynatıcı hata kodları: ${relevant.join(", ")}${http.length ? `; HTTP ${http.join(", ")}` : ""}.`, "Hata anını zaman çizelgesiyle karşılaştırın; ağ, yetki ve kaynak hataları birbirinden ayrılmalıdır.", "high");
      if (codes.some(code => /DECODER|VIDEO_OUTPUT|AUDIO_SINK|AUDIO_STALL/.test(code))) recommendations.push("Çözücü/ses-görüntü çıkışı hatası var; cihaz ve oynatıcı uyumluluğunu kontrol edin.");
      if (codes.some(code => /DNS|CONNECTION|NETWORK|TIMEOUT|TLS/.test(code))) recommendations.push("İstemci ile yayın sunucusu arasındaki bağlantıyı inceleyin; Wi-Fi veya panel kesin neden olarak henüz belirlenemez.");
      if (http.length) recommendations.push("Yayın sunucusunun HTTP yanıtını, hesap yetkisini ve eşzamanlı bağlantı sınırını kontrol edin.");
    } else if (current.state === "FAILED") add("Oynatıcı başarısız durumunda; ayrıntılı hata kodu yok.", "Kullanıcıdan yeniden denemesini isteyin ve yeni hata ölçümünü inceleyin.");
  }
  if (evidence.length) return { status: "problem", headline: "Oynatma sorunu ölçüldü", confidence, evidence, recommendations };
  if (!current || current.final || ["PAUSED", "ENDED"].includes(current.state)) return { status: "unknown", headline: "Etkin oynatma yok", confidence: "high", evidence: ["Cihaz erişilebilir; devam eden bir oynatma doğrulanmıyor."], recommendations: [] };
  if (current.state !== "PLAYING") return { status: "unknown", headline: "Oynatma bekleniyor", confidence: "low", evidence: ["Açılış veya kısa süreli tamponlama devam ediyor."], recommendations: [] };
  if (current.content_kind !== "RADIO" && (!current.frames_known || current.last_frame_age_ms === undefined || !(current.rendered_frames > 0))) return { status: "unknown", headline: "Görüntü akıcılığı doğrulanamadı", confidence: "low", evidence: ["Oynatıcı çalışıyor bildiriyor; güncel video kare ölçümü bulunmuyor."], recommendations: [] };
  return { status: "healthy", headline: "Son ölçümde sorun saptanmadı", confidence: "medium", evidence: ["Oynatıcı çalışıyor; mevcut ölçümlerde sorun eşiği aşılmadı."], recommendations: [] };
}

function presentDevice(row, now = Date.now()) {
  const lastSeen = new Date(row.last_seen_at).getTime(); const sampled = Number(row.sampled_at_ms);
  const info = row.device; const sessions = row.sessions || [];
  const freshMs = freshWindowMs(sessions);
  const online = now - lastSeen <= freshMs;
  const sampleFresh = sampled <= now + freshMs && now - sampled <= freshMs;
  const currentSessions = sessions.filter(session => !session.sampledAt || Math.abs(now - Date.parse(session.sampledAt)) <= freshMs);
  const diagnosis = diagnose(info, currentSessions, online && sampleFresh);
  return { installationId: row.installation_id, deviceCode: row.installation_id, lastSeenAt: new Date(lastSeen).toISOString(), sampledAt: new Date(sampled).toISOString(), info, sessions, online, freshWindowMs: freshMs, status: diagnosis.status, diagnosis };
}
module.exports = { RETENTION_MS, FRESH_MS, IDLE_FRESH_MS, MAX_BYTES, freshWindowMs, playbackPayload, diagnose, presentDevice };
