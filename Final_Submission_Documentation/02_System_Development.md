# System Development Explanation

## Development approach

The Android interface uses Kotlin and Jetpack Compose with Material 3. `MainActivity` assembles the screens and starts the Google and biometric flows. `AppViewModel` holds screen state and dispatches business operations. Repository interfaces separate screen logic from data operations; the configured app entry point uses `FirebaseApiRepository` to contact the API. A local SQLite repository and in-memory demo repository are also present as implementations, but they are not the configured production/demo entry point.

The ASP.NET Core API provides REST endpoints for authentication, products, sales, stock movements, reports and staff. It verifies Firebase ID tokens, loads the active staff role, validates inputs and uses Firestore operations for shared records. The guarded Node utility can populate simulated assignment data after the operator confirms the intended project.

## Authentication flow

For username/password login, Android sends the entered credentials to the API. The API checks a salted PBKDF2-SHA256 hash in the staff profile and issues a Firebase custom token when it matches. Firebase Authentication exchanges the custom token for a user session. Subsequent API requests carry a Firebase ID token. Google sign-in uses Credential Manager and Firebase; the API only grants app access if the identity matches an active staff profile.

The manager provisions staff profiles. Public self-registration is intentionally disabled in the current login UI. Optional device biometrics unlock an already authenticated session and do not replace Firebase identity or server-side authorization.

## Business workflows

The sales workflow selects products, records a payment method and amount paid, validates stock and payment, then submits the sale. The API calculates totals and performs sale and inventory writes transactionally. Products and stock are viewed on dedicated screens; manager-only changes are also authorized by the server. Transaction history and reports use the data loaded for the current staff role.

## Development and demo setup

The Android project uses Gradle 9.1.0 and JDK 17+. The API is an ASP.NET Core 8 project using Firebase Admin and Firestore libraries. The local physical-phone demonstration expects the API on port 5159 and the phone/computer on the same Wi-Fi. Its HTTP endpoint and LAN address are development-only; public use requires HTTPS hosting.

## Limitations to state

The cart currently accepts whole-kilogram increments, though backend records support decimal kilograms. The repository contains no website source, Compose UI test project, API automated test project, deployment configuration or verified screenshots/video. The API target .NET 8 reached end of support on 10 November 2026 and should be upgraded before real deployment.
