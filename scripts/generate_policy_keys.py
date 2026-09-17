#!/usr/bin/env python3
"""Generate a P-256 key pair for playback-policy signing (docs/policy-signing.md).

    python3 scripts/generate_policy_keys.py /secure/path/policy_signing_private.pem

writes the PKCS#8 private key there (never inside the repository) and the SPKI
public key to IptvPlayer/app/policy_public_key.pem, which the Android build
embeds. Existing files are never overwritten unless --force is given: the
public key already shipped in released apps must stay as it is until a rotation
is planned (a new key gets a new POLICY_SIGNING_KID).

Backends, in order: python ``cryptography`` if importable, then the ``openssl``
CLI. Without either, the equivalent ``node -e`` one-liner is printed instead.
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile

DEFAULT_PUBLIC = pathlib.Path("IptvPlayer/app/policy_public_key.pem")

NODE_ONE_LINER = (
    "node -e \"const c=require('crypto');const k=c.generateKeyPairSync('ec',{namedCurve:'prime256v1'});"
    "require('fs').writeFileSync(process.argv[1],k.privateKey.export({type:'pkcs8',format:'pem'}),{mode:0o600});"
    "require('fs').writeFileSync(process.argv[2],k.publicKey.export({type:'spki',format:'pem'}))\""
)


def node_fallback(private: pathlib.Path, public: pathlib.Path) -> str:
    # Plain concatenation: the JS braces above must not go through str.format.
    return f"{NODE_ONE_LINER} {private} {public}"


def generate_with_cryptography() -> tuple[bytes, bytes] | None:
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
    except ImportError:
        return None
    key = ec.generate_private_key(ec.SECP256R1())
    private = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption())
    public = key.public_key().public_bytes(serialization.Encoding.PEM,
                                           serialization.PublicFormat.SubjectPublicKeyInfo)
    return private, public


def generate_with_openssl() -> tuple[bytes, bytes] | None:
    openssl = shutil.which("openssl")
    if not openssl:
        return None
    with tempfile.TemporaryDirectory() as directory:
        private_path = pathlib.Path(directory) / "private.pem"
        # genpkey emits PKCS#8 directly (ecparam -genkey would emit SEC1).
        subprocess.run([openssl, "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256",
                        "-out", str(private_path)], check=True, capture_output=True)
        public = subprocess.run([openssl, "pkey", "-in", str(private_path), "-pubout"],
                                check=True, capture_output=True).stdout
        return private_path.read_bytes(), public


def write_pair(private_path: pathlib.Path, public_path: pathlib.Path, private: bytes, public: bytes,
               force: bool = False) -> None:
    for path in (private_path, public_path):
        if path.exists() and not force:
            raise FileExistsError(f"{path} exists; pass --force to overwrite (rotation: see docs/policy-signing.md)")
    private_path.parent.mkdir(parents=True, exist_ok=True)
    public_path.parent.mkdir(parents=True, exist_ok=True)
    private_path.write_bytes(private)
    try:
        private_path.chmod(0o600)
    except OSError:
        pass  # Windows: no POSIX mode bits
    public_path.write_bytes(public)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("private_out", type=pathlib.Path,
                        help="where to write the PKCS#8 private key (outside the repository)")
    parser.add_argument("--public-out", type=pathlib.Path, default=DEFAULT_PUBLIC,
                        help=f"public key path (default {DEFAULT_PUBLIC})")
    parser.add_argument("--force", action="store_true", help="overwrite existing files (key rotation)")
    args = parser.parse_args(argv)
    for path in (args.private_out, args.public_out):
        if path.exists() and not args.force:
            print(f"{path} already exists; not overwriting (use --force for a planned rotation).",
                  file=sys.stderr)
            return 2
    pair = generate_with_cryptography() or generate_with_openssl()
    if pair is None:
        print("Neither python 'cryptography' nor the openssl CLI is available. Run instead:\n\n  "
              + node_fallback(args.private_out, args.public_out))
        return 1
    write_pair(args.private_out, args.public_out, *pair, force=args.force)
    print(f"private key: {args.private_out} (set POLICY_SIGNING_PRIVATE_KEY_PEM to its contents)\n"
          f"public key:  {args.public_out} (commit; the app embeds it)\n"
          "Remember POLICY_SIGNING_KID: bump it when rotating.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
