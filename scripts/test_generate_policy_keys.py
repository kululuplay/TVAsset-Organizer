#!/usr/bin/env python3
"""Key generator: never clobbers a shipped public key; falls back to a node one-liner."""

import pathlib
import tempfile
import unittest
from unittest.mock import patch

from generate_policy_keys import main, node_fallback, write_pair

FAKE_PRIVATE = b"-----BEGIN " + b"PRIVATE KEY-----\nfake\n-----END " + b"PRIVATE KEY-----\n"
FAKE_PUBLIC = b"-----BEGIN PUBLIC KEY-----\nfake\n-----END PUBLIC KEY-----\n"


class GeneratePolicyKeysTest(unittest.TestCase):
    def test_existing_files_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            private = pathlib.Path(directory) / "private.pem"
            public = pathlib.Path(directory) / "public.pem"
            public.write_bytes(b"shipped")
            with self.assertRaises(FileExistsError):
                write_pair(private, public, FAKE_PRIVATE, FAKE_PUBLIC)
            self.assertEqual(public.read_bytes(), b"shipped")
            self.assertFalse(private.exists())
            self.assertEqual(main([str(private), "--public-out", str(public)]), 2)
            write_pair(private, public, FAKE_PRIVATE, FAKE_PUBLIC, force=True)
            self.assertEqual(public.read_bytes(), FAKE_PUBLIC)
            self.assertEqual(private.read_bytes(), FAKE_PRIVATE)

    def test_generated_pair_is_written_from_the_first_available_backend(self):
        with tempfile.TemporaryDirectory() as directory:
            private = pathlib.Path(directory) / "keys" / "private.pem"
            public = pathlib.Path(directory) / "public.pem"
            with patch("generate_policy_keys.generate_with_cryptography", return_value=None), \
                    patch("generate_policy_keys.generate_with_openssl",
                          return_value=(FAKE_PRIVATE, FAKE_PUBLIC)):
                self.assertEqual(main([str(private), "--public-out", str(public)]), 0)
            self.assertEqual(private.read_bytes(), FAKE_PRIVATE)
            self.assertEqual(public.read_bytes(), FAKE_PUBLIC)

    def test_without_a_backend_the_node_one_liner_is_printed(self):
        with tempfile.TemporaryDirectory() as directory:
            private = pathlib.Path(directory) / "private.pem"
            public = pathlib.Path(directory) / "public.pem"
            with patch("generate_policy_keys.generate_with_cryptography", return_value=None), \
                    patch("generate_policy_keys.generate_with_openssl", return_value=None), \
                    patch("builtins.print") as out:
                self.assertEqual(main([str(private), "--public-out", str(public)]), 1)
            printed = out.call_args.args[0]
            self.assertIn("node -e", printed)
            self.assertIn("prime256v1", printed)
            self.assertIn(str(private), printed)
            self.assertFalse(private.exists())
        command = node_fallback(pathlib.Path("a.pem"), pathlib.Path("b.pem"))
        self.assertIn("pkcs8", command)
        self.assertIn("spki", command)
        self.assertTrue(command.endswith("a.pem b.pem"))


if __name__ == "__main__":
    unittest.main()
