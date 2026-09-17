#!/usr/bin/env python3
"""Emergency brake: pause the in-app rollout when the release-health verdict is degraded.

Runs from .github/workflows/release-health.yml every 30 minutes. It only ever
sets ``paused: true`` on ``update-rollout.json`` (main) and opens/updates one
GitHub issue; re-activation is a human decision made with
``scripts/activate_release_rollout.py --force`` (see UPDATE_ROLLOUT.md).
"""

from __future__ import annotations

import base64
import datetime as dt
import http.client
import json
import os
import re
import urllib.parse

# Must match UpdateConfig.ROLLOUT_POLICY_URL (same as activate_release_rollout).
ROLLOUT_BRANCH = "main"
# A release older than this has already reached the fleet; pausing it would
# only take the stable fallback away from the few devices still waiting.
MAX_RELEASE_AGE_HOURS = 72
HEALTH_WINDOW_HOURS = 24
ISSUE_LABEL = "rollout"


def version_ok(value) -> bool:
    return isinstance(value, str) and re.fullmatch(r"\d+\.\d+\.\d+", value) is not None


def repo_ok(value: str) -> bool:
    # owner/name, each a GitHub-legal slug; "." / ".." segments would otherwise
    # pass a plain [\w.-]+ test and turn the API path into a traversal.
    parts = value.split("/")
    return len(parts) == 2 and all(
        re.fullmatch(r"[\w.-]+", part) and part.strip(".") != "" for part in parts)


def github(method: str, path: str, payload: dict | None = None, *, missing_ok: bool = False):
    # Fixed HTTPS origin, normal TLS verification, no shell and no redirects.
    connection = http.client.HTTPSConnection("api.github.com", timeout=30)
    try:
        connection.request(method, f"/{path}",
                           body=json.dumps(payload).encode() if payload is not None else None,
                           headers={"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
                                    "Accept": "application/vnd.github+json",
                                    "Content-Type": "application/json",
                                    "User-Agent": "KululuIPTV-release-health"})
        response = connection.getresponse()
        if response.status == 404 and missing_ok:
            return None
        if not 200 <= response.status < 300:
            raise RuntimeError(f"GitHub {method} {path} failed: HTTP {response.status}")
        return json.loads(response.read())
    finally:
        connection.close()


def fetch_health(base_url: str, key: str, version: str, hours: int = HEALTH_WINDOW_HOURS) -> dict:
    """GET <base_url>/api/release-health with the ops key. HTTPS only."""
    parts = urllib.parse.urlsplit(base_url)
    if parts.scheme != "https" or not parts.hostname:
        raise RuntimeError("RELEASE_HEALTH_URL must be an https:// origin")
    query = urllib.parse.urlencode({"version": version, "hours": hours})
    path = parts.path.rstrip("/") + "/api/release-health?" + query
    connection = http.client.HTTPSConnection(parts.hostname, parts.port, timeout=60)
    try:
        connection.request("GET", path, headers={"X-Kululu-Ops-Key": key,
                                                 "Accept": "application/json",
                                                 "User-Agent": "KululuIPTV-release-health"})
        response = connection.getresponse()
        if response.status == 503:
            raise RuntimeError("release-health endpoint is disabled (RELEASE_HEALTH_KEY unset on the server)")
        if not 200 <= response.status < 300:
            raise RuntimeError(f"release-health GET failed: HTTP {response.status}")
        return json.loads(response.read())
    finally:
        connection.close()


def release_age_hours(release: dict | None, now: dt.datetime) -> float | None:
    published = (release or {}).get("published_at")
    if not published:
        return None
    when = dt.datetime.fromisoformat(published.replace("Z", "+00:00"))
    return (now - when).total_seconds() / 3600


def decide(policy: dict, release: dict | None, now: dt.datetime) -> tuple[str, str]:
    """Pre-flight: ("check", version) or ("skip", reason). Pure."""
    version = policy.get("targetVersion")
    if policy.get("schema") != 1 or not version_ok(version):
        raise RuntimeError("Unsupported rollout policy")
    if policy.get("paused") is True:
        return "skip", f"v{version} is already paused"
    age = release_age_hours(release, now)
    if age is None:
        return "skip", f"v{version} has no published release yet"
    if age > MAX_RELEASE_AGE_HOURS:
        return "skip", f"v{version} was published {age:.0f} h ago (> {MAX_RELEASE_AGE_HOURS} h)"
    return "check", version


def paused_policy(policy: dict) -> dict:
    result = dict(policy)
    result["paused"] = True
    return result


def pct(value) -> str:
    return "n/a" if value is None else f"{value * 100:.1f}%"


