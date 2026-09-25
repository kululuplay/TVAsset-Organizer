import unittest

from verify_android_upgrade import (
    ApkIdentity,
    VerificationError,
    parse_apksigner,
    parse_badging,
    parse_min_android_api,
    parse_min_sdk,
    verify_upgrade,
)


CERT_A = "a" * 64
CERT_B = "b" * 64
BADGING = (
    "package: name='com.iptv.player' versionCode='123' versionName='1.5.79' platformBuildVersionName=''\n"
    "sdkVersion:'23'\n"
    "targetSdkVersion:'34'\n"
)


class VerifyAndroidUpgradeTest(unittest.TestCase):
    def identity(self, code=123, name="1.5.79", cert=CERT_A, package="com.iptv.player", min_sdk=23):
        return ApkIdentity(package, code, name, frozenset({cert}), min_sdk)

    def verify(self, current, previous=None, **overrides):
        arguments = dict(
            expected_package=current.package,
            expected_version_code=current.version_code,
            expected_version_name=current.version_name,
            allow_same_version=False,
        )
        arguments.update(overrides)
        verify_upgrade(current, previous, **arguments)

    def test_parses_aapt_badging_deterministically(self):
        self.assertEqual(parse_badging(BADGING), ("com.iptv.player", 123, "1.5.79"))

    def test_parses_min_sdk_from_badging(self):
        self.assertEqual(parse_min_sdk(BADGING), 23)
        with self.assertRaisesRegex(VerificationError, "minSdkVersion"):
            parse_min_sdk(BADGING.replace("sdkVersion:'23'\n", ""))

    def test_requires_v1_v2_and_certificate(self):
        output = """Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Signer #1 certificate SHA-256 digest: aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa:aa
"""
        self.assertEqual(parse_apksigner(output), frozenset({"aa" * 32}))
        with self.assertRaisesRegex(VerificationError, "scheme v2"):
            parse_apksigner(output.replace("v2 scheme", "v9 scheme"))

    def test_accepts_build_tools_certificate_line_prefixes_and_suffixes(self):
        output = """Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
INFO  Signer #1 certificate SHA-256 digest: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA (verified)
"""
        self.assertEqual(parse_apksigner(output), frozenset({"aa" * 32}))

    def test_accepts_signed_monotonic_upgrade(self):
        verify_upgrade(
            self.identity(code=123),
            self.identity(code=122, name="1.5.78"),
            expected_package="com.iptv.player",
            expected_version_code=123,
            expected_version_name="1.5.79",
            allow_same_version=False,
        )

    def test_rejects_certificate_package_and_version_regressions(self):
        cases = [
            (self.identity(cert=CERT_B), self.identity(code=122), "certificate"),
            (
                self.identity(package="com.example.other"),
                self.identity(code=122),
                "package mismatch",
            ),
            (self.identity(code=122), self.identity(code=122), "strictly greater"),
        ]
        for current, previous, message in cases:
            with self.subTest(message=message), self.assertRaisesRegex(
                VerificationError, message
            ):
                verify_upgrade(
                    current,
                    previous,
                    expected_package="com.iptv.player",
                    expected_version_code=current.version_code,
                    expected_version_name=current.version_name,
                    allow_same_version=False,
                )

    def test_same_version_is_only_allowed_for_existing_immutable_tag(self):
        verify_upgrade(
            self.identity(code=122, name="1.5.78"),
            self.identity(code=122, name="1.5.78"),
            expected_package="com.iptv.player",
            expected_version_code=122,
            expected_version_name="1.5.78",
            allow_same_version=True,
        )
        with self.assertRaisesRegex(VerificationError, "same version metadata"):
            verify_upgrade(
                self.identity(code=123, name="1.5.78"),
                self.identity(code=122, name="1.5.78"),
                expected_package="com.iptv.player",
                expected_version_code=123,
                expected_version_name="1.5.78",
                allow_same_version=True,
            )

    def test_min_sdk_must_match_gradle(self):
        self.verify(self.identity(), expected_min_sdk=23)
        with self.assertRaisesRegex(VerificationError, "minSdk mismatch"):
            self.verify(self.identity(min_sdk=23), expected_min_sdk=26)

    def test_min_sdk_never_decreases_between_releases(self):
        previous = self.identity(code=122, name="1.5.78", min_sdk=23)
        self.verify(self.identity(min_sdk=23), previous)
        self.verify(self.identity(min_sdk=26), previous)
        with self.assertRaisesRegex(VerificationError, "must not decrease"):
            self.verify(self.identity(min_sdk=21), previous)

    def test_release_body_marker_is_read_from_the_first_line_only(self):
        self.assertEqual(parse_min_android_api("Min-Android-API: 23\n\nNotes"), 23)
        self.assertEqual(
            parse_min_android_api("\r\n\r\nMin-Android-API: 26\r\n- Faster zapping"), 26
        )
        for body in (
            "",
            "Notes first\nMin-Android-API: 23",
            "Min-Android-API: 23 (Android 6)",
            "Min-Android-API: six",
        ):
            with self.subTest(body=body):
                self.assertIsNone(parse_min_android_api(body))

    def test_release_body_stays_short_enough_for_old_update_dialogs(self):
        short = "Min-Android-API: 23\n\n" + "x" * 500 + "\n"
        self.verify(self.identity(min_sdk=23), release_notes=short)
        long = "Min-Android-API: 23\n\n" + "x" * 700 + "\n"
        with self.assertRaisesRegex(VerificationError, "release body is"):
            self.verify(self.identity(min_sdk=23), release_notes=long)

    def test_release_body_must_declare_the_apk_min_android_api(self):
        self.verify(self.identity(min_sdk=23), release_notes="Min-Android-API: 23\n\nNotes\n")
        with self.assertRaisesRegex(VerificationError, "must start with 'Min-Android-API"):
            self.verify(
                self.identity(min_sdk=23),
                release_notes="Bug fixes and stability improvements.\n",
            )
        with self.assertRaisesRegex(VerificationError, "Min-Android-API mismatch"):
            self.verify(self.identity(min_sdk=26), release_notes="Min-Android-API: 23\n")


if __name__ == "__main__":
    unittest.main()
