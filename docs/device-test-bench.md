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

## Stale-process recycle (1.5.99)

`StaleProcessGuard` restarts the app process when the user comes back after
four hours in the background/asleep, or presses a key after four hours without
input while no playback runs. To test it without waiting:

```bash
adb shell settings put global kululu_stale_recycle_ms 60000
```

Then either press Home, wait a minute and reopen the app, or leave a
non-playing screen untouched for a minute and press a key. Expect
`PlaybackLog` lines `ProcessRecovery ... reason=stale_process_recycle detail=...`,
a new process id (`adb shell pidof <package>`) and the splash (players reopen
their own screen). Clear with `adb shell settings delete global kululu_stale_recycle_ms`.
`kululu_stale_recycle_any_screen 1` also allows the recycle from the login and
splash screens, so the path is testable without an account.

## Process recovery must be tested on a non-debuggable build

Debug builds are dumpable, so the `:playback_recovery` process can read
`/proc/<pid>` of the main process and the old `/proc`-based verification looked
fine on the emulator. Release builds are not dumpable and `/proc` is mounted
with `hidepid=2`: `/proc/<pid>` of the main process is invisible to its
sibling process (`adb shell ls -ln /proc/<pid>/stat` shows owner `0`, and the
directory does not exist from inside the recovery process). Until 1.5.100 the
recovery therefore never killed anything in production.

Build a release variant with the diagnostics switches, signed with any keystore
(the debug keystore is fine for the emulator):

```bash
cd IptvPlayer
LIVE_PLAYBACK_DIAGNOSTICS=1 KEYSTORE_FILE=$HOME/.android/debug.keystore KEYSTORE_PASSWORD=android KEY_ALIAS=androiddebugkey KEY_PASSWORD=android ./gradlew -PabiFilter=x86 :app:assembleRelease
```

Expected after a recycle in `adb logcat`: `ActivityManager: Process com.iptv.player (pid N) has died`
about 100-200 ms after `Start proc ...:playback_recovery`, then a new
`Start proc ...:com.iptv.player`. A surviving process now logs
`controlled process recovery did not replace this process` after six seconds
and reports `process_recovery_failed` to stability telemetry.

## Certificate trust on old Android (instrumented tests, 1.5.101)

Android below 7.1.1 does not ship ISRG Root X1, the root of the portal's
Let's Encrypt certificate; the app bundles it (`BundledRootTrust`, network
security config, libVLC `--gnutls-dir-trust`). The behaviour depends on the
platform's own certificate store and TLS stack, so it is tested on real system
images, not on the JVM:

```bash
sdkmanager "system-images;android-24;android-tv;x86" "system-images;android-23;android-tv;x86"
avdmanager create avd -n tv24 -k "system-images;android-24;android-tv;x86" -d tv_1080p
emulator -avd tv24 -no-window -port 5584
cd IptvPlayer
ANDROID_SERIAL=emulator-5584 ./gradlew -PabiFilter=x86 :app:connectedDebugAndroidTest
```

If Gradle reports `Failed to receive the UTP test results`, install both APKs
and run the runner directly:

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.iptv.player.test/androidx.test.runner.AndroidJUnitRunner
```

- `PortalChainTrustTest` checks the app's effective trust against the chain
  kululu.live served on 5 Oct 2026 (`src/androidTest/assets/tls`). It needs no
  network. When the leaf has expired the tests skip themselves: save a fresh
  chain with `openssl s_client -connect kululu.live:443 -showcerts` (run it on
  a server, not on a PC whose antivirus intercepts TLS).
- `LibVlcTrustDirectoryTest` proves that libVLC only reaches an HTTPS server
  with an unknown root when that root is in the trust directory.
- x86 only: libVLC 3.7.6 crashes in `nettle_memxor` when it encrypts an AES-GCM
  record (SIGSEGV right after the handshake), so the test removes GCM suites on
  x86. Real TV devices are ARM and use GCM every day.
- A live login from an emulator is not a valid trust test on a PC with a
  TLS-intercepting antivirus: the emulator then sees the antivirus certificate
  and fails on every Android version.

