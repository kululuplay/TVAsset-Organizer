#!/usr/bin/env python3
"""Publication guards for automatic production rollout."""

import copy
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from activate_release_rollout import enabled_policy, main


class ReleaseRolloutTest(unittest.TestCase):
    def setUp(self):
        self.digest = "a" * 64
        self.policy = dict(schema=1, targetVersion="1.5.88", stableVersion="1.5.86",
                           rolloutPercent=0, paused=True, emergency=False, salt="release-1.5.88")
        self.release = dict(tag_name="v1.5.88", draft=False, prerelease=False,
                            published_at="2026-09-11T19:23:12Z", assets=[dict(
                                name="KululuIPTV-v1.5.88.apk", state="uploaded", size=93_000_000,
                                digest=f"sha256:{self.digest}")])
        self.latest = copy.deepcopy(self.release)

    def activate(self, version="1.5.88"):
        return enabled_policy(self.policy, self.release, self.latest, version, self.digest)

    def test_public_verified_apk_opens_all_cohorts(self):
        actual = self.activate()
        self.assertEqual((actual["rolloutPercent"], actual["paused"], actual["emergency"]),
                         (100, False, False))
        self.assertEqual(actual["stableVersion"], "1.5.86")
        self.assertTrue(self.policy["paused"])

    def test_new_release_advances_an_older_policy(self):
        self.policy.update(targetVersion="1.5.87", salt="release-1.5.87")
        actual = self.activate()
        self.assertEqual(actual["targetVersion"], "1.5.88")
        self.assertEqual(actual["salt"], "release-1.5.88")

    def test_idempotent_activation(self):
        self.policy = self.activate()
        self.assertEqual(self.activate(), self.policy)

    def test_draft_prerelease_and_unpublished_are_rejected(self):
        for field, value in (("draft", True), ("prerelease", True), ("published_at", None)):
            with self.subTest(field=field):
                old = self.release[field]
                self.release[field] = value
                with self.assertRaises(ValueError):
                    self.activate()
                self.release[field] = old

    def test_an_older_job_cannot_activate_after_a_new_release(self):
        self.latest["tag_name"] = "v1.5.89"
        with self.assertRaises(ValueError):
            self.activate()

    def test_a_newer_rollout_target_cannot_be_overwritten(self):
        self.policy["targetVersion"] = "1.5.89"
        with self.assertRaises(ValueError):
            self.activate()

    def test_missing_duplicate_and_incomplete_apk_are_rejected(self):
        asset = self.release["assets"][0]
        for assets in ([], [asset, asset], [dict(asset, state="new")], [dict(asset, size=0)]):
            with self.subTest(assets=assets):
                self.release["assets"] = assets
                with self.assertRaises(ValueError):
                    self.activate()

    def test_wrong_or_missing_apk_digest_is_rejected(self):
        for digest in (None, "", "sha256:" + "b" * 64):
            with self.subTest(digest=digest):
                self.release["assets"][0]["digest"] = digest
                with self.assertRaises(ValueError):
                    self.activate()

    def test_invalid_policy_and_preview_are_rejected(self):
        with self.assertRaises(ValueError):
            self.activate("1.5.88-preview1")
        self.policy["stableVersion"] = "1.5.88"
        with self.assertRaises(ValueError):
            self.activate()

    def run_publication(self, conflict=False, changed_after_write=False):
        self.digest = hashlib.sha256(b"verified APK").hexdigest()
        self.release["assets"][0]["digest"] = f"sha256:{self.digest}"
        self.latest = copy.deepcopy(self.release)
        old = dict(sha="original-file-sha", content=base64.b64encode(
            json.dumps(self.policy).encode()).decode())
        updated = dict(content=base64.b64encode(json.dumps(self.activate()).encode()).decode())
        responses = [self.release, self.latest, old,
                     RuntimeError("GitHub conflict") if conflict else {},
                     old if changed_after_write else updated]
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "verified.apk"
            apk.write_bytes(b"verified APK")
            argv = ["activate_release_rollout.py", "--repo", "kululuplay/TVAsset-Organizer",
                    "--branch", "main", "--version", "1.5.88", "--verified-apk", str(apk)]
            with patch("sys.argv", argv), patch("activate_release_rollout.github",
                                               side_effect=responses) as api:
                if conflict or changed_after_write:
                    with self.assertRaises(RuntimeError):
                        main()
                else:
                    main()
                return api.call_args_list

    def test_publication_uses_compare_and_swap_and_reads_back(self):
        calls = self.run_publication()
        self.assertEqual(calls[3].args[0], "PUT")
        payload = calls[3].args[2]
        self.assertEqual(payload["sha"], "original-file-sha")
        self.assertEqual(payload["branch"], "main")
        self.assertEqual(json.loads(base64.b64decode(payload["content"]))["rolloutPercent"], 100)
        self.assertEqual(calls[4].args[0], "GET")

    def test_concurrent_policy_edit_fails_without_forcing_an_update(self):
        self.assertEqual(len(self.run_publication(conflict=True)), 4)

    def test_readback_mismatch_is_reported(self):
        self.assertEqual(len(self.run_publication(changed_after_write=True)), 5)


if __name__ == "__main__":
    unittest.main()
