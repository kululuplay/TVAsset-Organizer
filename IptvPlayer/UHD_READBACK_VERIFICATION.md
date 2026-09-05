# UHD surface readback correction (unreleased)

## Observed baseline

Read-only ADB observation of Xiaomi MiTV-AYFR0, Android 11, installed v1.5.85:

- Active hardware video decoder: `OMX.amlogic.hevc.decoder.awesome2`, HEVC
  3840x2160. AAC stereo 48 kHz used the existing platform PCM audio path.
  This was **not** the MPEG-audio / full-VLC-software issue.
- The device was not classified as constrained. The existing health monitor
  continued PixelCopy checks after initial healthy confirmation.
- Native gralloc/RenderThread work matched the monitor cadence: first request
  approximately 120 ms after first frame, startup follow-up after completion,
  then repeated copies approximately 5,000 ms after the preceding healthy copy.
- Several recurring copy windows coincided with 2.6–2.9 s audio feed gaps,
  dropped frames and subsequent native-frame-watchdog reconnects. For example,
  healthy confirmation at 03:51:07.113 was followed by readback at 03:51:12.115
  and an underrun at 03:51:14.905 (2,778 ms feed gap).
- This timing is strong evidence for avoidable GPU readback contention. It is
  **not** a controlled post-fix A/B test, a measured source frame rate, proof of
  a memory leak, or proof that every UHD problem has the same cause. A software
  HEVC decoder's `NoSupport` log does not describe the selected hardware decoder.

## Implemented behavior

- `SurfaceReadbackPolicy` uses the **selected decoder** and actual source format,
  not the size of the Android view. A 1080p view may contain a 4K decoder buffer.
- Select startup-only readback for existing constrained devices or selected
  `OMX.amlogic.*` / `c2.amlogic.*` decoding with valid UHD dimensions (long side
  at least 3840 or short side at least 2160). Other hardware paths retain their
  existing periodic validation.
- Metadata may arrive in either order. Unknown metadata does not erase facts;
  startup-only mode remains sticky within the stream. New stream/release resets
  metadata. Reused decoder names are read from the current format event too.
- Retain two fresh healthy startup captures. Then stop recurring readbacks,
  preserving validated evidence and the surface provider. Late metadata retires
  the next queued check **before** issuing another expensive copy.
- Do not discard a green/blank suspicion already observed when the policy changes.
  Existing grace periods and bounded unavailable-surface retries remain intact.
- Genuine source-dimension/decoder changes and output-surface transitions require
  fresh validation, even if the display size did not change. Duplicate/unknown
  metadata must not repeatedly restart validation. Stale callbacks cannot validate
  a replacement stream/output.
- The native video-frame watchdog, audio stall handling, codec error recovery,
  dropped-frame policy, single-connection ownership and routing remain enabled.
  No added stream request, quality cap, forced software video or customer setting.
  Diagnostics only add a closed readback-mode label, not URLs or credentials.
- Previous uncommitted MPEG audio changes remain separate and preserved. No
  version bump, installation, account reset, commit, push, merge or release.

## Verification

- 80 focused JVM tests passed from current source: 26 readback-policy tests,
  17 real-monitor scheduling/callback tests and 37 existing classifier, green
  gate, retry, progress-probe, liveness and dropped-frame tests.
- Monitor tests mock Android PixelCopy and use a deterministic handler/clock.
  They include slow 2,700 ms callbacks, queued/in-flight metadata changes,
  unresolved green/blank output, initial grace, stopped-monitor revalidation,
  1080p-view/FHD-to-UHD source transitions and stale completion isolation.
  They do not simulate a physical GPU driver or prove smooth playback.
- 20 MPEG decoder/renderer regression tests passed again.
- 36 existing audio/route-memory tests passed again against the freshly compiled
  debug Kotlin classes. **136 focused JVM tests passed in total.**
- `:app:compileDebugKotlin :app:compileReleaseKotlin` completed successfully on
  2026-09-05 (2 min 8 sec). Existing unrelated deprecation warnings remain.
- A fresh `testDebugUnitTest lintDebug assembleDebug assembleRelease` invocation
  failed at `:app:compileDebugJavaWithJavac` after 27 seconds: the host again
  denied access to generated `processDebugResources/R.jar`, accompanied by
  cascading unresolved generated-Java/Kotlin-class errors. Full Gradle tests,
  lint, final APK/R8 packaging and physical-stick acceptance are **not verified**.
- Independent read-only review found no remaining blockers in this change;
  device-dependent limitations below still require candidate acceptance.

## Limits and required device acceptance

1. The initial two PixelCopy operations still cost time on the affected driver.
   This correction removes recurring healthy-output work, not every startup delay.
2. After startup-only validation, later color-only green/black corruption with
   advancing native frame timestamps has less detection coverage. Actual stalls,
   codec errors, startup bad pixels and output transitions remain monitored.
3. Before publication, run the complete Gradle unit/lint/debug/release build on a
   working build host. The local host previously denied javac access to generated
   `R.jar`; Kotlin compilation alone is not final APK/R8 verification.
4. Test a correctly signed candidate on the same stick and channel without
   clearing account data. Confirm `surfaceReadback=STARTUP_ONLY`, hardware HEVC
   3840x2160 retained, no recurring five-second readback pattern, and compare
   audio feed gaps, dropped frames, CPU, A/V sync and reconnects over at least
   15 minutes under the same source/network conditions.
5. Recheck repeated UHD/FHD zaps, preview/fullscreen, background/foreground,
   source resolution change, MPEG/AAC/AC3/EAC3 audio, older Fire TV and newer
   hardware. Genuine network/decoder failures must still recover controllably.

No claim of universal codec support or confirmed physical-stick resolution is
made until candidate playback acceptance passes.
