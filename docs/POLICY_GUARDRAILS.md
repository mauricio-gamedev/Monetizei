# Policy guardrails

These are architectural rules, not legal advice.

1. Never credit withdrawable money because an ad was viewed or clicked.
2. Never tell users to click ads to support the project.
3. Never use normal ad impressions/clicks as a gameplay objective.
4. Rewarded-ad benefits, if used, must remain in-app and non-transferable unless the ad provider explicitly permits otherwise.
5. Real-money eligibility must be server-side and independent from ad engagement.
6. Do not implement wagering, paid entry, stake multipliers, loot-box cash conversion, or chance-based cash rewards without a separate legal/store-policy review.
7. Use test ads during development.
8. Never ship payout provider secrets in the APK.
9. All payout operations must be idempotent and auditable.
10. Add age, region, tax/identity and payout compliance gates before real-money launch.
