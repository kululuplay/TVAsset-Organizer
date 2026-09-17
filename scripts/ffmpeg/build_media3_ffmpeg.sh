#!/usr/bin/env bash
#
# Build the Media3 FFmpeg audio decoder extension (lib-decoder-ffmpeg) from
# source and drop the AAR into IptvPlayer/app/libs/ where app/build.gradle.kts
# picks it up automatically (BuildConfig.FFMPEG_AUDIO = true).
#
# Runs on Linux (GitHub Actions ubuntu runner). Everything is pinned in
# scripts/ffmpeg/versions.env. The script is idempotent: an existing checkout
# at the right tag is reused, an already compiled FFmpeg (all four ABIs) is not
# rebuilt, and the final AAR is only replaced when it was rebuilt.
#
# Usage:
#   scripts/ffmpeg/build_media3_ffmpeg.sh
#
# Environment (all optional):
#   ANDROID_HOME / ANDROID_SDK_ROOT  Android SDK with the pinned NDK + CMake
#   NDK_PATH                         explicit NDK directory (overrides SDK lookup)
#   FFMPEG_WORK_DIR                  scratch directory for the clones/build
#   FFMPEG_FORCE_REBUILD=1           ignore the idempotency shortcuts
#
set -euo pipefail

log() { printf '\n[ffmpeg-extension] %s\n' "$*"; }
die() { printf '\n[ffmpeg-extension] ERROR: %s\n' "$*" >&2; exit 1; }
need_tool() { command -v "$1" >/dev/null 2>&1 || die "required tool '$1' is not installed"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
APP_DIR="${REPO_ROOT}/IptvPlayer"
APP_GRADLE="${APP_DIR}/app/build.gradle.kts"
OUTPUT_AAR="${APP_DIR}/app/libs/media3-decoder-ffmpeg-release.aar"

# shellcheck source=versions.env
source "${SCRIPT_DIR}/versions.env"
: "${MEDIA3_VERSION:?}" "${FFMPEG_TAG:?}" "${NDK_VERSION:?}" "${CMAKE_VERSION:?}" \
  "${ANDROID_ABI_LEVEL:?}" "${ENABLED_DECODERS:?}"

for tool in git bash make nproc java unzip find sed grep; do need_tool "$tool"; done
# FFmpeg's configure/make need these host tools; NDK clang provides the rest.
for tool in pkg-config nasm; do
  command -v "$tool" >/dev/null 2>&1 || log "note: '$tool' not found (x86 asm is disabled by build_ffmpeg.sh, so this is fine)"
done

case "$(uname -s)" in
  Linux) HOST_PLATFORM="linux-x86_64" ;;
  Darwin) HOST_PLATFORM="darwin-x86_64" ;;
  *) die "unsupported host $(uname -s); this build runs on Linux (CI) or macOS" ;;
esac

