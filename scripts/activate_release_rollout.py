#!/usr/bin/env python3
"""Open in-app distribution only after the verified production APK is public."""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.client
import json
import os
import pathlib
import re

# Must match UpdateConfig.ROLLOUT_POLICY_URL, even for a build from master.
ROLLOUT_BRANCH = "main"


def version_parts(value: str) -> tuple[int, ...]:
    if not isinstance(value, str) or not re.fullmatch(r"\d+\.\d+\.\d+", value):
        raise ValueError("Expected a production x.y.z version")
    return tuple(map(int, value.split(".")))


def enabled_policy(current: dict, release: dict, latest: dict, version: str, digest: str,
                   percent: int = 100, force: bool = False) -> dict:
    candidate = version_parts(version)
    if not isinstance(percent, int) or isinstance(percent, bool) or not 0 <= percent <= 100:
        raise ValueError("Rollout percent must be an integer from 0 to 100")
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
    previous_target = current.get("targetVersion")
    if previous_target == version and not force and (
            current.get("paused") is True or current.get("emergency") is True):
        # An operator pause/emergency on this exact version is a deliberate hold
        # (AGENTS.md); an accidental re-run must not silently undo it.
        raise ValueError("Rollout policy for this version is held by an operator "
                         "(paused/emergency); pass --force to override")
    result = dict(current)
    if previous_target != version:
        # Advancing the target: the release that was being distributed until now
        # becomes the last known-good fallback the app may still offer.
        result["stableVersion"] = previous_target
        result["salt"] = f"release-{version}"
    stable = result.get("stableVersion")
    if stable is not None and version_parts(stable) >= candidate:
        raise ValueError("Stable fallback must precede the new version")
    result.update(targetVersion=version, rolloutPercent=percent, paused=False, emergency=False)
    if not isinstance(result.get("salt"), str) or not 1 <= len(result["salt"]) <= 64:
        raise ValueError("Invalid rollout salt")
    return result


def github(method: str, path: str, payload: dict | None = None) -> dict:
    # Fixed HTTPS origin, normal TLS verification, no shell and no redirects.
    connection = http.client.HTTPSConnection("api.github.com", timeout=30)
    try:
        connection.request(method, f"/{path}",
                           body=json.dumps(payload).encode() if payload is not None else None,
                           headers={"Authorization": f"Bearer {os.environ['GH_TOKEN']}",
                                    "Accept": "application/vnd.github+json",
                                    "Content-Type": "application/json",
                                    "User-Agent": "KululuIPTV-release-rollout"})
        response = connection.getresponse()
        if not 200 <= response.status < 300:
            raise RuntimeError(f"GitHub {method} {path} failed: HTTP {response.status}")
        return json.loads(response.read())
    finally:
        connection.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--verified-apk", required=True, type=pathlib.Path)
    parser.add_argument("--percent", type=int, default=100,
                        help="rollout cohort percentage (default 100: every device)")
    parser.add_argument("--force", action="store_true",
                        help="replace an operator pause/emergency on this same version")
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
    ref = f"{endpoint}?ref={ROLLOUT_BRANCH}"
    source = github("GET", ref)
    current = json.loads(base64.b64decode(source["content"]))
    updated = enabled_policy(current, release, latest, args.version, digest,
                             percent=args.percent, force=args.force)
    if updated != current:
        content = json.dumps(updated, indent=2) + "\n"
        # The file SHA makes concurrent policy edits fail instead of overwriting
        # a newer rollout or an operator's pause. No force push is used.
        github("PUT", endpoint, {
            "message": (f"Enable v{args.version} in-app updates for all users"
                        if args.percent == 100 else
                        f"Enable v{args.version} in-app updates for {args.percent}% of devices"),
            "content": base64.b64encode(content.encode()).decode(),
            "sha": source["sha"],
            "branch": ROLLOUT_BRANCH,
        })
    actual = github("GET", ref)
    if json.loads(base64.b64decode(actual["content"])) != updated:
        raise RuntimeError("Published rollout policy did not match; inspect the current policy")
    print(f"v{args.version} in-app rollout verified: {args.percent}%, paused=false")


if __name__ == "__main__":
    main()
