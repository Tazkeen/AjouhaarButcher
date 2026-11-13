# Database Explanation

Cloud Firestore is a document database, not a relational DBMS. The application datasets are stored as collections and documents. Sale line items are stored as a subcollection below each sale. Firestore does not enforce SQL foreign keys; the API validates the referenced product and applies related sale/stock changes in a transaction.

| Dataset / path | Main fields and use |
|---|---|
| `staffUsers/{uid}` | Username, name, role, active state, optional Google SSO email, password salt/hash/iteration count and timestamp |
| `products/{id}` | Category, product name, unit, price/cost per kg, quantity, reorder level and active flag |
| `sales/{saleId}` | Receipt, cashier, customer/payment fields, total, status, sale/creation times and idempotency hash |
| `sales/{saleId}/items/{productId}` | Product identifier/name snapshot, quantity in kg, unit price and line total |
| `stockMovements/{movementId}` | Product, quantity change, reason, staff member, related sale, notes and timestamp |

## Sample data

The checked-in `backend/seed-firestore/seed.mjs` utility is designed to create ten simulated records in each assignment dataset. It confirms the project ID and requires `--confirm-test-project`, then reports record counts. It does not create active Firebase Authentication accounts. Do not claim the live database has ten records per collection until the script result and Firebase Console collection counts have been captured and checked.

## Security and evidence

The API uses a Firebase service account outside the repository. Passwords are hashed with PBKDF2-SHA256 and a random per-account salt; the staff API omits password hashes from list responses. The repository contains no exported `firestore.rules`, so the group must inspect and document the actual Firebase Console rules. Include sanitized screenshots of collection structure and counts, without exposing password hash fields, account credentials, tokens or service-account JSON.
