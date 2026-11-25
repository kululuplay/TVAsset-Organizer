#!/usr/bin/env python3
"""Write remote-config.json: the signed playback policy and the announcement the app reads.

    python3 scripts/sign_remote_config.py --key /secure/path/policy_signing_private.pem \
        --policy policy.json --days 90
    python3 scripts/sign_remote_config.py --announce "Text shown once on every device"
    python3 scripts/sign_remote_config.py --no-policy --no-announcement

The app (util/RemoteConfig.kt) fetches the file from the main branch, so a change
is live once it is merged there. The policy is signed with the P-256 key whose
public half is IptvPlayer/app/policy_public_key.pem (docs/policy-signing.md); the
result is checked against that public key before anything is written. What is
not named on the command line stays as it is in the file.
"""

from __future__ import annotations

import argparse
import base64
import json
import pathlib
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
CONFIG = ROOT / "remote-config.json"
PUBLIC = ROOT / "IptvPlayer" / "app" / "policy_public_key.pem"


def signed_policy(policy: dict, private_pem: bytes, public_pem: bytes, expires_at_ms: int, kid: str = "1") -> dict:
    """Envelope the app accepts: the exact payload text plus its ECDSA-SHA256 signature (DER, base64)."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    payload = json.dumps({**policy, "policyVersion": 1, "expiresAtEpochMs": expires_at_ms},
                         separators=(",", ":"), ensure_ascii=False)
    key = serialization.load_pem_private_key(private_pem, password=None)
    signature = key.sign(payload.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    # Raises InvalidSignature when the private key is not the one the app trusts.
    serialization.load_pem_public_key(public_pem).verify(signature, payload.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
    return {"payload": payload, "sig": base64.b64encode(signature).decode("ascii"), "kid": kid}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--key", type=pathlib.Path, help="private key (PKCS#8 PEM); needed with --policy")
    parser.add_argument("--policy", type=pathlib.Path, help="policy JSON (docs/playback-policy.md)")
    parser.add_argument("--days", type=int, default=90, help="days until the policy runs out on devices")
    parser.add_argument("--no-policy", action="store_true", help="remove the policy from the file")
    parser.add_argument("--announce", help="announcement text; gets the next id so every device shows it once")
    parser.add_argument("--no-announcement", action="store_true", help="remove the announcement")
    parser.add_argument("--config", type=pathlib.Path, default=CONFIG)
    parser.add_argument("--public", type=pathlib.Path, default=PUBLIC)
    args = parser.parse_args(argv)

    config = json.loads(args.config.read_text(encoding="utf-8")) if args.config.is_file() else {}
    config["schema"] = 1
    if args.no_policy:
        config.pop("playbackPolicySigned", None)
    elif args.policy:
        if not args.key:
            parser.error("--policy needs --key")
        if not 1 <= args.days <= 366:
            parser.error("--days must be 1..366")
        policy = json.loads(args.policy.read_text(encoding="utf-8"))
        expires = int(time.time() * 1000) + args.days * 86_400_000
        config["playbackPolicySigned"] = signed_policy(policy, args.key.read_bytes(), args.public.read_bytes(), expires)
    if args.no_announcement:
        config.pop("announcement", None)
    elif args.announce is not None:
        text = args.announce.strip()
        if not text:
            parser.error("--announce needs a text")
        # The app shows an announcement once per id and only ids above the last one shown.
        config["lastAnnouncementId"] = int(config.get("lastAnnouncementId", 0)) + 1
        config["announcement"] = {"id": config["lastAnnouncementId"], "message": text}
    args.config.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    print(f"wrote {args.config}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
