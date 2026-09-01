# Monetizei — Asaas Pix payouts

Monetizei v0.7 adds a production Pix payout provider backed by Asaas. The Asaas API key and destination Pix key stay only on the backend. They must never be committed to GitHub or embedded in the APK.

## Railway variables

Keep the provider disabled while configuring it:

- `MONETIZEI_ASAAS_PIX_PAYOUTS_ENABLED=false`
- `MONETIZEI_ASAAS_API_KEY=<production API key starting with $aact_prod_>`
- `MONETIZEI_ASAAS_PIX_KEY=<destination Pix key>`
- `MONETIZEI_ASAAS_PIX_KEY_TYPE=CPF|CNPJ|EMAIL|PHONE|EVP`

When all values are verified and the Asaas account has enough available balance to fund the payout, enable:

- `MONETIZEI_ASAAS_PIX_PAYOUTS_ENABLED=true`

Asaas has priority over PayPal when both flags are enabled. During the migration to Pix, keep `MONETIZEI_PAYPAL_PAYOUTS_ENABLED=false` to avoid ambiguity.

## Provider behavior

The backend calls the Asaas production API at `https://api.asaas.com/v3` and authenticates with the `access_token` header. Every request also sends a Monetizei `User-Agent`.

A Pix payout is created with `POST /transfers` using:

- BRL amount;
- `operationType=PIX`;
- the configured Pix key and key type;
- the Monetizei withdrawal UUID as `externalReference`.

The returned Asaas transfer ID is stored in `provider_batch_id`. The app can then refresh the withdrawal; `GET /transfers/{id}` is used to reconcile the provider state.

- `PENDING` and other non-final states keep rewards in `PAYOUT_PENDING`.
- `DONE` moves the Monetizei reward ledger to `PAID`.
- `FAILED` / `CANCELLED` release the reserved rewards back to `AVAILABLE`.

## Duplicate-transfer protection

Asaas transfer creation does not use the same request-idempotency primitive as PayPal Payouts. To avoid accidentally sending money twice after a timeout or HTTP 5xx, Monetizei uses a reconciliation-only retry strategy for ambiguous Asaas submissions.

The request UUID is sent as `externalReference`. If the create call is ambiguous, the reward remains reserved and future refreshes search recent Asaas transfers for that exact reference. Monetizei does not blindly submit another real Pix transfer.

## Funding requirement

The Monetizei reward ledger and the Asaas account balance are separate. A reward being `AVAILABLE` in Monetizei does not create money in Asaas. Real Pix payouts can only succeed when the Asaas account has enough available balance to cover the transfer and any account-specific fees.

Do not enable production payouts until the destination Pix key has been checked and the Asaas account is funded for the intended payout amount.
