#!/usr/bin/env python3
"""Rollout emergency brake: pause on degraded, never on healthy/insufficient."""

import base64
import datetime as dt
import json
import unittest
from unittest.mock import patch

from release_health_gate import (decide, fetch_health, github, issue_body, issue_title, main,
                                 paused_policy)

NOW = dt.datetime(2026, 9, 17, 12, 0, tzinfo=dt.timezone.utc)
ENV = {"RELEASE_HEALTH_URL": "https://health.example.test", "RELEASE_HEALTH_KEY": "ops-key",
       "GITHUB_TOKEN": "gh-token", "GITHUB_REPOSITORY": "kululuplay/TVAsset-Organizer"}


def health(verdict="healthy", **extra):
    base = dict(version="1.5.90", windowHours=24, sessions=800, crashReports=3,
                crashFreeSessionRate=0.991, stallSessionRate=0.12, ttffP50Ms=900, ttffP90Ms=2100,
                baseline=dict(version="1.5.89", windowHours=168, sessions=6000, crashReports=20,
                              crashFreeSessionRate=0.99, stallSessionRate=0.11, ttffP50Ms=880,
                              ttffP90Ms=2000),
                verdict=verdict, reasons=[])
    if verdict == "degraded":
        base.update(crashFreeSessionRate=0.95, reasons=["crash-free sessions 95.0% < 97.0%"])
    base.update(extra)
    return base


class DecisionTest(unittest.TestCase):
    def setUp(self):
        self.policy = dict(schema=1, targetVersion="1.5.90", stableVersion="1.5.89",
                           rolloutPercent=100, paused=False, emergency=False, salt="release-1.5.90")
        self.release = dict(tag_name="v1.5.90", published_at="2026-09-16T20:00:00Z")

    def test_fresh_active_release_is_checked(self):
        self.assertEqual(decide(self.policy, self.release, NOW), ("check", "1.5.90"))

    def test_already_paused_is_skipped(self):
        self.policy["paused"] = True
        action, reason = decide(self.policy, self.release, NOW)
        self.assertEqual(action, "skip")
        self.assertIn("already paused", reason)

    def test_release_older_than_72h_is_skipped(self):
        self.release["published_at"] = "2026-09-14T11:00:00Z"  # 73 h
        self.assertEqual(decide(self.policy, self.release, NOW)[0], "skip")
        self.release["published_at"] = "2026-09-14T13:00:00Z"  # 71 h
        self.assertEqual(decide(self.policy, self.release, NOW)[0], "check")

    def test_unpublished_target_is_skipped(self):
        self.assertEqual(decide(self.policy, None, NOW)[0], "skip")
        self.assertEqual(decide(self.policy, dict(published_at=None), NOW)[0], "skip")

    def test_unsupported_policy_is_an_error(self):
        for bad in (dict(self.policy, schema=2), dict(self.policy, targetVersion="latest")):
            with self.subTest(bad=bad), self.assertRaises(RuntimeError):
                decide(bad, self.release, NOW)

    def test_paused_policy_only_flips_paused(self):
        paused = paused_policy(self.policy)
        self.assertEqual(paused, dict(self.policy, paused=True))
        self.assertFalse(self.policy["paused"])

    def test_issue_text_carries_metrics_and_recovery_command(self):
        self.assertEqual(issue_title("1.5.90"), "Rollout auto-paused: v1.5.90")
        body = issue_body("1.5.90", health("degraded"), NOW)
        self.assertIn("crash-free sessions 95.0% < 97.0%", body)
        self.assertIn("| Crash-free sessions | 95.0% | 99.0% |", body)
        self.assertIn("| Sessions | 800 | 6000 |", body)
        self.assertIn("--version 1.5.90", body)
        self.assertIn("--force", body)


