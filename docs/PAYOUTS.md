# Monetizei PayPal payouts

The v0.6 payout path is disabled by default. A withdrawal request is signed by the same Android Keystore identity used by gameplay telemetry. The backend withdraws the full AVAILABLE balance for the requested currency, reserves those reward rows as PAYOUT_PENDING, and uses the withdrawal request UUID as the PayPal `sender_batch_id` / idempotency identifier.

## Required Railway variables

Keep payouts disabled until sandbox credentials are ready:

- `MONETIZEI_PAYPAL_PAYOUTS_ENABLED=false`
- `MONETIZEI_PAYPAL_MODE=sandbox`
- `MONETIZEI_PAYPAL_CLIENT_ID=<sandbox client id>`
- `MONETIZEI_PAYPAL_CLIENT_SECRET=<sandbox client secret>`
- `MONETIZEI_PAYPAL_RECEIVER_EMAIL=<sandbox receiver email>`

When sandbox is fully validated, live mode requires an explicit change to:

- `MONETIZEI_PAYPAL_MODE=live`
- live PayPal client ID / secret
- the intended real PayPal receiver email
- `MONETIZEI_PAYPAL_PAYOUTS_ENABLED=true`

Never commit PayPal credentials to the repository or put them in the Android APK.

## State machine

`AVAILABLE -> PAYOUT_PENDING -> PAID`

- AVAILABLE: withdrawable balance.
- PAYOUT_PENDING: reserved while PayPal processes the payout; it is not available for a second withdrawal.
- PAID: PayPal confirmed the payout batch succeeded.
- A definitive PayPal failure releases the reserved rewards back to AVAILABLE.
- Network / HTTP 5xx ambiguity keeps the payout reserved and retries with the same request ID.

## PayPal prerequisites

PayPal Payouts requires a PayPal business account, Payouts access, account verification, and sufficient balance/funding for payout amount plus applicable fees. Always validate the integration in PayPal sandbox before enabling live payouts.
