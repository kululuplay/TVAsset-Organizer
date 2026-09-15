# Update visibility and legacy playback

## Customer behavior

- Subscription expiry remains the first launch notice. Acknowledging or dismissing
  it now continues to the update check instead of suppressing updates for that
  launch. A suppressed/no-op/failed expiry check also continues. Presentation waits
  for the dashboard to resume; destroying it cancels the pending continuation.
- Settings has a directly accessible **App-Updates** rail item. It displays the
  installed version, checks when opened, and distinguishes available, up to date,
  staged rollout and failed checks. Manual retry stays focusable while pending.
- An available update exposes **Update now**, using the existing verified APK
  download/installer flow. Merely opening Settings or checking starts no download.
- A late check cannot change another panel or steal D-pad focus. Leaving the panel
  or backgrounding Settings cancels its coroutine and the underlying HTTP call;
  reopening checks afresh. Both release and rollout requests close response bodies.

## Older Android TV / Fire TV devices

The minimum remains API 21 with armeabi-v7a, arm64-v8a, x86 and x86_64 APK libraries.
Device capabilities and memory, rather than a product-name blacklist, select the
existing compatibility profile. Existing SurfaceView output, disabled tunneling,
PCM defaults, decoder fallback and bounded engine recovery remain in place.

Media3 movie/episode playback now uses a 24 MiB encoded-sample target in that
profile, matching the constrained live-TV target. Previously it used the default
track-derived target even on small heaps. The same target also permits playback
to start/resume, avoiding an unreachable time-buffer wait on high-bitrate files.
Normal hardware keeps Media3's track-derived target. Buffering duration, seek
thresholds, source/container choice, and download behavior are otherwise unchanged.

The target controls encoded samples, not total process/decoder memory. This does
not add unsupported codecs to hardware, convert a fixed 4K stream into HD, or make
the Android APK run on Tizen/webOS. Physical verification on representative older
devices remains required before claiming model-specific compatibility.

## Verification scope

- Real Media3 load-control tests cover bounded allocation, resume at the byte
  target, refilling after consumption, seek/start thresholds, useful low-bitrate
  reserves, and unchanged capable-device behavior.
- Capability tests cover API 21/22/23/25/28/30 with 1 GiB RAM and a small heap, an
  actual 32-bit process on 64-bit-capable hardware, and a capable old-API device.
- HTTP tests cover cancellation, late response cleanup, normal response cleanup
  and non-success status preservation. All existing localized update strings are
  reused, including German and Turkish.
- No release, rollout change, physical-device install or customer-account access
  is part of this change. Existing 1.5.88 clients acquire the new UI/launch behavior
  only after upgrading; publishing on GitHub cannot rewrite installed APK code.