# ---------------------------------------------------------------------------
# Guard: the extension must match the media3 line the app compiles against.
# ---------------------------------------------------------------------------
[ -f "$APP_GRADLE" ] || die "app build script not found at $APP_GRADLE"
app_media3="$(sed -n 's/.*"androidx\.media3:media3-exoplayer:\([^"]*\)".*/\1/p' "$APP_GRADLE" | head -n 1)"
[ -n "$app_media3" ] || die "could not read the androidx.media3 version from $APP_GRADLE"
[ "$app_media3" = "$MEDIA3_VERSION" ] || die "media3 mismatch: app uses $app_media3, versions.env pins $MEDIA3_VERSION"

# ---------------------------------------------------------------------------
# Locate the NDK and CMake.
# ---------------------------------------------------------------------------
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${NDK_PATH:-}" ]; then
  [ -n "$SDK_ROOT" ] || die "set ANDROID_HOME (or NDK_PATH) so the pinned NDK $NDK_VERSION can be found"
  NDK_PATH="${SDK_ROOT}/ndk/${NDK_VERSION}"
fi
[ -d "$NDK_PATH/toolchains/llvm/prebuilt/${HOST_PLATFORM}/bin" ] \
  || die "NDK $NDK_VERSION not found at $NDK_PATH (install 'ndk;${NDK_VERSION}' with sdkmanager)"
if [ -n "$SDK_ROOT" ] && [ ! -x "${SDK_ROOT}/cmake/${CMAKE_VERSION}/bin/cmake" ]; then
  die "CMake ${CMAKE_VERSION} not found under ${SDK_ROOT}/cmake (install 'cmake;${CMAKE_VERSION}' with sdkmanager)"
fi
ndk_source_props="$NDK_PATH/source.properties"
if [ -f "$ndk_source_props" ]; then
  ndk_actual="$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk_source_props" | head -n 1)"
  [ "$ndk_actual" = "$NDK_VERSION" ] || die "NDK at $NDK_PATH reports $ndk_actual, expected $NDK_VERSION"
fi

WORK_DIR="${FFMPEG_WORK_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/media3-ffmpeg-build}"
MEDIA_DIR="${WORK_DIR}/media"
FFMPEG_DIR="${WORK_DIR}/ffmpeg"
FORCE="${FFMPEG_FORCE_REBUILD:-0}"
mkdir -p "$WORK_DIR"

log "media3 $MEDIA3_VERSION | FFmpeg $FFMPEG_TAG | NDK $NDK_VERSION ($NDK_PATH) | ABI level $ANDROID_ABI_LEVEL"
log "decoders: $ENABLED_DECODERS"
log "work dir: $WORK_DIR"

# ---------------------------------------------------------------------------
# Checkout helper: shallow clone of one tag, verified after checkout.
# ---------------------------------------------------------------------------
checkout_tag() {
  local url="$1" tag="$2" dir="$3"
  if [ -d "$dir/.git" ]; then
    local current
    current="$(git -C "$dir" describe --tags --exact-match 2>/dev/null || true)"
    if [ "$current" = "$tag" ] && [ "$FORCE" != "1" ]; then
      log "$dir already at $tag (reused)"
      return
    fi
    log "$dir is at '${current:-unknown}', fetching $tag"
    git -C "$dir" fetch --depth 1 origin "refs/tags/${tag}:refs/tags/${tag}"
    git -C "$dir" checkout --force "tags/${tag}"
    git -C "$dir" clean -fdx
  else
    log "cloning $url @ $tag"
    git clone --depth 1 --branch "$tag" "$url" "$dir"
  fi
  local resolved
  resolved="$(git -C "$dir" describe --tags --exact-match)"
  [ "$resolved" = "$tag" ] || die "$dir resolved to '$resolved', expected '$tag'"
  log "$dir at $(git -C "$dir" rev-parse HEAD) ($tag)"
}

checkout_tag "https://github.com/androidx/media.git" "$MEDIA3_VERSION" "$MEDIA_DIR"
checkout_tag "https://github.com/FFmpeg/FFmpeg.git" "$FFMPEG_TAG" "$FFMPEG_DIR"

FFMPEG_MODULE_PATH="${MEDIA_DIR}/libraries/decoder_ffmpeg/src/main"
JNI_DIR="${FFMPEG_MODULE_PATH}/jni"
[ -x "${JNI_DIR}/build_ffmpeg.sh" ] || die "build_ffmpeg.sh missing in media checkout: ${JNI_DIR}"
[ -f "${MEDIA_DIR}/libraries/decoder_ffmpeg/README.md" ] || die "decoder_ffmpeg README missing; wrong media checkout?"
log "README recommendation: $(grep -m1 -oE 'release/[0-9.]+' "${MEDIA_DIR}/libraries/decoder_ffmpeg/README.md" || echo '?') (pinned tag: $FFMPEG_TAG)"

# Link the FFmpeg tree where the extension's CMake/build script expect it.
if [ -L "${JNI_DIR}/ffmpeg" ] || [ -e "${JNI_DIR}/ffmpeg" ]; then
  rm -rf "${JNI_DIR}/ffmpeg"
fi
ln -s "$FFMPEG_DIR" "${JNI_DIR}/ffmpeg"

# ---------------------------------------------------------------------------
# Build FFmpeg static libs for the four ABIs (LGPL default configuration).
# ---------------------------------------------------------------------------
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)
ffmpeg_built() {
  local abi
  for abi in "${ABIS[@]}"; do
    for lib in libavcodec.a libavutil.a libswresample.a; do
      [ -f "${FFMPEG_DIR}/android-libs/${abi}/${lib}" ] || return 1
    done
  done
  [ -f "${FFMPEG_DIR}/android-libs/.decoders" ] \
    && [ "$(cat "${FFMPEG_DIR}/android-libs/.decoders")" = "${FFMPEG_TAG} ${ENABLED_DECODERS}" ]
}

