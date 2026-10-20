# Ajouhaar Butchery API

This ASP.NET Core API uses Firebase Authentication for identity and Cloud Firestore for application data. It runs as a separate ASP.NET service; Firebase stores the data and verifies Firebase ID tokens. No Cloud Functions are used.

## Authentication and local phone demo

- Staff sign in with a username and password. `POST /api/auth/login` verifies the password against a salted PBKDF2-SHA256 hash in `staffUsers`, then returns a Firebase custom token. The Android client exchanges it for a Firebase ID token before calling staff and business endpoints.
- Google sign-in remains enabled for SSO. A Google identity must already be linked to an active staff profile by UID or `googleEmail`; Google registration alone does not create a staff account.
- The manager is the only role allowed to list, add, edit, or remove staff. Cashiers receive `403 Forbidden` from staff-management routes.
- Firestore client rules deny direct client reads and writes. The API's Firebase Admin SDK uses server credentials and bypasses those rules.
- Install the .NET 8 SDK. Keep the Firebase service-account JSON outside the repository and set `GOOGLE_APPLICATION_CREDENTIALS` to its path. Never put service-account credentials in Git or the Android app.
- Set `GOOGLE_APPLICATION_CREDENTIALS` to the Firebase service-account JSON path outside the repository. Start the local API with `work/run-api-local.cmd` from the project root. It listens on port `5159` for the physical-phone demo. The phone and development computer must be on the same Wi-Fi; Windows Firewall must allow the private-network connection. Keep this terminal open while using the app.
- The Android debug build currently points to `http://192.168.3.47:5159`. For a different computer or network, rebuild with `-PapiBaseUrl=http://<computer-LAN-IP>:5159`. Android emulator builds can use `-PapiBaseUrl=http://10.0.2.2:5159`.
- `GET /api/connection/firestore` checks API-to-Firestore connectivity. `/swagger` is available only in Development. Use a Firebase **ID token** as the Swagger bearer token; do not use a custom token or Google Cloud access token.

The API itself is not deployed by publishing Firestore. For a phone demo on the same Wi-Fi, the phone must be able to reach the machine hosting this API; for an off-campus demo the API needs a reachable HTTPS host. Hosting the API is a separate deployment decision from Firebase Firestore.

## Firestore document layout

- `staffUsers/{firebaseAuthUid}`: username, fullName, optional email/googleEmail, role, isActive, authUid, passwordSalt, passwordHash, passwordIterations, createdAtUtc. Passwords are salted PBKDF2-SHA256 hashes; plaintext passwords are never stored.
- `products/{productId}`: name, category, unit (`kg`), pricePerKg, costPerKg, stockKg, reorderLevelKg, isActive, createdAtUtc, updatedAtUtc.
- `sales/{saleId}`: receiptNumber, cashierUid, cashierName, customerName, customerPhone, paymentMethod, totalAmount, status, saleDateUtc, createdAtUtc, idempotencyHash.
- `sales/{saleId}/items/{productId}`: productId, productName, quantityKg, unitPricePerKg, lineTotal.
- `stockMovements/{movementId}`: productId, productName, quantityChangeKg, reason, staffUid, saleId, notes, createdAtUtc.

Numeric values should be Firestore numbers. The reader also accepts invariant-culture numeric strings to make older seed data readable. For new data, store numbers as numbers and timestamps as Firestore timestamps.

## API routes

All routes below except the connectivity check require `Authorization: Bearer <Firebase ID token>`. Product and sale reads are available to staff. Product writes, reports, stock adjustments, and staff administration require a Manager.