def metrics_table(health: dict) -> str:
    baseline = health.get("baseline") or {}
    rows = [
        ("Sessions", health.get("sessions"), baseline.get("sessions")),
        ("Crash reports", health.get("crashReports"), baseline.get("crashReports")),
        ("Crash-free sessions", pct(health.get("crashFreeSessionRate")),
         pct(baseline.get("crashFreeSessionRate"))),
        ("Stall sessions", pct(health.get("stallSessionRate")), pct(baseline.get("stallSessionRate"))),
        ("TTFF p50 (ms)", health.get("ttffP50Ms"), baseline.get("ttffP50Ms")),
        ("TTFF p90 (ms)", health.get("ttffP90Ms"), baseline.get("ttffP90Ms")),
    ]
    head = (f"| Metric | v{health.get('version')} ({health.get('windowHours')} h) | "
            f"baseline v{baseline.get('version', '-')} ({baseline.get('windowHours', '-')} h) |\n"
            "| --- | --- | --- |\n")
    return head + "\n".join(f"| {name} | {cur if cur is not None else 'n/a'} | "
                            f"{base if base is not None else 'n/a'} |" for name, cur, base in rows)


def issue_title(version: str) -> str:
    return f"Rollout auto-paused: v{version}"


def issue_body(version: str, health: dict, now: dt.datetime) -> str:
    reasons = "\n".join(f"- {reason}" for reason in health.get("reasons", [])) or "- (no reasons given)"
    return (f"The release-health gate set `paused: true` for v{version} at {now.isoformat(timespec='minutes')}.\n\n"
            f"**Verdict:** `{health.get('verdict')}`\n\n{reasons}\n\n{metrics_table(health)}\n\n"
            "This is an emergency brake, not a verdict on the build. To lift it after investigating:\n"
            "```sh\nGH_TOKEN=... python3 scripts/activate_release_rollout.py "
            f"--repo <owner/repo> --version {version} --verified-apk KululuIPTV-v{version}.apk --force\n```\n"
            "See docs/release-health.md.")


def pause_rollout(repo: str, version: str, source: dict, health: dict) -> None:
    endpoint = f"repos/{repo}/contents/update-rollout.json"
    policy = json.loads(base64.b64decode(source["content"]))
    content = json.dumps(paused_policy(policy), indent=2) + "\n"
    reasons = "; ".join(health.get("reasons", [])) or health.get("verdict", "degraded")
    # Compare-and-swap on the file SHA: a concurrent operator edit wins.
    github("PUT", endpoint, {
        "message": f"Auto-pause rollout v{version}: {reasons}",
        "content": base64.b64encode(content.encode()).decode(),
        "sha": source["sha"],
        "branch": ROLLOUT_BRANCH,
    })


def upsert_issue(repo: str, version: str, health: dict, now: dt.datetime) -> None:
    title = issue_title(version)
    body = issue_body(version, health, now)
    existing = github("GET", f"repos/{repo}/issues?state=open&per_page=100&labels={ISSUE_LABEL}") or []
    for issue in existing:
        if issue.get("title") == title and "pull_request" not in issue:
            github("POST", f"repos/{repo}/issues/{issue['number']}/comments", {"body": body})
            return
    github("POST", f"repos/{repo}/issues", {"title": title, "body": body, "labels": [ISSUE_LABEL]})


def main() -> None:
    base_url = os.environ["RELEASE_HEALTH_URL"].strip()
    key = os.environ["RELEASE_HEALTH_KEY"].strip()
    repo = os.environ["GITHUB_REPOSITORY"].strip()
    if not repo_ok(repo):
        raise ValueError("Invalid repository")
    if not key:
        raise ValueError("RELEASE_HEALTH_KEY is empty")
    now = dt.datetime.now(dt.timezone.utc)
    endpoint = f"repos/{repo}/contents/update-rollout.json"
    source = github("GET", f"{endpoint}?ref={ROLLOUT_BRANCH}")
    policy = json.loads(base64.b64decode(source["content"]))
    version = policy.get("targetVersion")
    release = github("GET", f"repos/{repo}/releases/tags/v{version}", missing_ok=True) if version_ok(version) else None
    action, detail = decide(policy, release, now)
    if action == "skip":
        print(f"release-health: skipped ({detail})")
        return
    health = fetch_health(base_url, key, version)
    verdict = health.get("verdict")
    summary = (f"v{version}: {verdict}, {health.get('sessions')} sessions, "
               f"crash-free {pct(health.get('crashFreeSessionRate'))}, "
               f"stalls {pct(health.get('stallSessionRate'))}, ttff p90 {health.get('ttffP90Ms')} ms")
    if verdict != "degraded":
        print(f"release-health: {summary}")
        return
    print(f"release-health: PAUSING {summary}: {'; '.join(health.get('reasons', []))}")
    pause_rollout(repo, version, source, health)
    upsert_issue(repo, version, health, now)


if __name__ == "__main__":
    main()
