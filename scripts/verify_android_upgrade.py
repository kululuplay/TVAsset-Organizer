#!/usr/bin/env python3
"""Static signed-APK and upgrade compatibility gate (no emulator/adb needed)."""

from __future__ import annotations

import argparse
import dataclasses
import pathlib
import re
import subprocess
import sys
from collections.abc import Sequence


PACKAGE_RE = re.compile(
    r"^package:\s+name='(?P<package>[^']+)'\s+"
    r"versionCode='(?P<code>\d+)'\s+versionName='(?P<name>[^']*)'",
    re.MULTILINE,
)
CERT_RE = re.compile(
    r"certificate\s+SHA-256\s+digest\s*:\s*([0-9a-f:]{64,})",
    re.IGNORECASE,
)
SCHEME_RE_TEMPLATE = r"^Verified using v{scheme} scheme[^:]*:\s*true\s*$"
SDK_RE = re.compile(r"^sdkVersion:'(?P<sdk>\d+)'", re.MULTILINE)
# Machine-readable first line of the GitHub release body: the in-app updater
# (1.5.97+) reads it to skip releases the device cannot run (UPDATE_ROLLOUT.md).
MIN_ANDROID_API_RE = re.compile(r"^Min-Android-API:\s*(?P<api>\d+)$")
# Clients before 1.5.76 show the release body verbatim in an update dialog
# whose notes pane cannot scroll; a longer body hides the Update button.
MAX_RELEASE_BODY_CHARS = 600


class VerificationError(RuntimeError):
    """Raised when an APK cannot safely replace the installed release."""


@dataclasses.dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    version_name: str
    certificate_sha256: frozenset[str]
    min_sdk: int


def parse_badging(output: str) -> tuple[str, int, str]:
    match = PACKAGE_RE.search(output)
    if not match:
        raise VerificationError("aapt did not return package/version metadata")
    return match.group("package"), int(match.group("code")), match.group("name")


def parse_min_sdk(output: str) -> int:
    match = SDK_RE.search(output)
    if not match:
        raise VerificationError("aapt did not return the APK minSdkVersion (sdkVersion)")
    return int(match.group("sdk"))


def parse_min_android_api(release_notes: str) -> int | None:
    """The `Min-Android-API: <api>` marker, which must be the first non-blank line."""
    for line in release_notes.splitlines():
        if not line.strip():
            continue
        match = MIN_ANDROID_API_RE.match(line.strip())
        return int(match.group("api")) if match else None
    return None


def parse_apksigner(output: str) -> frozenset[str]:
    for scheme in (1, 2):
        if not re.search(
            SCHEME_RE_TEMPLATE.format(scheme=scheme),
            output,
            re.IGNORECASE | re.MULTILINE,
        ):
            raise VerificationError(f"APK signature scheme v{scheme} is not verified")
    digests = frozenset(
        match.replace(":", "").lower() for match in CERT_RE.findall(output)
    )
    if not digests or any(len(digest) != 64 for digest in digests):
        raise VerificationError("apksigner did not return a valid signer SHA-256")
    return digests


