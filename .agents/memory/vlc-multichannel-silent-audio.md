---
name: VLC 5.1 silent audio (force stereo downmix)
description: Why multichannel IPTV plays video with no sound on some boxes and the libVLC option that fixes it.
---

# VLC multichannel (5.1) → silent audio

On some Android TV boxes (e.g. Amlogic) a multichannel stream plays VIDEO but NO
AUDIO: libVLC's AudioTrack output can't open a 6-channel PCM track (logcat:
`too low audio sample frequency (0)` then `module not functional`,
`channelMask 0x3f`). 4K HEVC video decodes fine on HW meanwhile.

**Critical:** libVLC raises NO error event for an audio-only output failure
(`EncounteredError`/`onError` only fire for full playback errors). So the
PlayerController fallback ladder never fires and the stream stays silent forever —
there is nothing to fall back on. Do NOT expect an event to catch this.

**Fix:** force a stereo downmix on the non-passthrough (PCM) path with the libVLC
option `--stereo-mode=1` (also mirrored per-Media as `:stereo-mode=1`, next to the
existing `--no-spdif`/`:no-spdif`). `stereo-mode` is an INTEGER libVLC option
(1 = Stereo) — symbolic forms like `stereo-mode=stereo` do NOT parse. An unknown
option is logged + ignored (harmless), so this is safe even if a build lacks it.

**Why:** the design intent was already "decode to stereo PCM any HDMI sink accepts,"
but the code only disabled SPDIF — it never forced the downmix, so 5.1 sources still
tried (and failed) to open a 6-channel output. Genuinely-stereo channels are
unaffected (already 2.0); keep it gated behind `!allowPassthrough` so the opt-in
passthrough path is untouched.

**How to apply:** mirror on BOTH player paths (live = VlcPlayerEngine, VOD =
VodPlayerActivity) — they are separate libVLC setups. If it ever proves insufficient
on a device, next escalations are `--aout=opensles` (stereo-only, but a heavier
global aout change) or routing VLC audio failure to ExoPlayer (which forces
DEFAULT_AUDIO_CAPABILITIES / stereo PCM).
