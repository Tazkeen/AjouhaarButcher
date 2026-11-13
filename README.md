# Ajouhaar Moslem Butchery

**Sales and Stock Management System**

[![Project CI](https://github.com/Tazkeen/AjouhaarButcher/actions/workflows/project-ci.yml/badge.svg)](https://github.com/Tazkeen/AjouhaarButcher/actions/workflows/project-ci.yml)

Ajouhaar is an Android prototype for a butchery team. It supports staff sign-in, product and stock viewing, checkout, transaction history, and manager-controlled staff and inventory administration. The Android client is built with Kotlin and Jetpack Compose, connects to an ASP.NET Core API, and uses Firebase Authentication and Cloud Firestore for identity and shared business data.

This repository contains the Android application and API. **It does not contain the website source code.** Website-specific implementation details and screenshots must be added from the group's website project before the combined academic submission is described as containing both systems.

## Purpose and problem

The system is intended to help a manager and cashiers complete shop sales and keep product stock records current. Manual checkout and separate stock records can lead to arithmetic errors, missed deductions and inconsistent records. The app brings product lookup, payment entry, receipts and stock updates into one workflow, while the API applies access rules and performs shared data changes.

## Project objectives

- Provide an Android interface that staff can use on a physical phone.
- Let staff record sales and review transaction information.
- Let managers maintain staff profiles, products and stock.
- Store shared business records in Firestore behind a server API.
- Validate inputs and reject sales that exceed available inventory.
- Use Firebase Authentication for staff identity and Google SSO for linked staff accounts.
- Automate Android unit tests and builds and compile the API in GitHub Actions.

## Features in the repository

| Area | Implemented capability |
|---|---|
| Sign-in | Manager-provisioned username/password login through the API and Firebase custom-token exchange |
| SSO | Google sign-in through Android Credential Manager and Firebase for Google identities linked to active staff profiles |
| Device security | Optional Android biometric prompt to unlock an account already signed in on that device |
| Dashboard | Greeting, sales and product summary, low-stock count, recent activity and shortcuts |
| Sales | Product search, basket, cash/card/EFT selection, payment entry, change calculation and receipt display |
| Products | Catalogue search and manager-only product creation |
| Stock | Current stock display and manager stock adjustments; sales record stock deductions |
| Transactions | Transaction list and API-backed sales data subject to staff permissions |
| Reports | Daily sales summary and stock-report API endpoints; the Android summary uses the data loaded for the signed-in user |
| Staff | Manager-only listing, creation, update and removal through the API; optional Google email linking |
| Help | In-app explanations for sign-in, sales, stock, transactions and connection troubleshooting |
| Database | Firestore collections for staff profiles, products, sales, sale items and stock movements |
| Sample data | Guarded script that writes ten simulated examples per assignment dataset when deliberately run against the named Firebase project |

Public self-service registration is disabled. A manager creates staff profiles and credentials. This is the implemented account-registration workflow; there is no public create-account form in the current login screen.

## Technologies

| Component | Technology |
|---|---|
| Mobile app | Kotlin, Android SDK, Jetpack Compose, Material 3, AndroidX |
| Android build | Gradle 9.1.0 wrapper, Android Gradle Plugin 9.0.0, Kotlin Compose plugin |
| Identity | Firebase Authentication, Google sign-in, Android Credential Manager, Android BiometricPrompt |
| API | ASP.NET Core 8 REST API, Firebase Admin SDK, Google Cloud Firestore client |
| Database | Cloud Firestore document collections and a nested sale-items subcollection |
| Local demo persistence | SQLite repository implementation is included for the standalone prototype; the configured app entry point currently uses the Firebase/API repository |
| Automated checks | Kotlin/JUnit unit tests and GitHub Actions; API build is included in CI |

## Repository structure

```text
app/                                  Android app, resources and unit tests
backend/AjouhaarButchery.api/         ASP.NET Core API and controllers
backend/seed-firestore/               Guarded simulated Firestore data utility
gradle/                               Gradle wrapper
.github/workflows/project-ci.yml      Android and API CI workflow
docs/                                 Project-purpose and development report
Final_Submission_Documentation/       Academic report drafts and checklists
```

## System requirements

- Android Studio and Android SDK Platform 36.
- JDK 17 or newer for the configured Gradle build.
- .NET SDK 8 to run/build the API currently in this repository.
- Node.js 18 or newer only when using the Firestore sample-data utility.
- A Firebase project configured for the Android application and Firestore.
- For a physical-phone local demo: the API computer and Android phone on the same Wi-Fi, a reachable computer LAN address, and the API allowed through the private-network firewall.

The API targets .NET 8. Microsoft lists .NET 8 support as ending on 10 November 2026; this project should be migrated to a supported .NET target before a real deployment. The assignment CI retains .NET 8 to match the checked-in API target. [Microsoft .NET support policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core)

## Install and build the Android app

Clone the repository and open its root folder in Android Studio, or use PowerShell:

```powershell
git clone https://github.com/Tazkeen/AjouhaarButcher.git
cd AjouhaarButcher
```

The Android Firebase client configuration is in `app/google-services.json`. It is client configuration, not an administrative credential. Do not add Firebase service-account JSON, signing keys, passwords or tokens to the repository.

Build and run unit tests:

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
```

Connect an Android phone with USB debugging enabled and select it as the Android Studio deployment target. Alternatively, install the debug APK generated at `app/build/outputs/apk/debug/app-debug.apk` using Android Studio. The current debug API default is `http://192.168.3.47:5159`; change it for the current LAN using a Gradle property:

```powershell
./gradlew.bat :app:assembleDebug -PapiBaseUrl=http://<computer-LAN-IP>:5159
```

For the Android emulator, use `-PapiBaseUrl=http://10.0.2.2:5159`. Cleartext HTTP is permitted only in the debug manifest for a private-network demonstration. Release builds use Android's default cleartext-disabled policy and should use an HTTPS API.

## Start the API locally

Install the .NET 8 SDK. Keep the Firebase service-account JSON outside the repository, then set the environment variable in PowerShell:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\path\outside\the\repository\firebase-service-account.json'
work\run-api-local.cmd
```

The service listens on port 5159. Keep the terminal open during the phone demonstration. The phone and computer must share the local Wi-Fi. `GET /api/connection/firestore` checks API-to-Firestore access. Swagger is available at `http://localhost:5159/swagger` in Development. The API is not hosted publicly by this repository.

## Database design

Firestore is a NoSQL document database, so the assignment's datasets are collections rather than relational SQL tables. The API uses these paths:

| Collection/path | Purpose and representative fields |
|---|---|
| `staffUsers/{uid}` | Username, full name, role, active state, optional Google email, password salt/hash/iteration count and creation time |
| `products/{id}` | Product name, category, unit, price/cost per kilogram, stock, reorder level and active state |
| `sales/{id}` | Receipt, cashier, customer/payment fields, total, status, timestamps and idempotency metadata |
| `sales/{id}/items/{productId}` | Product snapshot, quantity in kilograms, unit price and line total |
| `stockMovements/{id}` | Product, quantity change, reason, staff, related sale, notes and timestamp |

Sale items are nested below their sale. Firestore does not provide SQL foreign-key constraints; the API validates references and performs sale and inventory mutations in Firestore transactions. The checked-in seeder creates ten simulated records for products, inactive sample staff profiles, sales, sale items and stock movements. Running the script and checking the live Firebase counts are separate manual steps; this README does not claim a current live count.

The repository does not include an exported `firestore.rules` file, so the live Firebase client rules cannot be verified from source control. Before demonstration, confirm in the Firebase Console that direct client reads/writes are denied as intended. The API's Admin SDK uses service-account IAM credentials and must remain on the server.

## Authentication and security

- Staff passwords are entered at the API and verified against a per-account PBKDF2-SHA256 hash with a random salt; plaintext passwords are not stored in Firestore.
- A successful username/password check returns a Firebase custom token. Android exchanges it for a Firebase ID token and sends that ID token to protected API routes.
- Google SSO is available only to Google identities linked to an active staff profile. A Google sign-in alone does not provision a shop staff account.
- API authorization policies restrict staff administration, product writes, reports and stock adjustments to Managers. Cashiers have narrower access to sales.
- BiometricPrompt is a local device unlock convenience after sign-in; it does not replace server-side identity or role checks.
- Service-account credentials and release signing keys are excluded from source control. Never paste passwords, Firebase tokens or private keys into an issue, screenshot or CI log.
- The local phone demo sends API traffic over private-network HTTP. Do not use that configuration on public networks or for real business use; configure HTTPS first.

Official Firebase references: [Google sign-in on Android](https://firebase.google.com/docs/auth/android/google-signin), [Firestore security rules](https://firebase.google.com/docs/firestore/security/rules-conditions).

## Website and mobile application

The mobile client is the Kotlin app described above. No website source, build system or website screenshots are present in this repository, so this README cannot verify or describe website pages and their behavior. Add the website repository or confirmed website implementation details and real screenshots before the combined system submission. Do not substitute a mockup for a working site's evidence.

## Screenshots

No website or app demonstration screenshots are currently checked into this repository. Capture the actual running app and website in use, then add the real image files and update this section with their repository-relative links. Do not create paths for screenshots that have not been added.

## Video demonstration

**Video link:** `[Add the uploaded demonstration video link here]`

The recording should show the app on a physical Android phone, explain the workflows with voice-over, and show the Firebase Authentication, API and database evidence without exposing passwords, tokens, hashes or service-account credentials.

## GitHub and DevOps

The source repository is [Tazkeen/AjouhaarButcher](https://github.com/Tazkeen/AjouhaarButcher). The `.github/workflows/project-ci.yml` workflow runs on pushes and pull requests to `main`, and can also be started manually. It runs Android unit tests and builds a debug APK, then restores and compiles the ASP.NET API. It does not publish a release, deploy the API, or connect to Firebase. A workflow only counts as passed after a successful run is visible in the repository's Actions tab.

Recommended team practice: make small topic branches, review changes through pull requests, let CI complete, and merge approved work into `main`. Keep credentials in protected local configuration or GitHub Actions secrets only when needed; the current CI workflow requires no Firebase service-account secret.

Project documentation: [User Guide](USER_GUIDE.md), [Release Notes](RELEASE_NOTES.md), [Final Submission Documentation drafts](Final_Submission_Documentation/README.md), and [App Purpose and Development Report](docs/Ajouhaar_App_Purpose_and_Development_Report.docx).

## Automated tests and verification

The Android JUnit suite currently covers money parsing/formatting, registration input validation, sale validation, stock deduction, change calculation, insufficient stock, product validation, stock adjustment validation and manager-provisioned demo staff authentication. Run it with `./gradlew.bat :app:testDebugUnitTest`.

The GitHub Actions workflow also compiles the API. There is no API unit/integration test project or automated Compose UI test in the repository yet. Live Firebase/API permission behavior, SSO, physical-device navigation and video evidence must be verified manually. Do not describe a workflow as passed until it has run successfully.

Android UI automation references: [Compose testing](https://developer.android.com/develop/ui/compose/testing). GitHub CI references: [Building and testing with GitHub Actions](https://docs.github.com/en/actions/tutorials/build-and-test-code), [workflow security](https://docs.github.com/en/actions/reference/security/secure-use).

## Signed release APK

No signing key or signing credentials are included. Create a keystore in a private location and keep a secure backup. For example, use `keytool -genkeypair` from the JDK and answer its prompts locally. Then set the following environment variables in the same PowerShell session:

```powershell
$env:ANDROID_KEYSTORE_PATH = 'C:\private\ajouhaar-release.jks'
$env:ANDROID_KEYSTORE_PASSWORD = '<enter-your-keystore-password>'
$env:ANDROID_KEY_ALIAS = '<your-key-alias>'
$env:ANDROID_KEY_PASSWORD = '<enter-your-key-password>'
./gradlew.bat :app:assembleRelease
```

The Gradle configuration uses signing values only when all four variables are set. Do not commit the keystore or passwords. Verify the resulting release APK's signature before distribution. No Google Play publication is claimed.

## Known limitations and outstanding submission evidence

- Website source and website screenshots are absent from this repository.
- No actual app screenshots or uploaded video link are present yet.
- Public self-registration is intentionally not available; the manager provisions staff.
- The app's cart quantity control currently uses whole kilograms, even though the API data model can represent decimal kilograms.
- Reports and role-specific access need live end-to-end verification with manager and cashier accounts.
- Firebase project configuration, SSO provider setup, live security rules and sample-record counts need to be checked in the Firebase Console.
- The local physical-phone demo relies on the development computer's current LAN address and same-Wi-Fi HTTP.
- The API targets .NET 8, which reached end of support on 10 November 2026; migrate it to a supported runtime before real deployment.
- There are no API automated tests or Compose UI tests yet.

## Future improvements

- Add the website source to this repository or provide a documented link to its separate repository.
- Add Compose navigation/UI tests and API unit/integration tests, including Firebase authorization scenarios.
- Support decimal kilogram quantities end-to-end and show a stable connection state in the app.
- Deploy the API to a managed HTTPS host and configure release builds for that host.
- Upgrade the API to a supported .NET LTS release and keep its dependencies patched.
- Capture and add actual website/mobile screenshots and the final narrated demonstration video.

## References

- Android Developers, [Jetpack Compose](https://developer.android.com/develop/ui/compose).
- Android Developers, [Test your Compose layout](https://developer.android.com/develop/ui/compose/testing).
- Firebase, [Authenticate with Google on Android](https://firebase.google.com/docs/auth/android/google-signin).
- Firebase, [Cloud Firestore security rules](https://firebase.google.com/docs/firestore/security/rules-conditions).
- GitHub Docs, [Building and testing code with Actions](https://docs.github.com/en/actions/tutorials/build-and-test-code).
- GitHub Docs, [Secure use reference for GitHub Actions](https://docs.github.com/en/actions/reference/security/secure-use).
- Microsoft, [.NET support policy](https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core).
