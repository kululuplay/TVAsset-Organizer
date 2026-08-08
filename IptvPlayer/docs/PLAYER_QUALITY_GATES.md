# Player quality gates

The live and VOD players must pass these checks before a public release. JVM
tests protect routing/state decisions; only physical devices can validate vendor
MediaCodec, SurfaceView and libVLC behaviour.

## Required device matrix

| Device class | Minimum coverage |
| --- | --- |
| Fire TV | one older low-memory stick, one current 4K/Max device |
| Amlogic Android TV | one API 21-25 box and one current Google TV device |
| Rockchip/MediaTek | at least one low-cost stick/box |
| Generic Google TV | current certified device |

Record Android/Fire OS version, build fingerprint, decoder name and available
memory with every result. Never include portal URLs, credentials or tokens.

## Required stream fixtures

- MPEG-TS and HLS variants of the same live channel
- H.264 576i/720p/1080i50/1080p50
- HEVC Main/Main10 and one 4K sample
- MPEG-2 video where supported by the service
- AAC, MP2, AC-3 and E-AC-3 audio
- multi-audio plus default/forced subtitle tracks
- MP4, MKV and AVI VOD; long-duration seek and resume
- deliberately interrupted, slow and malformed sources

## Automated invariants

- one active native/player owner and one subscription connection per session
- stale callbacks cannot mutate a newer channel/item generation
- every fallback route is bounded and visited at most once
- source/auth failures never masquerade as decoder failures
- URLs and credentials never enter telemetry or diagnostics
- VOD completion and next-episode actions execute at most once
- existing resume rows survive schema migrations
- Cast load starts only after the local provider socket reports a completed stop
- Cast suspension never restores local playback; uncertain ownership fails closed

## Physical acceptance gates

- 200 rapid channel changes: no ghost audio, duplicate connection or stale frame
- 50 background/foreground cycles: no crash, ANR or retained native owner
- 30 minutes per problematic fixture: no persistent green/black/frozen video
- automatic fallback finishes within 10 seconds after confirmed decoder failure
- stable-network rebuffer ratio below 1 percent after warm-up
- VOD seek p95 below 2 seconds and resume error at most 3 seconds
- 20 Cast handoffs/reloads/disconnects: no local/receiver socket overlap
- two-hour soak: no monotonic native/Java memory growth above 25 MB

## Rollout

Ship routing changes behind a remotely reversible configuration, begin with a
small device cohort and compare time-to-first-frame, rebuffer, recovery and fatal
rates with the previous release before expanding the rollout.
