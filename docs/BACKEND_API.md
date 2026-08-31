# Monetizei v0.2 — Backend API contract

The v0.2 repository contains the protocol and server-side verification core, but intentionally does not expose a public HTTP service yet.

## 1. Register installation

Future endpoint: `POST /v1/installations/register`

Payload fields:
- `protocolVersion`
- `installationId` — anonymous UUID generated once by the app
- `keyId` — SHA-256 fingerprint of the X.509 public key
- `publicKeyBase64` — EC P-256 public key; the private key stays in Android Keystore
- `signatureAlgorithm` — `SHA256withECDSA`
- `appVersion`
- `createdAtEpochMs`

Registration is first-write-wins for a given installation ID. Re-registering the same key is idempotent; attempting to replace it with another key is rejected.

## 2. Submit gameplay session

Future endpoint: `POST /v1/sessions`

Envelope fields:
- `payload.protocolVersion`
- `payload.installationId`
- `payload.sessionId`
- `payload.sequence`
- `payload.startedAtEpochMs`
- `payload.finishedAtEpochMs`
- `payload.durationMs`
- `payload.score`
- `payload.appVersion`
- `keyId`
- `signatureAlgorithm`
- `signatureBase64`

The signature covers the deterministic byte representation produced by `CanonicalSessionCodec`.

## Server acceptance order
1. Installation must already be registered.
2. Envelope key ID must match the registered key.
3. Payload shape/ranges must be valid.
4. ECDSA signature must verify against the registered public key.
5. Session ID and monotonic sequence must not be replayed.
6. Per-installation rate limit must allow the session.
7. Only then is an append-only verified-gameplay ledger entry created.

## Important trust rule
A valid device signature proves that the payload was signed by that installation key. It does **not** prove the device or gameplay is honest. Production anti-fraud still needs server-side behavioral analysis, device/app integrity signals where appropriate, anomaly detection and account-level limits.

## Money boundary
The v0.2 ledger stores verified gameplay score units only. It contains no currency amount and cannot create PayPal balance. A future reward-policy service must independently decide whether a verified session is eligible for any real-world reward.
