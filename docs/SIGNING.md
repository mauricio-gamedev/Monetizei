# Monetizei stable update signing

Android accepts an APK update only when the package name and signing identity are compatible with the installed app. Monetizei therefore supports a stable private signing keystore without storing that private key in this public repository.

## Security rules

- Never commit `.jks`, `.keystore`, passwords or private signing material.
- Keep at least one offline backup of the final signing keystore and its credentials.
- After the first public build is distributed with the stable key, treat that key as permanent for the sideload/update channel.
- GitHub Actions receives signing material only through repository secrets.
- The CI rejects tracked `.jks` and `.keystore` files.

## Required GitHub repository secrets

- `MONETIZEI_KEYSTORE_BASE64` — base64 encoding of the complete keystore file.
- `MONETIZEI_KEYSTORE_PASSWORD` — keystore password.
- `MONETIZEI_KEY_ALIAS` — signing key alias.
- `MONETIZEI_KEY_PASSWORD` — key password.

The private key itself must never appear in source code, Gradle files, issues, pull requests or build logs.

## CI behavior

Pull requests and ordinary verification continue to produce the normal debug APK. On a push to `main`, when all signing secrets are configured, CI additionally:

1. restores the keystore into the runner's temporary directory;
2. builds the release APK with the stable key;
3. verifies the APK signature with Android `apksigner`;
4. uploads the result as the `monetizei-update-signed` artifact;
5. discards the temporary runner and key copy after the job.

If the signing secret is not configured, the signed-update step is skipped rather than inventing a new key.

## One-time cutover note

The v0.5.0 and earlier test APKs were ordinary CI debug builds. A first APK signed with the new stable key may not install directly over an older APK signed by a different debug key. During the one-time signing cutover, uninstalling the old test build can be required. After the stable key is activated and used consistently, future APKs signed by that same key can update the stable-signed installation normally as long as version codes increase.

## Google Play

For a future Play Store release, use Google Play App Signing and preserve the distinction between the Play app-signing key and any upload key. Do not replace the production signing strategy casually after publication.
