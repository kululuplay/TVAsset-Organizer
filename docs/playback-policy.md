# Remote playback policy (`playbackPolicy`)

The crash-receiver returns a small `playbackPolicy` object in every heartbeat
response (`POST /api/heartbeat`, once a minute while the app is foregrounded).
The app (`util/PlaybackRemotePolicy.kt`) sanitises it, persists it in
SharedPreferences and exposes two cheap reads:

- `PlaybackRemotePolicy.snapshot()` — global kill switches.
- `PlaybackRemotePolicy.deviceOverrides()` — per-device-class overrides,
  resolved once per policy delivery from the `deviceOverrides` rules below.

Both return defaults (all-null overrides) once `expiresAtEpochMs` has passed,
so a box that stops hearing the server always falls back to its own settings.

## Where the policy comes from

1. **Panel** — the "Oynatma politikası" section on `/` saves the JSON into the
   `settings` table (key `playback_policy`). Takes effect on the next heartbeat,
   no restart. "Kayıtlı politikayı sil" removes it.
2. **Env fallback** — `PLAYBACK_POLICY_JSON` on the crash-receiver, used only
   while nothing is stored in the panel. Requires a restart to change.

Server-side validation (`validatePlaybackPolicyText` in `telemetry-store.js`)
and the app apply the same limits: text ≤ 32 KB, ≤ 32 rules, each regex ≤ 128
chars and must compile, unknown keys ignored, an invalid rule is dropped (the
panel reports how many were dropped) and never fails the whole policy.

## Schema

```json
{
  "policyVersion": 1,
  "ttlSeconds": 7200,
  "disablePixelCopyValidation": false,
  "allowSourceEngineFallback": true,
  "vodConnectTimeoutMs": 15000,
  "vodReadTimeoutMs": 20000,
  "deviceOverrides": [
    {
      "match": {
        "manufacturer": "<regex>",
        "model": "<regex>",
        "hardware": "<regex>",
        "board": "<regex>",
        "socModel": "<regex>",
        "sdkMin": 21,
        "sdkMax": 27,
        "lowRam": true,
        "totalRamMaxMb": 1536
      },
      "set": {
        "bufferMode": "ADAPTIVE | LOW | NORMAL | HIGH",
        "startEngine": "AUTO | EXOPLAYER | VLC",
        "tunneling": true,
        "livePreview": false,
        "allowSoftwareHdFallback": false,
        "compatibilityProfile": true,
        "vlcDeinterlace": false,
        "nativeFrameTrust": false
      }
    }
  ]
}
```

- `ttlSeconds` (300–604800, default 7200) becomes `expiresAtEpochMs` on the
  wire; the app refreshes it every heartbeat.
- Every `match` key is optional and they are ANDed. An empty `match` matches
  every device.
- Regex keys are matched case-insensitively with *find* semantics (unanchored;
  use `^…$` to anchor) against `Build.MANUFACTURER`, `Build.MODEL`,
  `Build.HARDWARE`, `Build.BOARD` and `Build.SOC_MODEL` (Android 12+; a
  `socModel` rule never matches an older device).
- `sdkMin` / `sdkMax` compare `Build.VERSION.SDK_INT`; `lowRam` compares
  `ActivityManager.isLowRamDevice`; `totalRamMaxMb` compares
  `MemoryInfo.totalMem` in MB (a device that could not report RAM never
  matches).
- Rules are applied in order; when several match, **later rules win per
  field**. Fields no rule sets stay `null`, and the consumer keeps its default.
- `set` values are enum names (case-insensitive) or booleans; anything else in
  `set` is ignored, and a rule that sets nothing valid is dropped.

Each heartbeat reports the matching facts (`manufacturer`, `model`,
`hardware`, `board`, `socModel`, `sdk`, `lowRam`, `totalRamMb`); the device
page (`/device/:id`) shows them under "Donanım / Kart", "SoC" and "RAM" so a
rule can be written from real values. The panel previews how many known
devices (of the 500 most recent) match each rule.

## Example rules

```json
{
  "ttlSeconds": 7200,
  "deviceOverrides": [
    {
      "match": { "manufacturer": "^amazon$", "model": "AFTM|AFTT" },
      "set": { "bufferMode": "HIGH", "startEngine": "EXOPLAYER", "livePreview": false }
    },
    {
      "match": { "model": "MiTV-AESP0|^M0$" },
      "set": { "tunneling": true, "allowSoftwareHdFallback": false }
    },
    {
      "match": { "lowRam": true },
      "set": { "compatibilityProfile": true, "livePreview": false }
    }
  ]
}
```