class GateRunTest(unittest.TestCase):
    """Fake-HTTP runs of main(): github() and fetch_health() are patched."""

    def setUp(self):
        self.policy = dict(schema=1, targetVersion="1.5.90", stableVersion="1.5.89",
                           rolloutPercent=100, paused=False, emergency=False, salt="release-1.5.90")
        self.release = dict(tag_name="v1.5.90", published_at="2026-09-16T20:00:00Z")

    def source(self):
        return dict(sha="file-sha", content=base64.b64encode(json.dumps(self.policy).encode()).decode())

    def run_gate(self, verdict, github_responses, health_calls=1):
        fixed_now = patch("release_health_gate.dt.datetime", wraps=dt.datetime)
        with patch.dict("os.environ", ENV), patch("release_health_gate.github",
                                                   side_effect=github_responses) as api, \
                patch("release_health_gate.fetch_health", return_value=health(verdict)) as probe, \
                fixed_now as clock:
            clock.now.return_value = NOW
            main()
        self.assertEqual(probe.call_count, health_calls)
        if health_calls:
            self.assertEqual(probe.call_args.args, ("https://health.example.test", "ops-key", "1.5.90"))
        return api.call_args_list

    def test_healthy_and_insufficient_change_nothing(self):
        for verdict in ("healthy", "insufficient"):
            with self.subTest(verdict=verdict):
                calls = self.run_gate(verdict, [self.source(), self.release])
                self.assertEqual([c.args[0] for c in calls], ["GET", "GET"])
                self.assertTrue(calls[0].args[1].endswith("update-rollout.json?ref=main"))
                self.assertEqual(calls[1].args[1], "repos/kululuplay/TVAsset-Organizer/releases/tags/v1.5.90")
                self.assertEqual(calls[1].kwargs, {"missing_ok": True})

    def test_degraded_pauses_with_cas_and_opens_an_issue(self):
        calls = self.run_gate("degraded", [self.source(), self.release, {}, [], {}])
        self.assertEqual([c.args[0] for c in calls], ["GET", "GET", "PUT", "GET", "POST"])
        put = calls[2]
        self.assertEqual(put.args[1], "repos/kululuplay/TVAsset-Organizer/contents/update-rollout.json")
        payload = put.args[2]
        self.assertEqual(payload["sha"], "file-sha")
        self.assertEqual(payload["branch"], "main")
        self.assertEqual(payload["message"],
                         "Auto-pause rollout v1.5.90: crash-free sessions 95.0% < 97.0%")
        written = json.loads(base64.b64decode(payload["content"]))
        self.assertEqual(written, dict(self.policy, paused=True))
        self.assertIn("labels=rollout", calls[3].args[1])
        issue = calls[4]
        self.assertEqual(issue.args[1], "repos/kululuplay/TVAsset-Organizer/issues")
        self.assertEqual(issue.args[2]["title"], "Rollout auto-paused: v1.5.90")
        self.assertIn("95.0%", issue.args[2]["body"])
        self.assertEqual(issue.args[2]["labels"], ["rollout"])

    def test_degraded_with_an_open_issue_comments_instead(self):
        existing = [dict(number=41, title="Rollout auto-paused: v1.5.90"),
                    dict(number=42, title="Rollout auto-paused: v1.5.90", pull_request={})]
        calls = self.run_gate("degraded", [self.source(), self.release, {}, existing, {}])
        self.assertEqual(calls[4].args[0:2], ("POST", "repos/kululuplay/TVAsset-Organizer/issues/41/comments"))
        self.assertIn("crash-free sessions 95.0%", calls[4].args[2]["body"])

    def test_paused_or_old_release_never_calls_the_health_endpoint(self):
        self.policy["paused"] = True
        calls = self.run_gate("degraded", [self.source(), self.release], health_calls=0)
        self.assertEqual(len(calls), 2)
        self.policy["paused"] = False
        self.release["published_at"] = "2026-09-10T00:00:00Z"
        calls = self.run_gate("degraded", [self.source(), self.release], health_calls=0)
        self.assertEqual(len(calls), 2)
        # A pre-armed policy for an unpublished version: 404 -> None -> skip.
        calls = self.run_gate("degraded", [self.source(), None], health_calls=0)
        self.assertEqual(len(calls), 2)

    def test_pause_write_conflict_propagates_as_an_error(self):
        with self.assertRaisesRegex(RuntimeError, "conflict"):
            self.run_gate("degraded", [self.source(), self.release, RuntimeError("GitHub conflict")])

    def test_invalid_repository_or_empty_key_fail_before_any_call(self):
        for env in (dict(ENV, GITHUB_REPOSITORY="../evil"), dict(ENV, RELEASE_HEALTH_KEY=" ")):
            with self.subTest(env=env), patch.dict("os.environ", env), \
                    patch("release_health_gate.github") as api, self.assertRaises(ValueError):
                main()
            api.assert_not_called()


