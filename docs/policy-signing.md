# Playback policy signing (`playbackPolicySigned`)

The heartbeat response carries the remote playback policy twice:

- `playbackPolicy` — the plain object older apps already consume
  (`docs/playback-policy.md`). Kept unchanged.
- `playbackPolicySigned` — the same policy as an exact JSON string plus an
  ECDSA P-256 signature, for apps that only trust a policy they can verify:

```json
"playbackPolicySigned": {
  "payload": "{\"policyVersion\":1,\"allowSourceEngineFallback\":true,\"expiresAtEpochMs\":1758000000000}",
  "sig": "MEUCIQD…",
  "kid": "1"
}
```

`payload` is `JSON.stringify(policyObject)` — the object is built once and the
very same instance is served as `playbackPolicy`, so `JSON.parse(payload)`
deep-equals `playbackPolicy`. `sig` is `crypto.sign("sha256", utf8(payload),
privateKey)`: an ECDSA-SHA256 signature in DER, base64. `kid` names the key.

## Server configuration

| Env | Meaning |
| --- | --- |
| `POLICY_SIGNING_PRIVATE_KEY_PEM` | PKCS#8 PEM of a P-256 private key. Multi-line, or single-line with literal `\n`. Any other curve/type fails startup. |
| `POLICY_SIGNING_KID` | Key id sent as `kid` (default `"1"`). |

Unset key → `playbackPolicySigned` is omitted and one warning is logged at
boot; the unsigned `playbackPolicy` is still served for older apps. The signed
blob is cached (`currentPolicyEnvelope` in `server.js`) and rebuilt when the
policy changes — panel save/clear refreshes the cache immediately, an env
change needs a restart — or after 30 s, because the payload carries
`expiresAtEpochMs`. Signing is sub-millisecond, so the cache is only there to
keep `payload` and `playbackPolicy` byte-for-byte consistent within a window.

## Key material

- Private key: **never in the repository**. Generate with
  `python3 scripts/generate_policy_keys.py /secure/path/policy_signing_private.pem`
  (python `cryptography` → `openssl genpkey` → prints a `node -e` fallback)
  and put its contents in `POLICY_SIGNING_PRIVATE_KEY_PEM` on the receiver.
  `scripts/scan_secrets.py` fails CI on any committed PEM private key.
- Public key: `IptvPlayer/app/policy_public_key.pem` (SPKI PEM). The Android
  build embeds it (`security/PolicyPublicKey.kt`). **It already exists in the
  tree; the generator refuses to overwrite it unless `--force` is passed for a
  planned rotation.**

## Client behaviour (fail closed)

An app that understands `playbackPolicySigned`:

1. verifies `sig` over the exact UTF-8 bytes of `payload` against every
   `PUBLIC KEY` block embedded in `BuildConfig.POLICY_PUBLIC_KEY_PEM` (the
   contents of `IptvPlayer/app/policy_public_key.pem`; up to four blocks);
   `kid` is logged for diagnostics only, the app does not pin keys by id;
2. only then parses `payload` (never the loose `playbackPolicy` object);
3. treats a missing or unverifiable envelope as **no policy**: the previously
   stored policy expires at its `expiresAtEpochMs` and the app returns to
   built-in defaults. It does not fall back to the unsigned object.

Consequence: once an app version that verifies signatures is in the field, the
receiver must be configured with the private key, or those devices stop
receiving policy after their TTL (kill switches and device overrides no longer
apply). The boot warning exists for exactly this.

## Rotation

1. Generate a new pair with `--force` to a **new** private-key path and a
   temporary public path (`--public-out /tmp/policy_public_key_2.pem`).
2. Append the new public block to `IptvPlayer/app/policy_public_key.pem`
   (old block first, new block after it); ship that app version and let it
   reach the fleet (rollout policy, `UPDATE_ROLLOUT.md`).
3. Switch the receiver to the new private key and bump `POLICY_SIGNING_KID`.
   Apps that only embed the old block now fail closed, so wait for step 2 to
   complete first.
4. Remove the old block from the PEM file in a later release.

Compromise of the private key: rotate immediately (steps 1-3 with the old
block removed in the same app release). Until the new app is installed, a
device can be fed a forged policy, which is bounded to the closed schema in
`docs/playback-policy.md` (kill switches and buffer/engine hints, no URLs or
code).

## Tests

`node --test crash-receiver/qoe-release-health.test.js` signs a policy with a
generated P-256 key, verifies it with the public half, rejects a tampered
payload, and refuses RSA/P-384 keys. `python -m pytest
scripts/test_generate_policy_keys.py` covers the generator's overwrite guard
and backend fallbacks.
