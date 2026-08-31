# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.1
- Native Android/Kotlin, no Compose.
- Lightweight `SurfaceView` game loop.
- 30-second tap challenge prototype.
- Persistent local soft coins + XP.
- Reward boundary separated behind `RewardRepository`.
- Unit tests for reward rules and malformed/impossible session input.
- Android 8+ compatible immersive-mode fallback.
- No AdMob SDK and no PayPal secrets/client payout code.
- Architecture prepared for server-authoritative rewards and payout processing.

## Reward boundary
The APK can grant only non-cash progression. Withdrawable money must be created by the future backend after gameplay, eligibility and anti-fraud checks. Ad views/clicks must never directly generate withdrawable balance.

## Build requirements
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- Kotlin Android plugin 2.3.21
- Android SDK / compileSdk 37

## Next milestone
1. Anonymous installation/user identity.
2. Signed session telemetry model.
3. Backend API contract.
4. Append-only server ledger.
5. Anti-replay + rate limiting.
6. Only then: compliant monetization and payout integration.
