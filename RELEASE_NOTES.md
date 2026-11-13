# Release Notes

## Task 3 submission preparation

This section records changes made during final-submission preparation on 13 November 2026. The repository does not contain the original Task 1 source snapshot, so this report does not assert a precise file-by-file comparison against Task 1. Existing application features below are described from the source currently present.

### Added in this preparation

- Added a GitHub Actions workflow that runs Android unit tests, assembles the debug APK and compiles the ASP.NET API.
- Expanded the repository README with architecture, setup, data design, security boundaries, CI, limitations and submission evidence requirements.
- Added a plain-language user guide and academic documentation drafts, screenshot checklist and AI usage declaration template.
- Added unit coverage for invalid product and stock updates and manager-provisioned staff sign-in/removal.
- Added API request outcome logging to Android Logcat without recording passwords, tokens or account identifiers.
- Added Gradle release signing configuration that reads signing values from environment variables; no keystore or passwords were created or committed.
- Limited cleartext HTTP permission to the debug manifest; the main/release manifest now uses Android's secure default.
- Rewrote the in-app Help text to match the Firebase/API-backed flow and manager-provisioned staff model.
- Expanded `.gitignore` for the Firebase service-account filename pattern used by this project.

### Capabilities in the current application source

- Kotlin/Jetpack Compose Android interface with dashboard, sale, product, stock, transaction, reports, More/settings, help and manager staff screens.
- Username/password login through the ASP.NET API, PBKDF2-SHA256 password verification and Firebase custom-token exchange.
- Google sign-in for Google identities associated with active staff profiles.
- Optional device biometric prompt for unlocking an already authenticated user session.
- Manager-only staff administration, product creation, stock adjustments and report API access.
- Firestore storage for staff profiles, products, sales, sale items and stock movement records.
- Guarded simulated-data seeder designed to write ten sample documents for each required dataset; live counts must still be checked in Firebase.

### Improvements and known boundaries

- Staff account creation is manager-controlled; public self-registration is disabled and there is no create-account button on the current login page.
- The Android cart currently accepts whole-kilogram increments, although the Firestore/API model supports decimal kilogram quantities.
- The Android report page summarizes the data loaded by the app; manager-only report endpoints also exist in the API.
- The repository contains no website source files, screenshots, video link, exported Firestore rules or API/UI automated test project.
- The API targets .NET 8, whose support ended on 10 November 2026; upgrade before real deployment.
- Local physical-phone demonstrations use same-Wi-Fi HTTP and require the API to be running on a reachable computer.

### Planned, not implemented

- Add or link the separate website source and document its actual features.
- Add UI and API automated tests and test live role restrictions against Firebase.
- Support fractional kilogram quantities throughout the Android checkout flow.
- Host the API over HTTPS and upgrade it to a supported .NET runtime.
- Add verified screenshots and the final narrated demonstration video.
- Run the new GitHub Actions workflow and record the actual result in the Actions tab.