def run(command: Sequence[str]) -> str:
    completed = subprocess.run(
        list(command),
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if completed.returncode != 0:
        rendered = " ".join(command[:2])
        raise VerificationError(f"{rendered} failed:\n{completed.stdout.strip()}")
    return completed.stdout


def inspect_apk(apk: pathlib.Path, aapt: pathlib.Path, apksigner: pathlib.Path) -> ApkIdentity:
    if not apk.is_file():
        raise VerificationError(f"APK does not exist: {apk}")
    badging = run((str(aapt), "dump", "badging", str(apk)))
    package, version_code, version_name = parse_badging(badging)
    min_sdk = parse_min_sdk(badging)
    certificates = parse_apksigner(
        run((str(apksigner), "verify", "--verbose", "--print-certs", str(apk)))
    )
    return ApkIdentity(package, version_code, version_name, certificates, min_sdk)


def verify_upgrade(
    current: ApkIdentity,
    previous: ApkIdentity | None,
    *,
    expected_package: str,
    expected_version_code: int,
    expected_version_name: str,
    allow_same_version: bool,
    expected_min_sdk: int | None = None,
    release_notes: str | None = None,
) -> None:
    if current.package != expected_package:
        raise VerificationError(
            f"package mismatch: APK={current.package!r}, expected={expected_package!r}"
        )
    if current.version_code != expected_version_code:
        raise VerificationError(
            "versionCode mismatch: "
            f"APK={current.version_code}, Gradle={expected_version_code}"
        )
    if current.version_name != expected_version_name:
        raise VerificationError(
            "versionName mismatch: "
            f"APK={current.version_name!r}, Gradle={expected_version_name!r}"
        )
    if expected_min_sdk is not None and current.min_sdk != expected_min_sdk:
        raise VerificationError(
            f"minSdk mismatch: APK={current.min_sdk}, Gradle={expected_min_sdk}"
        )
    if release_notes is not None:
        if len(release_notes) > MAX_RELEASE_BODY_CHARS:
            raise VerificationError(
                f"release body is {len(release_notes)} characters; at most "
                f"{MAX_RELEASE_BODY_CHARS} fit the update dialog of clients before 1.5.76"
            )
        declared = parse_min_android_api(release_notes)
        if declared is None:
            raise VerificationError(
                "release notes must start with 'Min-Android-API: <api>'; "
                "the in-app updater reads it from the release body"
            )
        if declared != current.min_sdk:
            raise VerificationError(
                f"Min-Android-API mismatch: release notes={declared}, APK={current.min_sdk}"
            )
    if previous is None:
        return
    if current.package != previous.package:
        raise VerificationError(
            f"upgrade package changed: {previous.package!r} -> {current.package!r}"
        )
    if current.certificate_sha256 != previous.certificate_sha256:
        raise VerificationError("release signing certificate does not match previous APK")
    if current.min_sdk < previous.min_sdk:
        # Clients only ever step forward; a lower minSdk would let a device
        # skip the release that first required its current Android version.
        raise VerificationError(
            "minSdkVersion must not decrease between releases: "
            f"{previous.min_sdk} -> {current.min_sdk}"
        )
    if allow_same_version:
        if (
            current.version_code != previous.version_code
            or current.version_name != previous.version_name
        ):
            raise VerificationError(
                "existing immutable tag must rebuild the same version metadata: "
                f"{previous.version_name}/{previous.version_code} -> "
                f"{current.version_name}/{current.version_code}"
            )
        valid_code = True
        requirement = "equal to"
    else:
        valid_code = current.version_code > previous.version_code
        requirement = "strictly greater than"
    if not valid_code:
        raise VerificationError(
            f"versionCode must be {requirement} previous release: "
            f"{current.version_code} vs {previous.version_code}"
        )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--new-apk", required=True, type=pathlib.Path)
    result.add_argument("--previous-apk", type=pathlib.Path)
    result.add_argument("--expected-package", required=True)
    result.add_argument("--expected-version-code", required=True, type=int)
    result.add_argument("--expected-version-name", required=True)
    result.add_argument("--aapt", required=True, type=pathlib.Path)
    result.add_argument("--apksigner", required=True, type=pathlib.Path)
    result.add_argument(
        "--expected-min-sdk",
        type=int,
        help="minSdk from Gradle; must equal the APK's sdkVersion.",
    )
    result.add_argument(
        "--release-notes",
        type=pathlib.Path,
        help="Release body about to be published; its first line must declare Min-Android-API.",
    )
    result.add_argument(
        "--allow-same-version",
        action="store_true",
        help="Only for rebuilding an immutable tag that already exists.",
    )
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        current = inspect_apk(args.new_apk, args.aapt, args.apksigner)
        previous = (
            inspect_apk(args.previous_apk, args.aapt, args.apksigner)
            if args.previous_apk
            else None
        )
        release_notes = None
        if args.release_notes is not None:
            try:
                release_notes = args.release_notes.read_text(encoding="utf-8")
            except OSError as error:
                raise VerificationError(f"cannot read release notes: {error}") from error
        verify_upgrade(
            current,
            previous,
            expected_package=args.expected_package,
            expected_version_code=args.expected_version_code,
            expected_version_name=args.expected_version_name,
            allow_same_version=args.allow_same_version,
            expected_min_sdk=args.expected_min_sdk,
            release_notes=release_notes,
        )
    except VerificationError as error:
        print(f"APK verification failed: {error}", file=sys.stderr)
        return 1
    print(
        "APK verified: "
        f"{current.package} v{current.version_name} ({current.version_code}), "
        f"minSdk={current.min_sdk}, "
        f"signer={','.join(sorted(current.certificate_sha256))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
