import unittest

from verify_android_upgrade import (
    ApkIdentity,
    VerificationError,
    parse_apksigner,
    parse_badging,
    verify_upgrade,
)


CERT_A = "a" * 64
CERT_B = "b" * 64


class VerifyAndroidUpgradeTest(unittest.TestCase):
    def identity(self, code=123, name="1.5.79", cert=CERT_A, package="com.iptv.player"):
        return ApkIdentity(package, code, name, frozenset({cert}))

    def test_parses_aapt_badging_deterministically(self):
        output = "package: name='com.iptv.player' versionCode='123' versionName='1.5.79' platformBuildVersionName=''"
        self.assertEqual(parse_badging(output), ("com.iptv.player", 123, "1.5.79"))

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


if __name__ == "__main__":
    unittest.main()
