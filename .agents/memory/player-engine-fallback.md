---
name: ExoPlayer->libVLC fallback for undecodable audio
description: Why silent-but-error-free ExoPlayer playback must be forced to fail so the libVLC fallback engine takes over.
---
The player runs ExoPlayer (Media3) primary + libVLC fallback; AUTO mode switches Exo→VLC only when `PlayerListener.onError` fires. The trap: ExoPlayer can play a stream **mute with no error**, which never triggers the fallback even though VLC would play it with sound.

**Rule:** Any path where ExoPlayer yields a picture but no audio must be turned into an `onError(...)` so the controller falls back to libVLC.

**Two known cases, both handled in `ExoPlayerEngine`:**
- **MP2 / MPEG audio (audio/mpeg-L2):** most Android TVs have no decoder. ExoPlayer's track selector *silently deselects* the undecodable audio track (no selected track fails → no `onPlayerError`). Detected in `onTracksChanged`: if there is ≥1 audio `Tracks.Group` but none returns `isTrackSupported(i)`, report `onError("UNSUPPORTED_AUDIO")` (guarded by a once-per-`play()` flag). Video-only streams stay safe (no audio group → no trigger); any one supported rendition short-circuits (HLS multi-audio safe).
- **AC-3 / E-AC-3 passthrough:** TVs falsely advertise HDMI passthrough → bitstream handed off → silence. Fixed by forcing `AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES` (stereo PCM) so Dolby is decoded on-device, or errors out → VLC.

**Why:** libVLC decodes MP2/AC-3/DTS in software, so the universal answer to "codec X has no sound on Exo" is to make Exo fail fast and let VLC handle it — never to chase per-codec Exo decoders.

**How to apply:** When a new "plays but silent / wrong" codec report comes in, first confirm VLC plays it, then add a detection in `ExoPlayerEngine` that converts the silent state into `onError`. Don't disable the libVLC engine's audio with extra `--` options; its defaults already cover these codecs.
