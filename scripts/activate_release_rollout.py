#!/usr/bin/env python3
"""Open in-app distribution only after the verified production APK is public."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import pathlib
import re
import subprocess
from urllib.parse import quote


def version_parts(value: str) -> tuple[int, ...]:
    if not isinstance(value, str) or not re.fullmatch(r"\d+\.\d+\.\d+", value):
        raise ValueError("Expected a production x.y.z version")
    return tuple(map(int, value.split(".")))


def enabled_policy(current: dict, release: dict, latest: dict, version: str, digest: str) -> dict:
    candidate = version_parts(version)
    tag = f"v{version}"
    for record in (release, latest):
        if (record.get("tag_name") != tag or record.get("draft") is not False
                or record.get("prerelease") is not False or not record.get("published_at")):
            raise ValueError("Candidate must be the latest published production release")
    apks = [asset for asset in release.get("assets", [])
            if asset.get("name", "").lower().endswith(".apk")]
    if len(apks) != 1:
        raise ValueError("Release must contain exactly one APK")
    apk = apks[0]
    if (apk.get("name") != f"KululuIPTV-{tag}.apk" or apk.get("state") != "uploaded"
            or apk.get("size", 0) <= 0 or not re.fullmatch(r"[0-9a-f]{64}", digest)
            or apk.get("digest") != f"sha256:{digest}"):
        raise ValueError("Published APK does not match the verified signed APK")
    allowed = {"schema", "targetVersion", "stableVersion", "rolloutPercent", "paused", "emergency", "salt"}
    if current.get("schema") != 1 or set(current) - allowed:
        raise ValueError("Unsupported rollout policy")
    if version_parts(current.get("targetVersion")) > candidate:
        raise ValueError("Refusing to replace a newer rollout target")
    stable = current.get("stableVersion")
    if stable is not None and version_parts(stable) >= candidate:
        raise ValueError("Stable fallback must precede the new version")
    result = dict(current)
    result.update(targetVersion=version, rolloutPercent=100, paused=False, emergency=False)
    if current.get("targetVersion") != version:
        result["salt"] = f"release-{version}"
    if not isinstance(result.get("salt"), str) or not 1 <= len(result["salt"]) <= 64:
        raise ValueError("Invalid rollout salt")
    return result


def github(method: str, path: str, payload: dict | None = None) -> dict:
    command = ["gh", "api", "--method", method, path]
    if payload is not None:
        command += ["--input", "-"]
    completed = subprocess.run(command, input=json.dumps(payload) if payload else None,
                               text=True, capture_output=True, check=False)
    if completed.returncode:
        raise RuntimeError(f"GitHub {method} {path} failed: {completed.stderr.strip()}")
    return json.loads(completed.stdout)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--branch", required=True, choices=("main", "master"))
    parser.add_argument("--version", required=True)
    parser.add_argument("--verified-apk", required=True, type=pathlib.Path)
    args = parser.parse_args()
    version_parts(args.version)
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
        raise ValueError("Invalid repository")
    with args.verified_apk.open("rb") as apk:
        digest = hashlib.file_digest(apk, "sha256").hexdigest()
    root = f"repos/{args.repo}"
    release = github("GET", f"{root}/releases/tags/v{args.version}")
    latest = github("GET", f"{root}/releases/latest")
    endpoint = f"{root}/contents/update-rollout.json"
    ref = f"{endpoint}?ref={quote(args.branch, safe='')}"
    source = github("GET", ref)
    current = json.loads(base64.b64decode(source["content"]))
    updated = enabled_policy(current, release, latest, args.version, digest)
    if updated != current:
        content = json.dumps(updated, indent=2) + "\n"
        # The file SHA makes concurrent policy edits fail instead of overwriting
        # a newer rollout or an operator's pause. No force push is used.
        github("PUT", endpoint, {
            "message": f"Enable v{args.version} in-app updates for all users",
            "content": base64.b64encode(content.encode()).decode(),
            "sha": source["sha"],
            "branch": args.branch,
        })
    actual = github("GET", ref)
    if json.loads(base64.b64decode(actual["content"])) != updated:
        raise RuntimeError("Published rollout policy did not match; inspect the current policy")
    print(f"v{args.version} in-app rollout verified: 100%, paused=false")


if __name__ == "__main__":
    main()
