# MPEG audio / hardware-video preservation (unreleased)

## Evidence and scope

On the attached Xiaomi MiTV-AYFR0, Android 11, v1.5.85 reported unsupported
`MPEG_AUDIO`, stereo, 48 kHz. It switched EXO → VLC_SW, then remembered that
route. Process CPU was repeatedly around 259–382% on four cores. A roughly
8.88-second SurfaceFlinger video-layer sample contained 127 presentations
(about 14.2 presentations/s); this is **not the source stream's frame rate**.
The observations support a software-video bottleneck, but do not establish a
controlled same-channel regression against the previous version.

## Implemented correction

- Append a narrowly scoped Media3 `DecoderAudioRenderer` using unmodified
  `javazoom:jlayer:1.0.1`. Working platform audio renderers remain first.
- Decode demuxed MPEG-1 Layer I/II/III, mono/stereo, 32/44.1/48 kHz to signed
  16-bit PCM. Media3's existing sink owns audio timing; video keeps its existing
  MediaCodec/SurfaceView path. There is no new player, socket or provider request.
- Preserve synthesis/MP3 reservoir state across packets; reset it on Media3
  flush/seek and release. Bound input size/frame count. Malformed input returns
  a decoder error to the existing bounded controller recovery.
- Do **not** claim unsupported profiles: upstream JLayer 1.0.1 miscalculates
  MPEG-2 Layer-II frame sizes, so low-rate MPEG-2/2.5 remains on existing paths.
  DRM, multichannel, AAC, AC3, EAC3, DTS and video are not handled by this renderer.
- Keep the existing Amlogic VLC hardware safety guard and one-connection rules.
- Route-memory schema 6 re-evaluates old schema 4/5 software routes once;
  hardware routes and login/settings remain unchanged. Explicit user-selected
  VLC/software mode is not silently overridden.
- Log a closed `mpegPcm=true` flag when the new decoder initializes. No customer
  media, URLs, credentials or raw track labels are added to diagnostics.
- Preserve JLayer decoder names and `.ser` resource lookup through R8. LGPL
  license, attribution and corresponding-source/rebuild information are in
  `app/src/main/assets/licenses/`. No third-party native binary is added.

## Verification on 2026-09-05

- **20 new focused JVM tests pass**, compiled from current sources: independently
  generated MP2 stereo/mono and MP3 tone PCM matches FFmpeg reference duration
  and bounded RMS error; packet continuity matches continuous JLayer decoding;
  Layer-I non-silent synthesis continuity, silence, truncation, malformed/oversized
  samples, rejected profiles, flush, timestamp preservation, EOS, input slicing,
  bounded decode errors, renderer support/DRM/PCM-sink gating are covered.
- **36 existing focused audio/route-memory tests pass**, including schema-6
  migration expectations and the earlier AC3 clock/underrun regression tests.
- Debug and release Kotlin compilation completed. A full Gradle invocation of
  testDebugUnitTest, lintDebug, assembleDebug and assembleRelease failed at
  compileDebugJavaWithJavac with the host's existing `AccessDeniedException` on
  generated debug `R.jar` (and cascading missing Kotlin-class errors).
  **Full Gradle suite, lint, final APK/R8 packaging and device playback are not
  verified.** The SDK's normal android.jar cannot run decoder tests; focused JVM
  tests used Gradle's generated return-default-values mockable Android jar.
- No version bump, install, account reset, commit, push, merge or release.
  Unrelated support-desk work is untouched.

## Required before publication

1. Run `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`
   on the normal CI/build host. Verify APK contains
   `javazoom/jl/decoder/sfd.ser` and `assets/licenses/LGPL-2.1.txt`; test the
   **minified** candidate too, not just debug.
2. Install a correctly signed candidate on the attached stick by an approved
   method, without uninstalling/clearing account data. Start the SAME affected
   channel and confirm `audioDecoder=SOFTWARE mpegPcm=true`, hardware video,
   and no unsupported-audio → VLC_SW transition.
3. Compare CPU and video-layer cadence against the captured baseline under the
   same source/network conditions; confirm audible stereo and A/V sync for at
   least 15 minutes. Progress/first-frame callbacks alone do not prove smoothness.
4. Test repeated zaps, preview/fullscreen, pause/resume, seek/radio where applicable,
   network interruption and single-connection ownership. Recheck AAC/AC3/EAC3,
   an older Fire Stick and a newer stick. Unsupported profiles must fail/fallback
   controllably, not remain indefinitely loading.

This fixes the missing audio-only path in code. It is not a claim of universal
codec support or measured post-fix smoothness until device acceptance passes.
