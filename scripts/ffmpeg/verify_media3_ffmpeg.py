#!/usr/bin/env python3
"""Reject a stale FFmpeg AAR/sidecar before Gradle loads extension classes."""
from __future__ import annotations

import argparse
import hashlib
import io
from pathlib import Path
import re
import shlex
from zipfile import ZipFile

SCRIPT_DIR = Path(__file__).resolve().parent
RECIPE_FILES = (
    "versions.env", "build_media3_ffmpeg.sh", "ndk-version.init.gradle",
    "verify_media3_ffmpeg.py",
)
ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def read_pins(path: Path) -> dict[str, str]:
    pins = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        fields = shlex.split(line, comments=True)
        if not fields:
            continue
        if len(fields) != 1 or "=" not in fields[0]:
            raise ValueError(f"Invalid pin in {path.name}")
        key, value = fields[0].split("=", 1)
        pins[key] = value
    return pins


def recipe_hash(script_dir: Path = SCRIPT_DIR) -> str:
    digest = hashlib.sha256()
    for name in RECIPE_FILES:
        digest.update(name.encode() + b"\0")
        # Checkout line endings must not make Linux CI artifacts invalid on Windows.
        digest.update((script_dir / name).read_bytes().replace(b"\r\n", b"\n"))
        digest.update(b"\0")
    return digest.hexdigest()


def expected_fields(pins: dict[str, str], script_dir: Path = SCRIPT_DIR) -> dict[str, str]:
    return {
        "media3": pins["MEDIA3_VERSION"], "ffmpeg": pins["FFMPEG_TAG"],
        "ndk": pins["NDK_VERSION"], "cmake": pins["CMAKE_VERSION"],
        "abi_level": pins["ANDROID_ABI_LEVEL"], "decoders": pins["ENABLED_DECODERS"],
        "build_recipe_sha256": recipe_hash(script_dir),
        "license": "LGPL-2.1-or-later (FFmpeg default configuration)",
    }


def validate_app(app_gradle: Path, pins: dict[str, str]) -> None:
    source = app_gradle.read_text(encoding="utf-8")
    versions = set(re.findall(r'"androidx\.media3:[^:\"]+:([^\"]+)"', source))
    if versions != {pins["MEDIA3_VERSION"]}:
        raise ValueError("App Media3 dependencies and FFmpeg pin must all match")
    minimum = re.search(r"\bminSdk\s*=\s*(\d+)", source)
    if not minimum or minimum[1] != pins["ANDROID_ABI_LEVEL"]:
        raise ValueError("App minSdk and FFmpeg native ABI level must match")


def verify(aar: Path, app_gradle: Path, script_dir: Path = SCRIPT_DIR) -> dict[str, str]:
    pins = read_pins(script_dir / "versions.env")
    validate_app(app_gradle, pins)
    sidecar = aar.with_suffix(".txt")
    fields = {}
    for line in sidecar.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if not separator or key in fields:
            raise ValueError("Malformed or duplicate FFmpeg provenance field")
        fields[key] = value
    for key, value in expected_fields(pins, script_dir).items():
        if fields.get(key) != value:
            raise ValueError(f"Stale FFmpeg provenance: {key}; rebuild/download the matching CI artifact")
    for key in ("media3_commit", "ffmpeg_commit"):
        if not re.fullmatch(r"[a-f0-9]{40}", fields.get(key, "")):
            raise ValueError(f"Missing source commit: {key}")
    if hashlib.sha256(aar.read_bytes()).hexdigest() != fields.get("aar_sha256"):
        raise ValueError("FFmpeg AAR SHA-256 does not match its provenance")
    with ZipFile(aar) as archive:
        if archive.testzip() is not None:
            raise ValueError("Corrupt FFmpeg AAR")
        for abi in ABIS:
            entry = f"jni/{abi}/libffmpegJNI.so"
            if archive.getinfo(entry).file_size == 0:
                raise ValueError(f"Empty FFmpeg native library: {abi}")
        with ZipFile(io.BytesIO(archive.read("classes.jar"))) as classes:
            for name in ("FfmpegAudioRenderer", "FfmpegLibrary", "FfmpegAudioDecoder"):
                classes.getinfo(f"androidx/media3/decoder/ffmpeg/{name}.class")
        properties = archive.read("META-INF/com/android/build/gradle/aar-metadata.properties").decode()
        minimum = re.search(r"^minCompileSdk=(\d+)\s*$", properties, re.MULTILINE)
        app_compile = re.search(r"\bcompileSdk\s*=\s*(\d+)", app_gradle.read_text(encoding="utf-8"))
        if not minimum or not app_compile or int(minimum[1]) > int(app_compile[1]):
            raise ValueError("FFmpeg AAR requires a higher/missing app compileSdk")
    return fields


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recipe-hash", action="store_true")
    parser.add_argument("--check-pins", action="store_true")
    parser.add_argument("--aar", type=Path, default=SCRIPT_DIR.parents[1] / "IptvPlayer/app/libs/media3-decoder-ffmpeg-release.aar")
    parser.add_argument("--app-gradle", type=Path, default=SCRIPT_DIR.parents[1] / "IptvPlayer/app/build.gradle.kts")
    args = parser.parse_args()
    if args.recipe_hash:
        print(recipe_hash())
        return
    if args.check_pins:
        validate_app(args.app_gradle, read_pins(SCRIPT_DIR / "versions.env"))
        print("App/FFmpeg Media3 and Android API pins match")
        return
    try:
        fields = verify(args.aar, args.app_gradle)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"FFmpeg extension verification failed: {error}\n")
    print(f"FFmpeg extension verified: Media3 {fields['media3']}, native API {fields['abi_level']}, four ABIs, SHA-256 {fields['aar_sha256']}")


if __name__ == "__main__":
    main()
