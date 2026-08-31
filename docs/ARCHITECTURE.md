# Monetizei v0.1 — Architecture

## Goal
A game-first Android application with a reward economy that cannot be manipulated from the client.

## Trust boundaries

### Android client
- Renders gameplay.
- Captures session telemetry.
- Holds only non-cash local progression during development.
- Never authorizes real-world payouts.
- Never stores PayPal credentials or payout secrets.

### Reward backend (next phase)
- Authenticates user/device/session.
- Validates gameplay events.
- Runs anti-fraud/rate-limit checks.
- Maintains the authoritative reward ledger.
- Creates payout requests only after eligibility checks.

### Payout worker (future)
- Isolated from gameplay API.
- Holds PayPal server credentials.
- Uses idempotency keys.
- Reconciles payout status through PayPal APIs/webhooks.

### Advertising
- No ad SDK is included in v0.1.
- Advertising must never directly mint withdrawable balance.
- Rewarded ads, if later approved for the product, may grant only compliant in-app/non-transferable benefits.

## Ledger model (planned)
The server ledger should be append-only:
- `earn_pending`
- `earn_approved`
- `earn_rejected`
- `payout_requested`
- `payout_processing`
- `payout_paid`
- `payout_failed`

The displayed balance is derived from ledger entries; it is never a client-editable number.
