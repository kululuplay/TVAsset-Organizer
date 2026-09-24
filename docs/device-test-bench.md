# Device test bench (diagnostics builds)

How the 2026-09-24 MiTV Stick investigation was run, so the next on-device
playback question does not start from zero. Everything here exists only in
builds made with `LIVE_PLAYBACK_DIAGNOSTICS=1`; production builds contain none
of it.

## Build a side-by-side test app

```bash
cd IptvPlayer
LIVE_PLAYBACK_DIAGNOSTICS=1 ./gradlew -PtsOnlyTestBuild=true -PabiFilter=armeabi-v7a :app:assembleDebug
```

- `-PtsOnlyTestBuild=true` gives package `com.iptv.player.preview` ("Kululu IPTV
  Preview"): installs next to the production app, never offers updates.
- `-PabiFilter=armeabi-v7a` (or any comma list) packages one ABI; a 32-bit stick
  with a nearly full `/data` rejects the 115 MB universal debug APK with
  `INSTALL_FAILED_INSUFFICIENT_STORAGE`, the single-ABI one is ~39 MB.
- `adb install -r -d` keeps the preview's login when downgrading between test
  builds (debug signing only).

## What a diagnostics build logs (tag `PlaybackLog`)

- `liveDiag …` — one line per second: player state, position, buffered
  duration, last video frame age, audio/video readiness.
- `videoDiag …` (`DiagnosticVideoRenderer`) — per second: `processOutputBuffer`
  calls vs. processed, last output PTS vs. position, drop/force decisions and
  the Media3 `DecoderCounters` (`queuedIn`, `rendered`, `skipped`, `dropped`).
  `outCalls=0` with `queuedIn` frozen means the codec neither returns output nor
  accepts input: the stall is inside the vendor decoder.
- `videoIn …` — the first 150 queued input buffers as `index:ptsMs/bytes[K][S][nal types]`
  (H.264: 9 AUD, 7 SPS, 8 PPS, 6 SEI, 5 IDR, 1 slice). Field-coded streams show
  20 ms PTS pairs; multi-slice pictures show repeated PTS.

## adb switches (`DiagnosticSwitches`)

```bash
adb shell settings put global kululu_tunnel 1          # force Media3 video tunneling on the next play
adb shell settings put global kululu_vlc_opts "--deinterlace=0 --avcodec-skiploopfilter=4"
adb shell settings put global kululu_vlc_opts '""'     # clear
```

The libVLC options apply to the next engine (each channel start builds a new
LibVLC instance); the log line `diag extra libVLC options: […]` confirms them.

## Measuring frames without screenshots

`screencap` cannot capture Media3 frames on Amlogic (hardware video plane), but
SurfaceFlinger still sees every buffer the SurfaceView receives:

```bash
adb shell dumpsys SurfaceFlinger --latency "SurfaceView - com.iptv.player.preview/com.iptv.player.ui.home.HomeActivity#0"
```

Each line is one presented frame (desired/actual present/frame-ready
timestamps, ns). Sample it once per second: the newest timestamp stops
advancing when the picture is frozen; `(n - 1) / span` over the 128-entry ring
is the effective frame rate.

## Findings kept for reference

- Xiaomi MiTV Stick `MiTV-AYFR0` (Amlogic s4, Android 11, 32-bit): interlaced
  H.264 on `OMX.amlogic.avc.decoder.awesome2` decodes ~5 frames, then all output
  buffers stay on the display side (`AmVideoDec OUT[26:4]`, `CLIENT[15]`).
  Identical on Media3 1.8.1 and 1.11.1; unchanged by static scheduling,
  operating-rate suppression, frame-per-buffer (field pairing) input or
  tunneling (`AUDIO_TRACK_INIT_FAILED`). Handled by `InterlacedExoPolicy`.
- Same device, software route: any libVLC deinterlace mode → 10 fps,
  `--deinterlace=0` → 25 fps (`VlcDeinterlacePolicy`).
