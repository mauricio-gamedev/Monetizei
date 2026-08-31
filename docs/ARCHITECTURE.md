# Monetizei v0.2 — Architecture

## Goal
A game-first Android application whose local gameplay, advertising and future real-world reward authorization remain separate trust domains.

## Modules

### `app`
- Native Android game loop.
- Persistent non-cash coins and XP.
- Anonymous installation UUID.
- EC P-256 signing key generated in Android Keystore.
- Monotonic session sequence.
- Signed telemetry outbox capped at 50 pending sessions.
- Never authorizes withdrawable value.

### `protocol`
Pure Kotlin/JVM shared contract used by Android and server:
- registration model;
- session payload/envelope model;
- deterministic canonical encoding;
- public-key fingerprinting;
- protocol limits/version.

Sharing this module prevents the client and server from silently drifting on signature bytes or field limits.

### `server`
Framework-free verification core that can later sit behind an HTTP layer:
- first-write-wins installation registry;
- ECDSA signature verification;
- payload validation;
- anti-replay sequence/session checks;
- rolling per-installation rate limit;
- append-only verified-gameplay ledger.

The current server module deliberately has no payout credentials and no ad-network dependency.

## Trust boundaries

### Android client
The device is untrusted for money. Android Keystore protects the private signing key from normal app storage extraction, but a valid signature alone does not prove honest gameplay or an uncompromised device.

### Reward backend
The backend is authoritative for accepted sessions, eligibility and future reward ledger entries. Production persistence must enforce uniqueness for installation/session IDs and sequence ordering transactionally.

### Payout worker (future)
- Isolated from gameplay ingestion.
- Holds PayPal server credentials only.
- Uses idempotency keys.
- Reconciles payout status through server-side APIs/webhooks.

### Advertising
Advertising remains outside reward authorization. Ad views/clicks must never directly mint withdrawable balance.

## Ledger split
v0.2 implements an append-only **verified gameplay ledger** containing score units only. A later monetary ledger may add states such as `earn_pending`, `earn_approved`, `earn_rejected`, `payout_requested`, `payout_processing`, `payout_paid` and `payout_failed` only after policy and fraud controls are ready.
