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
        "vlcDeinterlace": false
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
