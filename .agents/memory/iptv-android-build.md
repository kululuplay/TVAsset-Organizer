---
name: IPTV Android build constraints
description: Non-obvious constraints for the com.iptv.player Android app (source-only, CI-built)
---
# IPTV Android player — build/release gotchas

- **No local compile.** Source lives under `IptvPlayer/`; APK is built by GitHub Actions (repo kululuplay/TVAsset-Organizer). Verify correctness by static reasoning + ripgrep, never by running gradle here.
- **Release build runs R8** (`isMinifyEnabled = true`). Any class referenced only by name from the manifest (e.g. Cast `OptionsProvider` meta-data) MUST have a `-keep` rule in `proguard-rules.pro`, or it is stripped/renamed and crashes at runtime in release only.
  **Why:** debug builds don't minify, so these breakages are invisible until the CI release APK.
- **String resources are split** across many `strings_*.xml` files per locale (en/tr/de/fr/nl/ar). When adding keys, keep 6-locale parity AND check for cross-file duplicate `name=` (Android fails the build on dup keys across files in the same `values*` dir).
- **rg gotcha:** `-oh` triggers ripgrep help; use `-oI` for only-matching without filename.
- **Post-login landing is `DashboardActivity`** (gradient-tile launcher), NOT `HomeActivity`. SplashActivity + LoginActivity route to Dashboard; `HomeActivity` is the 3-pane *Live browser* reached via the Live tile.
  **Why:** user wanted a Smarters-style launcher home. No dedicated Favorites/Search screen exists yet — those tiles/buttons route to HomeActivity as a stopgap.


## Locale parity & the multi-file base gotcha (IMPORTANT)
The base `values/` locale SPLITS strings across MANY files: `strings.xml` PLUS
`strings_settings.xml`, `strings_guide.xml`, `strings_player.xml`, `strings_polish.xml`,
`strings_live.xml`, `strings_dashboard.xml`, `strings_features.xml`. Translated locales
(values-tr/de/fr/nl/ar) similarly use multiple `strings*.xml` files.
**Mistake to never repeat:** counting only `values/strings.xml` made it look like the base
was missing 28 keys vs translations; adding them caused `Duplicate resources` build failure
because they already lived in `strings_settings.xml`/`strings_guide.xml`/`strings_polish.xml`/
`strings_player.xml`.
**How to apply:** ALWAYS glob `values/strings*.xml` (and `values-XX/strings*.xml`) when
checking string parity or dup keys — never a single file. Android fails the build on
duplicate `name=` across ANY two files in the same `values/` folder. Current state: all 6
locales have 219 keys, full parity, no dups.

