# Monetizei

Android game-first project that separates gameplay progression, advertising and real-world reward authorization from day one.

## v0.3
- Native Android/Kotlin game remains lightweight and Compose-free.
- Persistent local soft coins + XP remain non-cash.
- Anonymous installation UUID + EC P-256 signing key in Android Keystore.
- Every completed session is signed before it leaves the device.
- Durable local outbox saves registration and sessions before any network attempt.
- Android HTTP uploader runs off the gameplay thread and retries from the local queue.
- Shared strict JSON protocol is used by both Android and backend.
- Real JVM HTTP backend exposes `GET /health`, `POST /v1/installations` and `POST /v1/sessions`.
- End-to-end HTTP test launches the backend, registers a public key, submits a real signed session and verifies replay rejection.
- Anti-replay, rate limiting and append-only verified-gameplay ledger remain server-side.
- CI tests Android + protocol + server, builds the backend distribution and uploads the debug APK.

## Fail-safe behavior
Gameplay never depends on networking. If the backend URL is not configured, the device is offline, a request times out or the backend rejects a temporary request, the signed session remains queued locally and gameplay continues.

## Backend URL
`BuildConfig.MONETIZEI_API_BASE_URL` is intentionally empty by default. A production HTTPS URL will be connected only after the backend is deployed. PayPal credentials, payout secrets and monetary authorization must never be placed in the APK.

## Reward boundary
The APK can grant only non-cash progression. A valid session signature proves possession of the installation key; it does not prove honest gameplay and does not create withdrawable value. Real-world rewards must be authorized later by server-side eligibility, fraud, policy and budget checks. Ad views/clicks must never directly generate withdrawable balance.

## Build requirements
- JDK 17
- Gradle 9.5.0
- Android Gradle Plugin 9.3.0
- Kotlin 2.3.21
- Android SDK / compileSdk 37

## Next milestone
1. Deploy the HTTP backend behind HTTPS.
2. Configure the production Android API URL.
3. Replace in-memory backend state with durable database storage.
4. Add server-issued challenges and stronger anti-fraud signals.
5. Add authoritative reward eligibility + budget controls.
6. Only then integrate compliant advertising and payout processing.
