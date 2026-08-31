# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.2
- Native Android/Kotlin game remains lightweight and Compose-free.
- Persistent local soft coins + XP remain non-cash.
- Anonymous installation UUID persisted per install.
- EC P-256 signing key generated inside Android Keystore.
- Every completed 30-second session receives a UUID + monotonic sequence and ECDSA signature.
- Signed sessions are persisted in a bounded local outbox for future upload.
- Shared `protocol` module keeps Android/server signature bytes identical.
- New framework-free `server` core verifies registrations and signatures.
- Anti-replay and rolling rate limiting run before ledger insertion.
- Accepted sessions create append-only verified-gameplay ledger entries, not money.
- CI tests Android + protocol + server, builds the debug APK and uploads it as an artifact.

## Reward boundary
The APK can grant only non-cash progression. A valid session signature does not create withdrawable value. Real-world rewards must be authorized later by the backend after eligibility, fraud and policy checks. Ad views/clicks must never directly generate withdrawable balance.

## Build requirements
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- Kotlin JVM plugin 2.3.21 for shared/server modules
- AGP 9 built-in Kotlin support for Android
- Android SDK / compileSdk 37

## Next milestone
1. HTTP transport for registration/session upload.
2. Durable database-backed registry, replay guard and ledger.
3. Server-issued installation challenge / stronger enrollment.
4. Behavioral anti-fraud and integrity signals.
5. Reward-policy service with budget controls.
6. Only then: compliant ads and PayPal payout integration.