## aapt: unescaped apostrophe in strings breaks the build
A single raw `'` inside a `<string>` value fails aapt with a cryptic
"Can not extract resource from ParsedResource" / "Failed to compile values resource file
values-XX/values-XX.xml" — NOT a clear apostrophe message, and XML well-formedness checks
still PASS (it's an Android-specific rule, not an XML rule). High risk in fr/it (l', d', etc.).
**How to apply:** apostrophes inside string values must be `\'` (or the whole value wrapped
in "double quotes"). When touching locale strings, scan ALL locales:
`grep -rn "'" values*/*.xml | grep '<string' | grep -v "\\'"` should return nothing.

## aapt: dotted style names imply a parent
A style named `Foo.Bar` with NO `parent=` attribute implicitly inherits from `Foo`
(everything before the last dot). If that base style doesn't exist, aapt fails at link time:
"resource style/Foo not found ... failed linking references". Seen with Widget.Iptv.Feature /
Widget.Iptv.IconButton implying a non-existent Widget.Iptv.
**How to apply:** any dotted-name style without an explicit parent must either have a real base
style of that name, or set `parent=""` to opt out. Scan:
`rg '<style name="[^"]*\.' values*/*.xml | rg -v 'parent='` should return nothing.

## libVLC tuning for smooth IPTV (anti-stutter/freeze)
VlcPlayerEngine options chosen for stability over startup latency on weak Android TV boxes:
network-caching+live-caching=3000ms (bigger jitter buffer), clock-jitter=0 + clock-synchro=0
(IPTV TS streams have irregular PCR; stops "stall to resync"), avcodec-skiploopfilter=all +
avcodec-fast (cut decode load), http-reconnect (recover dropped HTTP). Mirror the same as
per-Media `:opts` so they apply per stream.
**Why:** --no-drop-late-frames/--no-skip-frames (old config) FORCE rendering every late frame
→ delay accumulates → stutter; removing them restores VLC's real-time defaults.
**Tradeoff:** clock-* can drift/desync on long sessions; avcodec-fast/skiploopfilter lose a bit
of quality at low bitrate. Acceptable when user prioritizes no-freeze. Make runtime-toggleable
only if desync/artifacts are reported.

## Dashboard design language (professional/modern)
Old dashboard used six clashing full-tile rainbow gradients = amateur. New language:
cohesive dark frosted-glass tiles (bg_tile_surface: top->bottom dark gradient + hairline
stroke), color lives ONLY in rounded per-category icon badges (bg_badge_*), Live tile is a
hero variant (bg_tile_hero: same glass + soft purple/magenta radial glows) with an accent
underline. Focus = 3dp brand_pink ring (bg_tile_focus) + scale animator (tile_focus).
**Why:** localize color to small badges over a unified dark surface = streaming-app polish
(Apple TV/Plex), still high-contrast D-pad focus on dark tiles.
**How to apply:** keep this language for any new dashboard/home cards; don't reintroduce
full-tile gradients. Reuse badge_hero/medium/small dimens + badge_radius.

## Connect/refresh crash hardening (catch Throwable, not Exception)
App closed instantly on login "Connect". Connect path looked fully guarded but
`catch (e: Exception)` does NOT catch `java.lang.Error` subclasses — esp.
`OutOfMemoryError`, which is the realistic cause on low-RAM TV boxes when a big
Xtream account / M3U returns tens of thousands of channels (Gson list + mapNotNull
+ entity list + Room txn all in memory at once). Also `LoginViewModel.submit` had
an unguarded `settings.saveSource` and no final safety net in the viewModelScope
coroutine, so any escaping Throwable killed the process.
Fix: repo testSource/refreshLive/refreshVod/refreshSeries/refreshEpg catch
`Throwable`; `toAppError` extends Throwable (OOM -> EMPTY_PLAYLIST); refreshLive
inserts channels in chunks of 1000; submit wraps body in try/catch(Throwable).
**CRITICAL:** when catching Throwable in a coroutine you MUST rethrow
`CancellationException` (`if (e is CancellationException) throw e`) or you break
cancellation (stale state, leaks). Architect flagged this.
**Why:** an un-reproducible "app closes on action X" with already-try/catch'd code
is almost always an Error (OOM/Linkage) escaping `catch(Exception)`, or an
unguarded line in the launching coroutine.
**How to apply:** for any heavy network+parse+DB op behind a button, catch
Throwable + rethrow CancellationException + map the rest to a friendly AppError;
batch large DB inserts to bound peak memory.

## VOD & live both play through libVLC (VLC is the only engine users want)
Live TV uses PlayerController(PlayerMode); VOD (VodPlayerActivity) was built
directly on Media3 ExoPlayer. User wanted VOD to also always use VLC like live.
PlayerEngine interface has NO seek/duration/position/track APIs, so VOD can't
reuse it as-is — VodPlayerActivity drives libVLC MediaPlayer directly (mirror
VlcPlayerEngine's LibVLC options + VLCVideoLayout + attachViews).
**CRITICAL Kotlin pitfall:** libVLC MediaPlayer setters return non-Unit
(`setTime` long, `setAudioTrack`/`setSpuTrack` boolean), so Kotlin will NOT expose
them as assignable properties — `mp.time = x` / `mp.audioTrack = id` fail to
compile. Call the methods explicitly: `mp.setTime(x)`, `mp.setAudioTrack(id)`,
`mp.setSpuTrack(id)`. Read-only getters (`mp.time`, `mp.length`, `mp.isPlaying`,
`mp.currentVideoTrack`) work as properties. `mp.media = media` DOES work
(setMedia returns void).
**VOD-on-VLC specifics:** defer the resume seek to the first
`MediaPlayer.Event.Playing` (VLC ignores setTime before playback starts) via a
pendingSeekMs field; subtitle menu must add an explicit "Off" -> setSpuTrack(-1)
(VLC may not always expose a disable track); aspect map = setAspectRatio/setScale
(ORIGINAL/16:9/4:3 clean, FILL = setAspectRatio("${w}:${h}") of container, ZOOM =
setScale(fill/fit) from currentVideoTrack dims); poll time/length on a Handler.
**Why:** ExoPlayer-only VOD diverged from the VLC-everywhere product decision and
ExoPlayer rejects some IPTV codecs (DTS/AC3/EAC3, AVI/MKV) VOD libraries contain.