| Method and route | Access | Purpose |
|---|---|---|
| `GET /api/connection/firestore` | Public diagnostic | Check API-to-Firestore connectivity |
| `POST /api/auth/login` | Public, rate-limited | Verify a staff username/password and issue a Firebase custom token |
| `GET /api/products` | Staff | List active products; Manager may pass `includeInactive=true` |
| `GET /api/products/{id}` | Staff | Read one product |
| `GET /api/products/low-stock` | Staff | List active products at/below reorder level |
| `POST /api/products` | Manager | Create a product |
| `PUT /api/products/{id}` | Manager | Update product details (stock is managed through movements) |
| `PATCH /api/products/{id}/status` | Manager | Activate/deactivate product |
| `POST /api/stock-movements` | Manager | Adjust stock and create an audit movement atomically |
| `GET /api/stock-movements?productId={id}&limit=100` | Manager | List latest movements |
| `POST /api/sales` | Staff | Create a sale; calculates prices and deducts stock atomically |
| `GET /api/sales` | Staff | Search/paginate sales; Cashier sees only their sales |
| `GET /api/sales/{id}` | Staff | Read sale; Cashier can only read their own |
| `GET /api/sales/{id}/receipt` | Staff | Read receipt subject to the same access rule |
| `GET /api/reports/daily-sales?date=2026-10-19` | Manager | Daily sales in South African local time |
| `GET /api/reports/stock-levels` | Manager | Stock and reorder status |
| `GET /api/staff` | Manager | List staff profiles without password hashes |
| `POST /api/staff` | Manager | Create a staff account with a salted password hash and optional Google SSO link |
| `PUT /api/staff/{uid}` | Manager | Update staff profile and active state |
| `DELETE /api/staff/{uid}` | Manager | Remove a staff profile and its Firebase Auth account |
| `GET /api/staff/me` | Active staff | Read the signed-in staff profile |

Example sale body:

```json
{
  "customerName": "Walk-in customer",
  "customerPhone": "",
  "paymentMethod": "Cash",
  "items": [
    { "productId": "FIRESTORE_PRODUCT_ID", "quantityKg": 1.25 }
  ]
}
```

Send an `Idempotency-Key` header for sale retries. Repeating the same key and body returns the original sale; using the same key for a different body returns `409 Conflict`.

## Smoke-check sequence

After Firebase Authentication and the Manager profile are prepared:

1. `GET /api/connection/firestore` should return `connected: true`.
2. Authorize Swagger with the Manager's Firebase ID token.
3. `POST /api/products` with a name, category, positive price and starting stock.
4. `GET /api/products` should include the product.
5. `POST /api/sales` for less than available stock; inspect the response, then `GET /api/products/{id}` to confirm the stock decreased.
6. Repeat the sale request with the same `Idempotency-Key`; it must not deduct stock twice.
7. `GET /api/stock-movements?productId={id}` should include the sale deduction.
8. Try a sale larger than available stock; it should be rejected and stock should stay unchanged.
9. `GET /api/reports/daily-sales?date=YYYY-MM-DD` should include the sale on its South African business date.
10. Create a second Auth user and a `Cashier` profile. Confirm that cashier cannot access Manager routes and can see only their own sales.

These are manual acceptance steps for live API behavior. The Firestore database already has ten simulated records in each required dataset, written by `../../seed-firestore/seed.mjs`: products, inactive staff profiles, sales, sale items and stock movements. All carry `isSample: true`; the sample staff profiles are inactive and have no Firebase Auth accounts.

To repeat the database seeding against this guarded project, use the Node utility from the repository root (Node 18+):

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS = 'C:\path\outside\the\repository\service-account.json'
node --use-system-ca backend/seed-firestore/seed.mjs ajouhaar-butchery-system --confirm-test-project
```

The script uses stable sample IDs and verifies the counts after writing. It does not create Firebase Authentication users or passwords. Never put the service-account JSON in the repository or Android app.

## Android integration handoff

The Android app is wired to Firebase Authentication, Google sign-in, and this API adapter. It uses Firestore string product IDs, decimal kilogram quantities, and sends the current Firebase ID token on API requests. The API still needs to be hosted at an HTTPS URL for use outside the development Wi-Fi. The current API base URL is emulator-oriented; for a physical phone, use a reachable HTTPS API URL or the development machine's LAN address while both devices are on the same Wi-Fi.
