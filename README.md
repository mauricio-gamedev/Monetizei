# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.4.1
- Native Android/Kotlin game remains lightweight and Compose-free.
- Persistent local soft coins + XP remain non-cash.
- Anonymous installation UUID + EC P-256 signing key in Android Keystore.
- Every completed session is signed before it leaves the device.
- Durable local outbox saves registration and sessions before any network attempt.
- Android HTTP uploader runs off the gameplay thread and retries from the local queue.
- Production Android builds now point to `https://monetizei-production.up.railway.app`.
- The finished-session screen shows live upload state: sending, synced, pending or HTTP error.
- Real JVM HTTP backend exposes `GET /health`, `POST /v1/installations` and `POST /v1/sessions`.
- Server state is durable in SQLite instead of memory-only.
- Installation registrations, replay state and accepted gameplay ledger survive backend restarts/redeploys when a persistent volume is attached.
- SQLite uses WAL, foreign keys, unique session/sequence constraints and indexed ledger lookups.
- Root `Dockerfile` builds only the server/protocol modules and is ready for Railway or another Docker host.
- CI tests Android + protocol + server, persistence across restart, backend distribution, Docker image and APK.

## Fail-safe behavior
Gameplay never depends on networking. If the device is offline, a request times out or the backend rejects a temporary request, the signed session remains queued locally and gameplay continues.

## Backend storage
The backend reads `MONETIZEI_DB_PATH`. Default: `./data/monetizei.db`.

For Railway, attach a persistent volume at `/data` and use `MONETIZEI_DB_PATH=/data/monetizei.db`. The database stores verified gameplay evidence only; it does not store a client-editable cash balance.

## Backend URL
Production API: `https://monetizei-production.up.railway.app`.

PayPal credentials, payout secrets and monetary authorization must never be placed in the APK.

## Reward boundary
The APK can grant only non-cash progression. A valid session signature proves possession of the installation key; it does not prove honest gameplay and does not create withdrawable value. Real-world rewards must be authorized later by server-side eligibility, fraud, policy and budget checks. Ad views/clicks must never directly generate withdrawable balance.

## Build requirements
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- Kotlin 2.3.21
- SQLite JDBC 3.53.4.0
- Android SDK / compileSdk 37 (Android build only)
- Docker for container validation/deployment

## Next milestone
1. Confirm the Android signed-session queue drains against the live Railway backend.
2. Attach/verify Railway persistent storage at `/data`.
3. Add server-issued challenges and stronger anti-fraud signals.
4. Add authoritative reward eligibility + budget controls.
5. Only then integrate compliant advertising and payout processing.
