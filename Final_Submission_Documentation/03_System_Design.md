# System Design Considerations

## Architecture

```text
Kotlin Android app
       │ Firebase ID token + JSON requests
       ▼
ASP.NET Core REST API ───── Firebase Authentication
       │ Firebase Admin / Firestore client
       ▼
Cloud Firestore collections
```

The Android screen layer uses Compose and an `AppViewModel`. Repository methods isolate data access. The API is the trusted application data path and applies role policies. Firebase Authentication provides identity; Firestore stores shared application records.

## Roles

| Role | Intended access |
|---|---|
| Cashier | View products, complete sales and access only permitted sales records |
| Manager | Cashier functions plus staff administration, product writes, stock adjustments and reports |

Role checks are enforced in the API in addition to role-based screen visibility. Managers create staff; there is no public registration form.

## Input and failure handling

The app validates sale amounts, basket contents and product values, then presents API failures through a readable error dialog. The API revalidates values and stock because client validation alone is not trusted. Sale requests include an idempotency key to avoid duplicate deductions if a request is retried.

## Security and deployment

Passwords are stored as salted PBKDF2-SHA256 hashes. Firebase service-account credentials are supplied to the API from an external file. The debug manifest allows same-Wi-Fi HTTP for local testing; the main manifest leaves Android cleartext disabled. A deployed API must be hosted over HTTPS and use a supported runtime. Firebase rules are not exported in the repository, so the group must verify the live rules in Console.

## Design evidence still needed

Add a genuine context diagram or database diagram if required by the report rubric, generated from the final design. Include the website as a separate component only after its source and actual integration are confirmed. Attach screenshots from the running phone and real website.
