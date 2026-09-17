# Media3 FFmpeg audio decoder extension

ExoPlayer (Media3) is the primary engine. Until now any audio codec the
device's MediaCodec stack could not decode (MP2 on most TV boxes, AC-3/E-AC-3
without a Dolby licence, DTS, sometimes AAC-LATM) produced a silent picture,
which `ExoPlayerEngine` turns into `onAudioUnavailable()` so the controller
falls back to libVLC. Every fallback hop costs a reconnect and, on Amlogic, a
different (worse) video path.

The Media3 `decoder_ffmpeg` module adds `FfmpegAudioRenderer`, a pure-software
audio renderer backed by FFmpeg's `libavcodec`, so those streams now play with
sound inside the same ExoPlayer instance and hardware video stays untouched.
Google does not publish the module to Maven, so CI builds it from source.

## What is built

| Input | Pin | Where |
| --- | --- | --- |
| `androidx/media` (Media3) | tag `1.8.1` (must equal the app's `androidx.media3:*` version) | `scripts/ffmpeg/versions.env` |
| FFmpeg | tag `n6.0.1` (the `release/6.0` line the Media3 1.8.1 README recommends) | `scripts/ffmpeg/versions.env` |
| Android NDK | `26.1.10909125` (r26b, the version the README documents as tested) | `scripts/ffmpeg/versions.env` |
| CMake | `3.22.1` (`CMakeLists.txt` needs 3.21+) | `scripts/ffmpeg/versions.env` |
| ABI level | 21 (= `minSdk`) | `scripts/ffmpeg/versions.env` |
| ABIs | `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` (the app's `abiFilters`) | `build_ffmpeg.sh` in the media checkout |
| Decoders | `mp3 mp2 aac ac3 eac3 dca opus vorbis flac alac mlp truehd` | `ENABLED_DECODERS` in `versions.env` |

FFmpeg is configured by the extension's own `build_ffmpeg.sh`
(`--disable-everything` plus the listed `--enable-decoder=...`, static libs,
no programs, no avformat/avfilter/swscale). Nothing adds `--enable-gpl`,
`--enable-nonfree` or external libraries, so the result is FFmpeg under its
default **LGPL 2.1+** terms. The build script refuses to continue if
`config.h` shows `CONFIG_GPL` or `CONFIG_NONFREE`.

Output: `IptvPlayer/app/libs/media3-decoder-ffmpeg-release.aar` (classes +
`jni/<abi>/libffmpegJNI.so`, which statically links `libavcodec`, `libavutil`
and `libswresample`) and a sidecar `media3-decoder-ffmpeg-release.txt` that
records the exact commits, NDK and decoder list.

### Pipeline

1. `scripts/ffmpeg/build_media3_ffmpeg.sh` (Linux/macOS): clones both repos at
   the pinned tags into `$FFMPEG_WORK_DIR` (default `$RUNNER_TEMP` or `/tmp`),
   symlinks FFmpeg into `libraries/decoder_ffmpeg/src/main/jni/ffmpeg`, runs
   `build_ffmpeg.sh` for the four ABIs, then
   `./gradlew :lib-decoder-ffmpeg:assembleRelease` inside the media checkout
   with `scripts/ffmpeg/ndk-version.init.gradle` pinning `ndkVersion`, and
   copies the AAR into `app/libs/`. It is idempotent (existing checkouts at the
   right tag and already built static libs are reused; `FFMPEG_FORCE_REBUILD=1`
   overrides) and fails fast on a missing tool, NDK, CMake, tag or ABI.
2. `.github/workflows/android.yml` job `ffmpeg-extension`: restores the AAR
   from `actions/cache` keyed by media3 version + FFmpeg tag + hash of the
   three files under `scripts/ffmpeg/` + NDK version; on a miss it installs the
   NDK/CMake and runs the script (~15-20 min). The AAR is verified (all four
   `libffmpegJNI.so`) and uploaded as artifact `media3-decoder-ffmpeg`.
3. `verify` and `release` `needs: ffmpeg-extension`, download the artifact into
   `IptvPlayer/app/libs/` before Gradle runs, and fail if the AAR is missing.
   The release job additionally checks the final minified APK contains
   `lib/<abi>/libffmpegJNI.so` for every ABI. Production always ships FFmpeg
   audio; there is no "build without it" mode in CI.

### App integration

- `app/build.gradle.kts` detects the AAR at configuration time. Present:
  `implementation(files(...))`, source set `src/ffmpeg/java`,
  `BuildConfig.FFMPEG_AUDIO = true`. Absent (every local build): source set
  `src/noffmpeg/java`, `FFMPEG_AUDIO = false`, and nothing else changes.
- Both source sets define `internal object FfmpegAudio` with `available`,
  `version` and `audioRenderers(context, handler, listener, audioSink)`. The
  stub returns `false`/`null`/empty. The real one also returns empty when
  `FfmpegLibrary.isAvailable()` is false (native library failed to load), so a
  packaging accident degrades to the pre-extension behaviour instead of
  crashing.
- `ExoPlayerEngine.buildRenderersFactory()` and
  `Media3VodEngine.buildRenderersFactory()` override `buildAudioRenderers` and
  arrange the list through `FfmpegAudioRendererOrder` (pure, unit-tested):
  - default: `[MediaCodecAudioRenderer, FfmpegAudioRenderer, (live only)
    KululuMpegAudioRenderer]`. Platform/hardware decoders keep priority;
    FFmpeg only claims formats they reject. This is the audio-only equivalent
    of `EXTENSION_RENDERER_MODE_ON`; video renderers are untouched.
  - `preferSoftwareAudio` (constrained/compatibility devices) and passthrough
    off: a `CodecGatedAudioRenderer`-wrapped FFmpeg renderer restricted to
    `audio/ac3`, `audio/eac3`, `audio/eac3-joc` goes first, so Dolby decodes to
    PCM predictably; everything else keeps the default order.
  - passthrough on: FFmpeg stays strictly last (software decode would defeat
    the HDMI bitstream handoff the user opted into).
- The silent-audio detection (`checkTrackSupport` /
  `ExoPlaybackFailureClassifier.classifyTracks`) reads
  `Tracks.Group.isTrackSupported`, which aggregates the capabilities of every
  audio renderer. With FFmpeg present the formats it decodes report
  `FORMAT_HANDLED`, so no false fallback fires; a format FFmpeg was not built
  with (or a decoder that fails at init, surfaced through `onPlayerError`) still
  ends in `onAudioUnavailable()` and the libVLC ladder. No FFmpeg-specific
  branch exists in the detection code, by design.
- `proguard-rules.pro` keeps `androidx.media3.decoder.ffmpeg.**` (JNI looks
  Java methods up by name) and `-dontwarn`s the package so R8 stays quiet in
  a build without the AAR.

## How to bump versions

1. Media3: change every `androidx.media3:*` version in
   `IptvPlayer/app/build.gradle.kts` **and** `MEDIA3_VERSION` in
   `scripts/ffmpeg/versions.env`. Both the script and the CI job refuse a
   mismatch. Check `libraries/decoder_ffmpeg/README.md` at the new tag for a
   changed FFmpeg/NDK recommendation.
2. FFmpeg: set `FFMPEG_TAG` to a release tag (`nX.Y.Z`). Stay on the line the
   Media3 README recommends; newer majors have broken the JNI glue before.
3. NDK/CMake: `NDK_VERSION` / `CMAKE_VERSION`. The NDK must ship
   `toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi21-clang`.
4. Decoders: edit `ENABLED_DECODERS`. Only audio decoders are useful; the
   module has no video renderer. Names are FFmpeg decoder names
   (`dca` = DTS, `mlp`/`truehd` = Dolby TrueHD family).
5. Any change to the three files under `scripts/ffmpeg/` changes the cache
   key, so the next CI run rebuilds automatically. Cache entries expire after
   7 days without use; a rebuild is normal after a quiet week.

Local (Linux/macOS) manual build: install the pinned NDK and CMake via
`sdkmanager`, export `ANDROID_HOME`, run
`bash scripts/ffmpeg/build_media3_ffmpeg.sh`, then build the app normally.
Windows is unsupported for the FFmpeg step; copy the CI artifact into
`IptvPlayer/app/libs/` instead. Do not commit the AAR (binary, ~10 MB,
regenerated by CI); `IptvPlayer/.gitignore` already excludes the AAR and its
`.txt` sidecar.

## How to verify on a device

1. Confirm the build carries the extension: `BuildConfig.FFMPEG_AUDIO` is
   true, and `unzip -l app-release.apk | grep libffmpegJNI` lists all ABIs.
2. Play a channel/film with MP2, AC-3, E-AC-3 or DTS audio and watch logcat:

   ```
   adb logcat -s FfmpegAudioRenderer FfmpegLibrary ExoPlayerImpl DecoderAudioRenderer PlaybackLog
   ```

   Expected: `FfmpegLibrary` prints no "No <codec> decoder available" warning;
   the app's PlaybackLog line
   `audioRenderers=MediaCodecAudioRenderer,FfmpegAudioRenderer,... ffmpeg=true ffmpegVersion=...`
   (live engine); and the audio decoder initialised event names
   `ffmpeg` (`audioDecoderInitialized ... ffmpeg`) instead of an OMX/c2
   codec or a libVLC fallback. `ExoPlayerImpl` logs the registered modules;
   `media3.decoder.ffmpeg` must be listed.
3. Negative check: on the same device with passthrough ON in settings, AC-3
   should still go to the platform renderer (log shows the OMX/c2 decoder or
   passthrough), because FFmpeg is only trailing in that mode.
4. Fallback still intact: a stream with a codec FFmpeg was not built with
   (e.g. WMA) must still reach `audio track unsupported -> audio compatibility
   fallback` in PlaybackLog and switch to libVLC.

`FfmpegLibrary.getVersion()` (surfaced as `ffmpegVersion=` in the renderer
log line) is the linked libavcodec version and must match the pinned tag
(`6.0.1` for `n6.0.1`).

## Licence notes

- Media3 itself and the extension's Java/JNI glue are Apache-2.0.
- FFmpeg as configured here is **LGPL-2.1-or-later** (no GPL or nonfree
  components; enforced by the build script). Statically linking `libavcodec`,
  `libavutil` and `libswresample` into `libffmpegJNI.so` is fine under LGPL
  section 6 as long as the app: (a) reproduces the LGPL text, (b) states that
  FFmpeg is used and where its source lives, (c) lets users relink; on
  Android the practical reading is publishing the exact FFmpeg source/tag and
  build configuration, which `versions.env` plus the sidecar `.txt` provide.
- The app already ships `assets/licenses/LGPL-2.1.txt` for JLayer, so the
  licence text is covered, and `assets/licenses/FFmpeg-NOTICE.txt` names the
  FFmpeg tag, source location, enabled decoders and the Media3 extension.
  Still open: the release job's packaging check does not yet require
  `assets/licenses/FFmpeg-NOTICE.txt`, and nothing in the app surfaces the
  notice files by name (the licence screen would need to list it next to the
  JLayer notice).
- Codec patents (AC-3, E-AC-3, DTS, AAC, MLP/TrueHD) are a separate matter
  from copyright; the LGPL grant does not cover them. Check the distribution
  region's obligations before shipping outside the existing markets.
