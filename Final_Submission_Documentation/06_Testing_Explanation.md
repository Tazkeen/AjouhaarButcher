# Testing Explanation

## Automated tests present

`app/src/test/.../BusinessRulesTest.kt` contains JUnit tests for money parsing and formatting, registration field validation, sale validation, sale totals/change, stock deduction, insufficient stock, product validation, stock adjustment constraints and manager-provisioned demo staff authentication.

Run Android checks from the repository root:

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug
```

The GitHub Actions workflow runs both Android tasks and compiles the API. The repository has no API test project or Compose UI automation tests at present.

## Manual verification still required

Record actual pass/fail outcomes and dates for:

- Manager and cashier login, including an incorrect password.
- Google SSO using a Google identity linked to an active staff profile.
- Manager-only staff/product/stock/report access and cashier denial.
- Sale completion, receipt values, stock deduction, insufficient payment and insufficient stock.
- Phone-to-API connectivity and API-to-Firestore health.
- Android navigation and invalid form input on the target phone.
- At least ten simulated documents in each required Firestore dataset.
- Website workflows and screenshots, after adding the website source/evidence.

Fill in: **Test device:** [model/Android version] · **Date:** [actual date] · **Tester:** [name] · **Result:** [pass/fail] · **Evidence:** [real screenshot/video link]. Do not fill these fields with assumed results.
