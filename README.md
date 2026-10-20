# Ajouhaar Moslem Butchery

Android app and ASP.NET Core API prototype for butchery sales, products and stock management. The Android app is built with Kotlin and Jetpack Compose; Firebase Authentication provides identity and Cloud Firestore stores shared records.

## Project layout

- `app/` — Android app (minimum Android 8.0 / API 26).
- `backend/AjouhaarButchery.api/` — ASP.NET Core 8 API.
- `backend/seed-firestore/` — guarded Node.js utility for simulated Firestore data.
- `gradle/` — Gradle wrapper (Gradle 9.1.0).

The Firebase Android client configuration is in `app/google-services.json`. It contains public client configuration, not an administrator credential. Keep the Firebase service-account JSON outside the repository and provide it to the API through `GOOGLE_APPLICATION_CREDENTIALS`. Never commit service-account keys, local backups, SDK paths, or signing credentials.

## Authentication

Staff log in with manager-provisioned usernames and passwords. The API verifies a per-account salted PBKDF2-SHA256 password hash stored in Firestore and returns a Firebase custom token; the Android app exchanges it for a Firebase ID token. Passwords are not stored in plaintext. Firebase Google sign-in provides SSO for Google identities linked to an active staff profile. Only a Manager may list, add, update, or remove staff. New public self-registration is disabled.

The demo staff accounts currently provisioned in Firebase are:

| Role | Username |
|---|---|
| Manager | `sajouhaar` |
| Cashier | `cashier1` |
| Cashier | `cashier2` |

Demo passwords are provided to the group outside source control. Change the short assignment-demo passwords before any real deployment.

## Run the Android app and API locally

Install Android Studio with Android SDK Platform 36 and JDK 17+, and install the .NET 8 SDK. The phone and computer must be on the same Wi-Fi for the local API demo.

In PowerShell, set the path to a service-account JSON kept outside the repository, then start the API from the project root:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\path\outside\the\repository\firebase-service-account.json'
work\run-api-local.cmd
```

The API listens on port `5159`. The Android debug build currently uses `http://192.168.3.47:5159`; update the default in `app/build.gradle.kts` or pass `-PapiBaseUrl=http://<computer-LAN-IP>:5159` if your Wi-Fi address changes. For an Android emulator, use `-PapiBaseUrl=http://10.0.2.2:5159`. Keep the API terminal open during the demo. Same-Wi-Fi HTTP is for local development only; use a publicly reachable HTTPS API for off-network access.

Build and run the Android app:

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```

API connection status is available at `GET /api/connection/firestore`; Swagger is available at `/swagger` in Development. More routes and the Firestore layout are documented in [backend/AjouhaarButchery.api/README-backend.md](backend/AjouhaarButchery.api/README-backend.md).

## Simulated data

The guarded seeder writes at least ten simulated examples for each assignment dataset: products, sales, sale items, stock movements and inactive sample staff profiles. It only writes when both the supplied project ID and service-account project ID match the approved project, and requires `--confirm-test-project`.

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\path\outside\the\repository\firebase-service-account.json'
node --use-system-ca backend/seed-firestore/seed.mjs ajouhaar-butchery-system --confirm-test-project
```

The active Firebase staff profiles are separately managed by the API and must not be replaced with the inactive sample profiles. Firebase client security rules deny direct client reads and writes; the server uses the Firebase Admin SDK.