if ffmpeg_built && [ "$FORCE" != "1" ]; then
  log "FFmpeg static libraries already built for ${ABIS[*]} (reused)"
else
  log "building FFmpeg for ${ABIS[*]}"
  rm -rf "${FFMPEG_DIR}/android-libs"
  # shellcheck disable=SC2086
  (cd "$JNI_DIR" && ./build_ffmpeg.sh \
      "$FFMPEG_MODULE_PATH" "$NDK_PATH" "$HOST_PLATFORM" "$ANDROID_ABI_LEVEL" $ENABLED_DECODERS)
  printf '%s %s' "$FFMPEG_TAG" "$ENABLED_DECODERS" > "${FFMPEG_DIR}/android-libs/.decoders"
  ffmpeg_built || die "FFmpeg build finished but static libraries are missing"
fi

# Sanity: never ship GPL/nonfree code. config.h reflects the last configure run.
if grep -qE '^#define (CONFIG_GPL|CONFIG_NONFREE) 1' "${FFMPEG_DIR}/config.h" 2>/dev/null; then
  die "FFmpeg was configured with GPL/nonfree components; the app ships under LGPL terms only"
fi
log "FFmpeg license mode: LGPL (no CONFIG_GPL / CONFIG_NONFREE)"

# ---------------------------------------------------------------------------
# Build the extension AAR (JNI wrapper + Java renderer) inside the media checkout.
# ---------------------------------------------------------------------------
log "assembling lib-decoder-ffmpeg release AAR"
(
  cd "$MEDIA_DIR"
  chmod +x ./gradlew
  NDK_VERSION="$NDK_VERSION" ./gradlew --no-daemon \
    --init-script "${SCRIPT_DIR}/ndk-version.init.gradle" \
    :lib-decoder-ffmpeg:assembleRelease \
    --stacktrace
)

built_aar="$(find "${MEDIA_DIR}/libraries/decoder_ffmpeg" -type f -name 'lib-decoder-ffmpeg-release.aar' | head -n 1)"
[ -n "$built_aar" ] || die "AAR not produced under ${MEDIA_DIR}/libraries/decoder_ffmpeg"

# Verify the AAR carries native code for every ABI the app ships.
entries="$(unzip -Z1 "$built_aar")"
for abi in "${ABIS[@]}"; do
  printf '%s\n' "$entries" | grep -qx "jni/${abi}/libffmpegJNI.so" \
    || die "AAR is missing jni/${abi}/libffmpegJNI.so"
done
printf '%s\n' "$entries" | grep -q '^classes.jar$' || die "AAR is missing classes.jar"

mkdir -p "$(dirname "$OUTPUT_AAR")"
cp -f "$built_aar" "$OUTPUT_AAR"
cat > "${OUTPUT_AAR%.aar}.txt" <<EOF
media3=${MEDIA3_VERSION}
media3_commit=$(git -C "$MEDIA_DIR" rev-parse HEAD)
ffmpeg=${FFMPEG_TAG}
ffmpeg_commit=$(git -C "$FFMPEG_DIR" rev-parse HEAD)
ndk=${NDK_VERSION}
abi_level=${ANDROID_ABI_LEVEL}
decoders=${ENABLED_DECODERS}
license=LGPL-2.1-or-later (FFmpeg default configuration)
EOF
log "wrote $OUTPUT_AAR ($(du -h "$OUTPUT_AAR" | cut -f1)) and ${OUTPUT_AAR%.aar}.txt"
