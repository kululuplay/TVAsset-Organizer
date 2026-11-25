#!/usr/bin/env python3
"""remote-config.json writer: signs what the app will verify, keeps what was not named."""

import base64
import json
import pathlib
import tempfile
import unittest

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

from sign_remote_config import main


def pair() -> tuple[bytes, bytes]:
    key = ec.generate_private_key(ec.SECP256R1())
    return (
        key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()),
        key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo),
    )


class SignRemoteConfigTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        base = pathlib.Path(self.directory.name)
        self.config, self.key, self.public, self.policy = (base / n for n in ("c.json", "k.pem", "p.pem", "policy.json"))
        private, public = pair()
        self.key.write_bytes(private)
        self.public.write_bytes(public)
        self.policy.write_text('{"deviceOverrides":[{"match":{"model":"^AFTMM$"},"set":{"startEngine":"VLC"}}]}')
        self.common = ["--config", str(self.config), "--public", str(self.public)]

    def tearDown(self):
        self.directory.cleanup()

    def test_policy_is_signed_over_the_exact_payload_text_and_expires(self):
        self.assertEqual(main(["--key", str(self.key), "--policy", str(self.policy), "--days", "30", *self.common]), 0)
        envelope = json.loads(self.config.read_text(encoding="utf-8"))["playbackPolicySigned"]
        serialization.load_pem_public_key(self.public.read_bytes()).verify(
            base64.b64decode(envelope["sig"]), envelope["payload"].encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        payload = json.loads(envelope["payload"])
        self.assertEqual(payload["deviceOverrides"][0]["set"]["startEngine"], "VLC")
        self.assertGreater(payload["expiresAtEpochMs"], 0)

    def test_a_key_the_app_does_not_trust_writes_nothing(self):
        self.public.write_bytes(pair()[1])
        with self.assertRaises(InvalidSignature):
            main(["--key", str(self.key), "--policy", str(self.policy), *self.common])
        self.assertFalse(self.config.exists())

    def test_announcement_ids_only_grow_and_the_policy_stays(self):
        main(["--key", str(self.key), "--policy", str(self.policy), *self.common])
        main(["--announce", "first", *self.common])
        main(["--no-announcement", *self.common])
        main(["--announce", "second", *self.common])
        config = json.loads(self.config.read_text(encoding="utf-8"))
        self.assertEqual(config["announcement"], {"id": 2, "message": "second"})
        self.assertIn("playbackPolicySigned", config)


if __name__ == "__main__":
    unittest.main()
