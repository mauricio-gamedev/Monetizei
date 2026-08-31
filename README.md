# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.4
- Native Android/Kotlin game remains lightweight and Compose-free.
- Persistent local soft coins + XP remain non-cash.
- Anonymous installation UUID + EC P-256 signing key in Android Keystore.
- Every completed session is signed before it leaves the device.
- Durable local outbox saves registration and sessions before any network attempt.
- Android HTTP uploader runs off the gameplay thread and retries from the local queue.
- Real JVM HTTP backend exposes `GET /health`, `POST /v1/installations` and `POST /v1/sessions`.
- Server state is now durable in SQLite instead of memory-only.
- Installation registrations, replay state and accepted gameplay ledger survive backend restarts/redeploys.
- SQLite uses WAL, foreign keys, unique session/sequence constraints and indexed ledger lookups.
- Root `Dockerfile` builds only the server/protocol modules and is ready for Railway or another Docker host.
- CI tests Android + protocol + server, persistence across restart, backend distribution, Docker image and APK.

## Fail-safe behavior
Gameplay never depends on networking. If the backend URL is not configured, the device is offline, a request times out or the backend rejects a temporary request, the signed session remains queued locally and gameplay continues.

## Backend storage
The backend reads `MONETIZEI_DB_PATH`. Default: `./data/monetizei.db`.

For a persistent host, mount durable storage and point `MONETIZEI_DB_PATH` inside that mount. The database stores verified gameplay evidence only; it does not store a client-editable cash balance.

## Backend URL
`BuildConfig.MONETIZEI_API_BASE_URL` is still empty until a public HTTPS deployment exists. After deployment, the generated HTTPS domain will be connected to the APK in a small follow-up build. PayPal credentials, payout secrets and monetary authorization must never be placed in the APK.

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

## Deployment target
The first deployment target is Railway Free because it supports GitHub/Docker deployments, public HTTPS domains and a persistent volume on its free tier. See `docs/DEPLOY_RAILWAY.md`.

## Next milestone
1. Deploy the v0.4 backend and attach persistent storage.
2. Generate its public HTTPS domain.
3. Connect that HTTPS URL to the Android build and drain the signed-session queue.
4. Add server-issued challenges and stronger anti-fraud signals.
5. Add authoritative reward eligibility + budget controls.
6. Only then integrate compliant advertising and payout processing.
