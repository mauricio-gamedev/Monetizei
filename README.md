# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.5
- Native Android/Kotlin game remains lightweight and Compose-free.
- Persistent local soft coins + XP remain non-cash.
- Anonymous installation UUID + EC P-256 signing key in Android Keystore.
- Every completed session is signed before it leaves the device.
- Production Android builds point to `https://monetizei-production.up.railway.app`.
- Server state is durable in SQLite on the Railway `/data` volume.
- New append-only reward ledger is separate from the gameplay ledger.
- Monetary values are stored as integer BRL cents on the server, never calculated or trusted from the APK.
- Reward states are `PENDING -> APPROVED -> AVAILABLE` and transitions are server-controlled.
- Every reward has a unique `gameplay_ledger_id`, preventing one accepted session from generating money twice.
- Reward policy has a minimum verified score, per-installation daily cap and global UTC daily budget.
- Automatic production rewards are disabled by default until a funded budget is explicitly configured.
- The Android result screen can display the latest server wallet snapshot: pending, approved and available.
- Ad views/clicks are not reward inputs and must never directly create withdrawable balance.

## Reward configuration
Production defaults create **zero monetary liability**. To enable funded rewards later, configure server environment variables:

- `MONETIZEI_REWARD_CENTS_PER_SESSION` (default `0`)
- `MONETIZEI_DAILY_REWARD_BUDGET_CENTS` (default `0`)
- `MONETIZEI_MIN_REWARD_SCORE` (default `20`)
- `MONETIZEI_MAX_REWARDS_PER_INSTALLATION_DAY` (default `10`)

Both reward amount and daily budget must be greater than zero before pending rewards are created.

## Fail-safe behavior
Gameplay never depends on networking. If the device is offline, a request times out or the backend rejects a temporary request, the signed session remains queued locally and gameplay continues.

## Backend storage
The backend reads `MONETIZEI_DB_PATH`. Railway uses `MONETIZEI_DB_PATH=/data/monetizei.db` with a persistent volume mounted at `/data`.

SQLite stores installation identities, accepted gameplay evidence and the reward ledger. PayPal credentials and payout secrets must never be placed in the APK.

## Security boundary
A valid device signature proves possession of the installation key; it does not by itself prove honest gameplay. `PENDING` is intentionally not withdrawable. Approval must remain server-side and should later depend on stronger fraud/risk checks. Only `AVAILABLE` can become eligible for payout processing in a later release.

## Build requirements
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- Kotlin 2.3.21
- SQLite JDBC 3.53.4.0
- Android SDK / compileSdk 37 (Android build only)
- Docker for container validation/deployment

## Next milestone
1. Validate the v0.5 wallet response against the live Railway backend.
2. Add authenticated server-issued challenges and stronger anti-fraud scoring.
3. Add a protected review/approval path for pending rewards.
4. Add payout eligibility and minimum-withdrawal rules.
5. Integrate PayPal sandbox first, then production payouts.
6. Add compliant advertising separately from cash-reward authorization.