1. Fire TV Stick 1st/2nd gen (`AFTM`, `AFTT`): bigger buffer, force ExoPlayer,
   no live preview.
2. Mi TV Stick (`MiTV-AESP0`, `M0`): tunneled playback, never fall back to a
   software HD decoder.
3. Any `isLowRamDevice` box (also catches the sticks above; ordering means the
   stick-specific fields set earlier stay, and `compatibilityProfile` is added).

## Client API (Kotlin)

```kotlin
// object PlaybackRemotePolicy
data class DeviceOverrides(
    val bufferMode: BufferMode? = null,
    val startEngine: PlayerMode? = null,
    val tunneling: Boolean? = null,
    val livePreviewEnabled: Boolean? = null,
    val allowSoftwareHdFallback: Boolean? = null,
    val vlcDeinterlace: Boolean? = null,
    val compatibilityProfile: Boolean? = null,
    val nativeFrameTrust: Boolean? = null,
)
fun deviceOverrides(nowMs: Long = System.currentTimeMillis()): DeviceOverrides
fun deviceFacts(context: Context): DeviceOverrideMatcher.DeviceFacts
```

The pure matcher lives in `DeviceOverrideMatcher` (same file, no Android
imports) and is covered by `PlaybackRemotePolicyDeviceOverridesTest`.

## `vlcDeinterlace` (1.5.98)

`set.vlcDeinterlace` controls libVLC's deinterlacer on the **software** route
only. The device rule (`VlcDeinterlacePolicy`) turns it off on Amlogic and
other 32-bit devices, where every filter mode costs the zero-copy output path
(1080i measured at 10 fps with the filter and 25 fps without on a MiTV Stick).
`false` forces it off, `true` restores libVLC's default on a device class that
copes with the filter; hardware routes and progressive streams are unaffected.

## `nativeFrameTrust` (1.5.99)

Live TV on ExoPlayer confirms video by sampling the SurfaceView with
PixelCopy. On overlay/underlay SoCs (Amlogic-class sticks) the decoded picture
sits on a hardware plane, so the copy can read black, or a zeroed placeholder
that classifies as solid green, while the viewer sees valid video. Three
verdicts depend on those pixels alone: startup solid green, persistent blank
and the 9 s validation deadline.

`set.nativeFrameTrust` decides when those pixel-only verdicts are advisory and
Media3's own frame cadence (at least 24 frames, at least 8 in the last second,
player READY) is accepted as video proof instead:

- unset (default): advisory only where the verdict has no other video stage to
  go to, e.g. a constrained Amlogic stick with software HD withheld. There the
  verdict could only reopen the same ExoPlayer stage in a loop. Wherever
  another stage exists the verdicts stay authoritative, exactly as in 1.5.98.
- `false`: always authoritative, with the 1.5.98 PixelCopy cadence and no
  inline-copy budget (see below), i.e. the 1.5.98 live Exo surface validation.
  It does not undo the other 1.5.99 changes (failed player stopped during the
  retry wait, narrowed interlaced quick-fail, telemetry fields). Use it for a
  device class seen playing real green or black video.
- `true`: advisory on every route. For a new SoC whose PixelCopy is blind but
  which still has a fallback stage.

In every mode one healthy PixelCopy sample on a stream makes the pixel verdicts
authoritative again for the rest of that stream, and decoder-side checks (frame
stall, no first frame) are unaffected. The app log line
`PixelCopy advisory (...) -> accept Media3 native frames` marks an accept, and
the `playback_attempt` STABLE row then carries `proof=NATIVE_ADVISORY`.

Only while the pixel verdicts are advisory, a PixelCopy request that blocks the
main thread for 1.5 s on a constrained device (4 s elsewhere) stops sampling
for that stream (`SLOW_INLINE_COPY`; the copy is not classified and proves
nothing, so native frame cadence or the deadline decides), and shorter
blocking copies are spaced to twice their cost. Authoritative pixel verdicts
keep the 1.5.98 cadence with no budget and no spacing, so a slow but
successful copy is still classified before the 9 s deadline. VLC and VOD
surface sampling is unchanged.

An empty `match` reaches every device, so
`{"match":{},"set":{"nativeFrameTrust":false}}` is the fleet-wide rollback.
Deploy the crash-receiver that knows the key before any rule uses it: an older
server drops the key from the rule (and a rule that sets nothing else). Apps
before 1.5.99 ignore it. The panel's match preview only counts the 500 most
recent devices, so check a model rule's full match count on the devices table.
When the policy expires (no heartbeat for `ttlSeconds`) the app returns to the
default.
