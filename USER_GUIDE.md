# Ajouhaar Mobile App User Guide

## Before signing in

For the connected demonstration, the API must be running and the phone and computer must be on the same Wi-Fi. The app uses staff profiles prepared by the manager. It does not offer public self-registration.

## Register a staff member

1. Sign in using a manager account.
2. Open **More** and choose **Staff → Manage**.
3. Tap **Add**.
4. Enter the staff member's full name, username and password. Optionally add the Google email they will use for SSO.
5. Choose **Cashier** or **Manager**, then tap **Add**.

The API stores a salted password hash; it does not store the entered password as readable text. Give credentials to staff privately. Do not put passwords in screenshots, reports or source code. There is no self-service create-account page.

## Log in

1. Open Ajouhaar Butchery.
2. Enter the username and password issued by the manager.
3. Tap **Log in**.

For Google SSO, tap **Continue with Google** and select the Google identity already linked to an active staff profile. A Google account that is not linked by the manager cannot access the shop data. If biometric sign-in has been enabled on this phone after a successful login, use **Continue with biometrics**.

## Use the dashboard

The dashboard greets the signed-in user and summarizes sales, products, low stock and recent transactions. Tap **New sale** to start checkout or **View products** to search the catalogue. Use the bottom navigation to move between Home, Sale, Products, Stock and More.

## View and manage products

1. Open **Products**.
2. Enter a product name or category in **Search products**.
3. Staff can view the catalogue and its price and stock.
4. A manager can tap **Add** and enter a product name, category, price per kilogram and opening stock.

The current app lets a cashier view products but only a manager can add them.

## Record a sale

1. Open **Sale**.
2. Search for a product and tap it to add it to the basket.
3. Use the basket controls to change the quantity. The current app uses whole-kilogram increments.
4. Select **Cash**, **Card** or **EFT**.
5. Enter the amount received. Check the total and change due.
6. Tap **Complete sale** and review the receipt.

The API validates available stock and calculates the saved sale. A completed sale deducts stock and appears in the transaction history. An insufficient-payment or insufficient-stock message means the sale was not accepted; correct the values and try again.

## Manage stock

Open **Stock** to see stock levels and low-stock indicators. The manager can use the plus and minus controls to adjust quantities. Stock cannot be reduced below zero. Sales also create stock deductions in the backend.

## View transactions and reports

Open **More → Transactions** to view recorded sales. Open **More → Reports** to see the daily totals and counts available in the current app. The backend also exposes manager-only reporting routes. Report values should be checked against Firebase/API data when preparing the demonstration.

## Settings, security and help

Open **More** to review the signed-in profile, enable/disable biometric unlock, lock the app or log out. Biometric unlock is an optional device convenience for a session that has already signed in; the server still checks Firebase identity and staff role.

Open **More → Help** for in-app guidance on sales, products, stock and connection problems.

## Troubleshooting

| Problem | What to check |
|---|---|
| Server connection timeout | Start the API, keep it running, confirm phone and computer use the same Wi-Fi, and confirm the app's API address matches the computer's current LAN address. |
| Firestore connection failure | Check the API health endpoint and its `GOOGLE_APPLICATION_CREDENTIALS` path. Keep the service-account file outside the repository. |
| Username or password rejected | Check spelling and case of the username, ask the manager to confirm the staff account is active, and request a reset/provisioning through the manager. |
| Google sign-in is rejected | Ask the manager to link the Google email to the active staff profile and verify the Firebase Google provider and Android OAuth setup. |
| Manager-only action denied | Confirm that the signed-in staff profile has the Manager role; do not rely only on whether a button is visible. |
| Build fails in Android Studio | Confirm JDK 17+, Android SDK Platform 36 and Gradle wrapper availability, then read the first Gradle error above the failure summary. |
| Biometric option unavailable | Sign in with username/password first, enable biometrics in More and ensure the phone has biometrics configured. Use the password login if biometrics are cancelled or unavailable. |

For a public or off-network deployment, the API must use a reachable HTTPS host. The local demonstration HTTP configuration is for the same private Wi-Fi only.
