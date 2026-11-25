-- Field numbers of one app version from the support desk database. Read-only.
-- Run it the day after a release and compare with the previous version:
--
--   ssh root@<support desk> 'cd /tmp && sudo -u postgres psql -d kululu_support -q -v ver=1.6.6' \
--     < support-desk/field-report.sql
--
-- support_playback_samples keeps about the last day of the fleet, one snapshot
-- per device every few seconds; the CTE keeps the last snapshot of each session.
-- Compare versions per device model or per channel only: the mix of devices and
-- channels differs between versions. Never print installation ids.
SET default_transaction_read_only = on;
\pset format unaligned
\pset tuples_only on

-- The session is read-only, so no view or table can be made: the session list
-- is a psql variable that every query below starts with (quotes are doubled).
\set s 'WITH s AS MATERIALIZED (SELECT DISTINCT ON (installation_id, sess->>''session_id'') installation_id AS iid, payload->''device''->>''appVersion'' AS v, payload->''device''->>''manufacturer'' AS man, payload->''device''->>''model'' AS model, sess->>''content_kind'' AS kind, sess->>''content_label'' AS label, sess->>''content_key'' AS ckey, (sess->>''final'')::boolean AS fin, sess->>''end_reason'' AS end_reason, sess->>''final_engine'' AS e1, coalesce((sess->>''engine_switch_count'')::int, 0) AS sw, (sess->>''session_duration_ms'')::bigint AS dur, (sess->>''time_to_first_frame_ms'')::bigint AS ttff, coalesce((sess->>''rebuffer_count'')::int, 0) AS rb, coalesce((sess->>''rebuffer_duration_ms'')::bigint, 0) AS rbd, coalesce(sess->>''failure_codes'', '''') AS fcodes, coalesce((sess->>''dropped_frames'')::bigint, 0) AS dropped, coalesce((sess->>''rendered_frames'')::bigint, 0) AS rendered FROM support_playback_samples, jsonb_array_elements(payload->''sessions'') sess ORDER BY installation_id, sess->>''session_id'', sampled_at_ms DESC)'

SELECT '== sessions by version and kind: sessions, devices, hours';
:s
SELECT v, kind, count(*), count(DISTINCT iid), round(sum(dur) FILTER (WHERE fin) / 3600000.0, 1)
FROM s GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 12;

SELECT '== ' || :'ver' || ' by kind: sessions, start p50 ms, start p90 ms, fatal, no picture, engine switched, rebuffers per hour';
:s
SELECT kind, count(*),
       percentile_disc(0.5) WITHIN GROUP (ORDER BY ttff),
       percentile_disc(0.9) WITHIN GROUP (ORDER BY ttff),
       count(*) FILTER (WHERE end_reason = 'FATAL_FAILURE'),
       count(*) FILTER (WHERE ttff IS NULL),
       count(*) FILTER (WHERE sw > 0),
       round(sum(rb) / nullif(sum(dur) / 3600000.0, 0), 2)
FROM s WHERE v = :'ver' AND fin GROUP BY 1 ORDER BY 2 DESC;

SELECT '== ' || :'ver' || ' live by device model: sessions, devices, final libVLC pct, start p50 ms, rebuffers per hour, dropped frame pct';
:s
SELECT man, model, count(*), count(DISTINCT iid),
       round(100.0 * count(*) FILTER (WHERE e1 = 'VLC') / nullif(count(*) FILTER (WHERE e1 <> 'UNKNOWN'), 0)),
       percentile_disc(0.5) WITHIN GROUP (ORDER BY ttff),
       round(sum(rb) / nullif(sum(dur) / 3600000.0, 0), 2),
       round(100.0 * sum(dropped) / nullif(sum(dropped + rendered), 0), 1)
FROM s WHERE v = :'ver' AND kind = 'LIVE_TV' AND fin GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 15;

SELECT '== ' || :'ver' || ' failure codes (once per session) by kind';
:s
SELECT kind, code, count(*)
FROM (SELECT kind, unnest(ARRAY(SELECT DISTINCT x FROM unnest(string_to_array(fcodes, ',')) x WHERE x <> '')) AS code
      FROM s WHERE v = :'ver') t
GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 15;

SELECT '== channels that fail to connect, all versions: name, tries, with picture, devices';
:s
SELECT max(label), count(*), count(*) FILTER (WHERE ttff IS NOT NULL), count(DISTINCT iid)
FROM s WHERE kind = 'LIVE_TV' GROUP BY ckey
HAVING count(*) FILTER (WHERE fcodes LIKE '%CONNECTION_FAILED%') >= 5
ORDER BY count(*) FILTER (WHERE fcodes LIKE '%CONNECTION_FAILED%') DESC LIMIT 15;

SELECT '== automatic crash reports of the last 7 days by kind (first line of the message)';
SELECT split_part(message, E'\n', 1), count(*), count(DISTINCT installation_id)
FROM support_tickets
WHERE type = 'diagnostic' AND message LIKE 'Otomatik rapor%' AND created_at > now() - interval '7 days'
GROUP BY 1 ORDER BY 2 DESC;
