#!/usr/bin/env python3
"""Fail CI when credential-shaped values are committed in the current tree."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKIP = {
    "scripts/scan_secrets.py",
    ".env.example",
    "IptvPlayer/app/src/main/java/com/iptv/player/util/SensitiveDataRedactor.kt",
    "IptvPlayer/app/src/test/java/com/iptv/player/util/SensitiveDataRedactorTest.kt",
}
TEXT_SUFFIXES = {
    ".gradle",
    ".js",
    ".json",
    ".kts",
    ".kt",
    ".md",
    ".properties",
    ".sh",
    ".txt",
    ".xml",
    ".yml",
    ".yaml",
}

PLACEHOLDER = re.compile(
    r"(?i)^(?:user(?:name)?|pass(?:word)?|example|sample|test|demo|"
    r"replace[^ ]*|<[^>]+>|\$[a-z_]\w*|\$\{[^}]+\}|\{[^}]+\}|\{\{[^}]+\}\})$"
)
XTREAM_PATH = re.compile(
    r"(?i)/(?:live|movie|series)/([^/\s?#]+)/([^/\s?#]+)/"
)
QUERY_USER = re.compile(r"(?i)[?&](?:username|user)=([^&#\s]+)")
QUERY_PASS = re.compile(r"(?i)[?&](?:password|pass)=([^&#\s]+)")
HARDCODED_SERVICE_KEY = re.compile(
    r"""(?i)\b(?:TELEMETRY_INGEST_KEY|CRASH_INGEST_KEY|TMDB_API_KEY|"""
    r"""INGEST_KEY)\b\s*(?::\s*\w+\s*)?=\s*["']([A-Za-z0-9_-]{16,})["']"""
)
PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")


def tracked_files() -> list[str]:
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
    )
    return [item.decode("utf-8") for item in output.split(b"\0") if item]


def real(value: str) -> bool:
    cleaned = value.strip("\"'<>[]()")
    return bool(cleaned) and not PLACEHOLDER.fullmatch(cleaned)


def findings(path: str, text: str) -> list[str]:
    result: list[str] = []
    if PRIVATE_KEY.search(text):
        result.append("private key material")

    for match in HARDCODED_SERVICE_KEY.finditer(text):
        if real(match.group(1)):
            result.append("hard-coded service key")
            break

    for match in XTREAM_PATH.finditer(text):
        if real(match.group(1)) and real(match.group(2)):
            result.append("Xtream credentials in URL path")
            break

    # A username or password parameter alone is common in URL-builder source;
    # flag only files containing both with concrete values.
    users = [m.group(1) for m in QUERY_USER.finditer(text) if real(m.group(1))]
    passwords = [m.group(1) for m in QUERY_PASS.finditer(text) if real(m.group(1))]
    if users and passwords:
        result.append("Xtream credentials in query string")
    return result


def main() -> int:
    failures: list[tuple[str, str]] = []
    for relative in tracked_files():
        if relative in SKIP:
            continue
        path = ROOT / relative
        if path.suffix.lower() not in TEXT_SUFFIXES or not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        for kind in findings(relative, text):
            failures.append((relative, kind))

    if failures:
        print("Secret scan failed (values are intentionally not printed):", file=sys.stderr)
        for path, kind in failures:
            print(f"  {path}: {kind}", file=sys.stderr)
        return 1

    print("Secret scan passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