class HttpTest(unittest.TestCase):
    def test_github_uses_fixed_origin_bearer_token_and_json(self):
        with patch.dict("os.environ", {"GITHUB_TOKEN": "gh-token"}), patch(
                "release_health_gate.http.client.HTTPSConnection") as constructor:
            connection = constructor.return_value
            response = connection.getresponse.return_value
            response.status = 200
            response.read.return_value = b'{"ok": true}'
            self.assertEqual(github("PUT", "repos/o/r/contents/update-rollout.json", {"branch": "main"}),
                             {"ok": True})
            constructor.assert_called_once_with("api.github.com", timeout=30)
            request = connection.request.call_args
            self.assertEqual(request.args, ("PUT", "/repos/o/r/contents/update-rollout.json"))
            self.assertEqual(request.kwargs["headers"]["Authorization"], "Bearer gh-token")
            self.assertEqual(json.loads(request.kwargs["body"]), {"branch": "main"})
            connection.close.assert_called_once()

    def test_github_404_is_none_only_when_allowed(self):
        with patch.dict("os.environ", {"GITHUB_TOKEN": "gh-token"}), patch(
                "release_health_gate.http.client.HTTPSConnection") as constructor:
            constructor.return_value.getresponse.return_value.status = 404
            self.assertIsNone(github("GET", "repos/o/r/releases/tags/v9.9.9", missing_ok=True))
            with self.assertRaisesRegex(RuntimeError, "HTTP 404"):
                github("GET", "repos/o/r/releases/tags/v9.9.9")
            constructor.return_value.getresponse.return_value.read.assert_not_called()

    def test_fetch_health_sends_ops_key_over_https_only(self):
        with patch("release_health_gate.http.client.HTTPSConnection") as constructor:
            connection = constructor.return_value
            response = connection.getresponse.return_value
            response.status = 200
            response.read.return_value = json.dumps(health()).encode()
            result = fetch_health("https://health.example.test/base/", "ops-key", "1.5.90")
            self.assertEqual(result["verdict"], "healthy")
            constructor.assert_called_once_with("health.example.test", None, timeout=60)
            request = connection.request.call_args
            self.assertEqual(request.args, ("GET", "/base/api/release-health?version=1.5.90&hours=24"))
            self.assertEqual(request.kwargs["headers"]["X-Kululu-Ops-Key"], "ops-key")
            connection.close.assert_called_once()
        with self.assertRaisesRegex(RuntimeError, "https"):
            fetch_health("http://health.example.test", "ops-key", "1.5.90")

    def test_fetch_health_503_and_errors_are_reported(self):
        for status, message in ((503, "disabled"), (401, "HTTP 401"), (500, "HTTP 500")):
            with self.subTest(status=status), patch(
                    "release_health_gate.http.client.HTTPSConnection") as constructor:
                constructor.return_value.getresponse.return_value.status = status
                with self.assertRaisesRegex(RuntimeError, message):
                    fetch_health("https://health.example.test", "ops-key", "1.5.90")


if __name__ == "__main__":
    unittest.main()
