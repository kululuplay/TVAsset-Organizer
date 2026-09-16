# Device evidence and measured buffering

## Behavior

Live TV/radio remain direct MPEG-TS. Movies/episodes retain their source container;
HLS is still rejected. Playback tuning neither opens a second provider connection
nor restarts a healthy stream to change a buffer or run a codec experiment.

New/unset buffer preferences default to ADAPTIVE. Explicit LOW, NORMAL and HIGH
preferences retain their duration choices. Memory pressure can still lower the
encoded-sample byte budget. The budget does not include decoder surfaces, native
allocations, artwork or the remainder of the process heap.

## Device-specific evidence

Existing engine-route memory is now hashed and scoped to firmware fingerprint,
API level, supported ABIs, actual process bitness and Media3 version. Previously
stored, unscoped route hints are ignored; account and user preferences are kept.

An additional device-local store distinguishes codec implementation and stream
format (codec/profile, dimensions, frame rate and HDR/color characteristics).
Only non-DRM, non-secure, non-tunneled hardware video on API 23–30 can try an
asynchronous MediaCodec queue. Other configurations retain platform defaults.

The trial requires three baseline windows, each with a full minute of advancing
playback, at least 600 rendered frames and at least 5% dropped frames. Low buffer,
memory pressure, pause, seek, startup and unverified output invalidate the window.
The queue changes only at the next natural codec creation. A construction error
retries the platform-default queue once and blocks the candidate for seven days.

A successful candidate needs at most 0.5% dropped frames and more than 50%
improvement over baseline in two different playback sessions. Further windows
continue to monitor degradation without counting the same session twice. A
failed candidate loses approval. Stored observations expire after seven days;
future-dated observations cannot select a queue. The store is bounded to 128
hashed records and writes off the playback/main thread. No account, title or URL
is included in these new records.

## Buffer measurements

Media3 observes inter-byte network gaps only when the stream is actually short of
buffer on both sides of the gap. Ordinary loader idle time is excluded. Transfer
generations reject callbacks from retired channels; observation generations
exclude pause/seek time even when the HTTP connection is reused.

Actual post-start rebuffers contribute a bounded duration average. Startup and
seek cooldowns, user pause, background playback and old media events are excluded.
A shared background sampler checks system low-memory state and Java heap headroom
at most every five seconds. It does not force GC or scan codecs repeatedly.

| Limit | Constrained profile | Standard profile |
| --- | ---: | ---: |
| Encoded-sample budget | 24 MiB | 48 MiB |
| Budget under memory pressure | 8 MiB | 16 MiB |
| Maximum measured restart reserve | 4 seconds | 6 seconds |
| Current measured fill range | 7.5–12 seconds | 8–18 seconds |

Loading and restart use the same byte ceiling. A high-bitrate source reaching the
ceiling may start with positive buffered media instead of waiting for a duration
it cannot hold. Empty buffers never trigger this shortcut. Explicit capable-device
VOD profiles retain Media3's track-derived target unless memory pressure intervenes.

libVLC's cache setting is immutable for an open media instance. Its measured
ADAPTIVE cache is therefore applied only on natural open/recovery and capped at
3 seconds; pressure selects 1.5 seconds. Changing this cache does not itself
reconnect. It uses actual native rebuffer durations, not invented bandwidth data.

## Verification and physical acceptance

Automated checks exercise the real Media3 load controls and allocator, transfer
callbacks, stale-source and seek/pause boundaries, byte-limit restart, explicit
preferences, codec trial/promotion/revocation, firmware/format separation and
bounded measurement history. Numerical policy thresholds are guarded defaults,
not claims of measured improvement on every TV model.

Physical validation is pending: the previously authorized stick's ADB endpoint
refuses connections, and no device is listed. No candidate APK has been installed
on it. A separate `com.iptv.player.preview` APK is used for the device trial; this
change does not publish a production release or activate a rollout.

For physical acceptance, record the device/firmware/ABI and compare the baseline
and candidate with the same content, connection and buffer preference. Measure
first frame, rebuffer count/duration, rendered/dropped frames, process memory and
the selected buffer/queue over at least five minutes per representative live
stream and VOD item. Include pause/resume, seek, rapid channel changes and return
from background. Read numeric `MeasuredBuffer` and `VerifiedCodec` diagnostics
alongside existing playback logs. Never attribute buffering to the panel or
decoder solely from the visual spinner. User-visible audio/video and hardware
surface output still require real-device observation.

Reference: Android's [TV playback memory guidance](https://developer.android.com/training/tv/playback/memory)
and [Media3 stuttering guidance](https://developer.android.com/media/media3/exoplayer/troubleshooting#video-playback-is-stuttering).
