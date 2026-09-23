#!/usr/bin/env python3
"""Artifact contract tests; no SDK, network or native build needed."""
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile

from verify_media3_ffmpeg import ABIS, SCRIPT_DIR, expected_fields, read_pins, verify


class FfmpegArtifactTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.aar = self.root / "decoder.aar"
        self.gradle = self.root / "build.gradle.kts"
        self.pins = read_pins(SCRIPT_DIR / "versions.env")
        self.gradle.write_text(
            f'android {{ compileSdk = 36; defaultConfig {{ minSdk = {self.pins["ANDROID_ABI_LEVEL"]} }} }}\n'
            f'implementation("androidx.media3:media3-exoplayer:{self.pins["MEDIA3_VERSION"]}")\n',
            encoding="utf-8",
        )
        self.write_aar()

    def write_aar(self, missing_abi=None, missing_class=None, compile_sdk=36):
        jar = io.BytesIO()
        with ZipFile(jar, "w") as classes:
            for name in ("FfmpegLibrary", "FfmpegAudioRenderer", "FfmpegAudioDecoder"):
                if name != missing_class:
                    classes.writestr(f"androidx/media3/decoder/ffmpeg/{name}.class", b"fixture")
        with ZipFile(self.aar, "w") as archive:
            archive.writestr("classes.jar", jar.getvalue())
            archive.writestr("META-INF/com/android/build/gradle/aar-metadata.properties", f"minCompileSdk={compile_sdk}\n")
            for abi in ABIS:
                if abi != missing_abi:
                    archive.writestr(f"jni/{abi}/libffmpegJNI.so", b"fixture")
        self.fields = expected_fields(self.pins) | {
            "media3_commit": "a" * 40, "ffmpeg_commit": "b" * 40,
            "aar_sha256": hashlib.sha256(self.aar.read_bytes()).hexdigest(),
        }
        self.write_sidecar()

    def write_sidecar(self):
        self.aar.with_suffix(".txt").write_text(
            "".join(f"{key}={value}\n" for key, value in self.fields.items()), encoding="utf-8",
        )

    def test_matching_build_is_accepted(self):
        self.assertEqual(verify(self.aar, self.gradle)["media3"], self.pins["MEDIA3_VERSION"])

    def test_old_media3_api_toolchain_decoders_or_recipe_are_rejected(self):
        for key, value in (("media3", "1.8.1"), ("abi_level", "21"), ("ndk", "old"),
                           ("cmake", "old"), ("decoders", "mp3"), ("build_recipe_sha256", "0" * 64)):
            with self.subTest(field=key):
                original = self.fields[key]
                self.fields[key] = value
                self.write_sidecar()
                with self.assertRaisesRegex(ValueError, "Stale FFmpeg provenance"):
                    verify(self.aar, self.gradle)
                self.fields[key] = original

    def test_app_dependency_mismatch_is_rejected(self):
        self.gradle.write_text(self.gradle.read_text() + '\nimplementation("androidx.media3:media3-ui:1.8.1")')
        with self.assertRaisesRegex(ValueError, "dependencies"):
            verify(self.aar, self.gradle)

    def test_app_min_sdk_mismatch_is_rejected(self):
        self.gradle.write_text(self.gradle.read_text().replace("minSdk = 23", "minSdk = 21"))
        with self.assertRaisesRegex(ValueError, "minSdk"):
            verify(self.aar, self.gradle)

    def test_same_version_replaced_binary_is_rejected(self):
        self.aar.write_bytes(self.aar.read_bytes() + b"different artifact")
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            verify(self.aar, self.gradle)

    def test_missing_any_abi_is_rejected_even_with_matching_sha(self):
        for abi in ABIS:
            with self.subTest(abi=abi):
                self.write_aar(missing_abi=abi)
                with self.assertRaises(KeyError):
                    verify(self.aar, self.gradle)

    def test_missing_renderer_classes_is_rejected(self):
        self.write_aar(missing_class="FfmpegAudioRenderer")
        with self.assertRaises(KeyError):
            verify(self.aar, self.gradle)

    def test_missing_sidecar_is_rejected(self):
        self.aar.with_suffix(".txt").unlink()
        with self.assertRaises(FileNotFoundError):
            verify(self.aar, self.gradle)

    def test_source_commit_required(self):
        self.fields["media3_commit"] = "unknown"
        self.write_sidecar()
        with self.assertRaisesRegex(ValueError, "source commit"):
            verify(self.aar, self.gradle)

    def test_insufficient_app_compile_sdk_is_rejected(self):
        self.write_aar(compile_sdk=37)
        with self.assertRaisesRegex(ValueError, "compileSdk"):
            verify(self.aar, self.gradle)


if __name__ == "__main__":
    unittest.main()
