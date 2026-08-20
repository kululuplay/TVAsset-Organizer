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
    r"^Signer\s+#\d+\s+certificate\s+SHA-256\s+digest:\s*([0-9a-f:]+)\s*$",
    re.IGNORECASE | re.MULTILINE,
)
SCHEME_RE_TEMPLATE = r"^Verified using v{scheme} scheme[^:]*:\s*true\s*$"


class VerificationError(RuntimeError):
    """Raised when an APK cannot safely replace the installed release."""


@dataclasses.dataclass(frozen=True)
class ApkIdentity:
    package: str
    version_code: int
    version_name: str
    certificate_sha256: frozenset[str]


def parse_badging(output: str) -> tuple[str, int, str]:
    match = PACKAGE_RE.search(output)
    if not match:
        raise VerificationError("aapt did not return package/version metadata")
    return match.group("package"), int(match.group("code")), match.group("name")


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
    package, version_code, version_name = parse_badging(
        run((str(aapt), "dump", "badging", str(apk)))
    )
    certificates = parse_apksigner(
        run((str(apksigner), "verify", "--verbose", "--print-certs", str(apk)))
    )
    return ApkIdentity(package, version_code, version_name, certificates)


def verify_upgrade(
    current: ApkIdentity,
    previous: ApkIdentity | None,
    *,
    expected_package: str,
    expected_version_code: int,
    expected_version_name: str,
    allow_same_version: bool,
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
    if previous is None:
        return
    if current.package != previous.package:
        raise VerificationError(
            f"upgrade package changed: {previous.package!r} -> {current.package!r}"
        )
    if current.certificate_sha256 != previous.certificate_sha256:
        raise VerificationError("release signing certificate does not match previous APK")
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
        verify_upgrade(
            current,
            previous,
            expected_package=args.expected_package,
            expected_version_code=args.expected_version_code,
            expected_version_name=args.expected_version_name,
            allow_same_version=args.allow_same_version,
        )
    except VerificationError as error:
        print(f"APK verification failed: {error}", file=sys.stderr)
        return 1
    print(
        "APK verified: "
        f"{current.package} v{current.version_name} ({current.version_code}), "
        f"signer={','.join(sorted(current.certificate_sha256))}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
