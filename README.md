# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.5.1
- Native Android/Kotlin game remains lightweight and Compose-free.
- Persistent local soft coins + XP remain non-cash.
- Anonymous installation UUID + EC P-256 session-signing key in Android Keystore.
- Every completed session is signed before it leaves the device.
- Production Android builds point to `https://monetizei-production.up.railway.app`.
- Server state is durable in SQLite on the Railway `/data` volume.
- Reward ledger is separate from the gameplay ledger and now records an explicit currency for every reward.
- Supported reward currencies are `BRL` and `USD`; each has independent pending, approved and available wallet balances.
- Existing v0.5 reward rows migrate automatically to `BRL`, preserving previous balances.
- Reward states remain `PENDING -> APPROVED -> AVAILABLE` and transitions are server-controlled.
- Every reward has a unique `gameplay_ledger_id`, preventing one accepted session from generating money twice.
- Reward policy has a minimum verified score, per-installation daily cap and global UTC daily budget.
- Automatic production rewards remain disabled by default until a funded budget is explicitly configured.
- The v0.5.1 Android result screen displays both BRL and USD wallet balances.
- The v0.5.1 backend keeps the legacy BRL wallet fields so v0.5.0 clients remain compatible during rollout.
- Ad views/clicks are not reward inputs and must never directly create withdrawable balance.
- Gradle and GitHub Actions now support a stable APK update-signing key stored only in GitHub Secrets.

## Reward configuration
Production defaults create **zero monetary liability**. To enable a funded reward program later, configure server environment variables:

- `MONETIZEI_REWARD_CURRENCY` (`BRL` or `USD`, default `BRL`)
- `MONETIZEI_REWARD_CENTS_PER_SESSION` (default `0`)
- `MONETIZEI_DAILY_REWARD_BUDGET_CENTS` (default `0`)
- `MONETIZEI_MIN_REWARD_SCORE` (default `20`)
- `MONETIZEI_MAX_REWARDS_PER_INSTALLATION_DAY` (default `10`)

Both reward amount and daily budget must be greater than zero before pending rewards are created. A single configured reward policy issues one currency; the ledger and wallet can retain balances from both currencies over time.

## Stable APK update signing
The public repository never stores the private signing key. CI supports an optional stable signed-update artifact when the required GitHub Secrets are configured. See `docs/SIGNING.md`.

The v0.5.0 and earlier test APKs used ordinary CI debug signing, so the first cutover to a new stable key can require a one-time uninstall. After that cutover, keep the signing key permanent for that update channel.

## Fail-safe behavior
Gameplay never depends on networking. If the device is offline, a request times out or the backend rejects a temporary request, the signed session remains queued locally and gameplay continues.

## Backend storage
The backend reads `MONETIZEI_DB_PATH`. Railway uses `MONETIZEI_DB_PATH=/data/monetizei.db` with a persistent volume mounted at `/data`.

SQLite stores installation identities, accepted gameplay evidence and the multi-currency reward ledger. PayPal credentials, payout secrets and APK signing secrets must never be placed in the APK.

## Security boundary
A valid device session signature proves possession of the installation key; it does not by itself prove honest gameplay. `PENDING` is intentionally not withdrawable. Approval must remain server-side and should later depend on stronger fraud/risk checks. Only `AVAILABLE` can become eligible for payout processing in a later release.

## Build requirements
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- Kotlin 2.3.21
- SQLite JDBC 3.53.4.0
- Android SDK / compileSdk 37 (Android build only)
- Docker for container validation/deployment

## Next milestone
1. Validate BRL + USD wallet migration against the live Railway SQLite volume.
2. Activate one permanent APK update-signing key through GitHub Secrets.
3. Add authenticated server-issued challenges and stronger anti-fraud scoring.
4. Add a protected review/approval path for pending rewards.
5. Add payout eligibility and minimum-withdrawal rules.
6. Integrate PayPal sandbox first, then production payouts.
7. Add compliant advertising separately from cash-reward authorization.
