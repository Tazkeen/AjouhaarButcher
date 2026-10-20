import { readFileSync } from "node:fs";
import { createSign } from "node:crypto";

const projectId = "ajouhaar-butchery-system";
if (process.argv[2] !== projectId || process.argv[3] !== "--confirm-test-project") {
  throw new Error(`Refusing to write. Run: npm run seed -- ${projectId} --confirm-test-project`);
}
const credentialPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
if (!credentialPath) throw new Error("Set GOOGLE_APPLICATION_CREDENTIALS to an external service-account JSON path.");
const serviceAccount = JSON.parse(readFileSync(credentialPath, "utf8"));
if (serviceAccount.project_id !== projectId) throw new Error("Service account project does not match the guarded test project.");

const b64url = (value) => Buffer.from(value).toString("base64url");
const now = Math.floor(Date.now() / 1000);
const unsigned = `${b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }))}.${b64url(JSON.stringify({
  iss: serviceAccount.client_email,
  scope: "https://www.googleapis.com/auth/datastore",
  aud: serviceAccount.token_uri,
  iat: now,
  exp: now + 3600
}))}`;
const signer = createSign("RSA-SHA256");
signer.update(unsigned);
const assertion = `${unsigned}.${signer.sign(serviceAccount.private_key, "base64url")}`;
const tokenResponse = await fetch(serviceAccount.token_uri, {
  method: "POST",
  headers: { "content-type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion })
});
if (!tokenResponse.ok) throw new Error(`Google OAuth failed (${tokenResponse.status}): ${await tokenResponse.text()}`);
const { access_token: accessToken } = await tokenResponse.json();

const value = (v) => {
  if (v instanceof Date) return { timestampValue: v.toISOString() };
  if (typeof v === "string") return { stringValue: v };
  if (typeof v === "boolean") return { booleanValue: v };
  if (typeof v === "number") return { doubleValue: v };
  throw new Error(`Unsupported Firestore value: ${v}`);
};
const write = (path, data) => ({ update: {
  name: `projects/${projectId}/databases/(default)/documents/${path}`,
  fields: Object.fromEntries(Object.entries(data).map(([key, v]) => [key, value(v)]))
} });
const products = [
  ["sample-beef-mince", "Beef Mince", "Beef", 120, 85, 40, 10],
  ["sample-beef-stew", "Beef Stew", "Beef", 110, 78, 35, 8],
  ["sample-beef-sausage", "Beef Sausage", "Beef", 135, 92, 28, 7],
  ["sample-chicken-breast", "Chicken Breast", "Chicken", 75, 52, 25, 8],
  ["sample-chicken-thighs", "Chicken Thighs", "Chicken", 68, 47, 30, 8],
  ["sample-whole-chicken", "Whole Chicken", "Chicken", 62, 43, 24, 6],
  ["sample-lamb-chops", "Lamb Chops", "Lamb", 155, 112, 20, 5],
  ["sample-lamb-stew", "Lamb Stew", "Lamb", 125, 90, 26, 6],
  ["sample-beef-ribs", "Beef Ribs", "Beef", 145, 101, 18, 5],
  ["sample-chicken-wings", "Chicken Wings", "Chicken", 82, 57, 22, 6]
];
const timestamp = new Date();
const writes = [];
products.forEach(([id, name, category, pricePerKg, costPerKg, stockKg, reorderLevelKg], index) => {
  const number = index + 1;
  const suffix = String(number).padStart(2, "0");
  const staffId = `sample-staff-${suffix}`;
  const saleId = `sample-sale-${String(number).padStart(3, "0")}`;
  const saleTime = new Date(Date.now() - index * 3600_000);
  const total = Math.round(pricePerKg * 0.25 * 100) / 100;
  writes.push(write(`products/${id}`, { name, category, unit: "kg", pricePerKg, costPerKg, stockKg, reorderLevelKg, isActive: true, isSample: true, createdAtUtc: timestamp, updatedAtUtc: timestamp }));
  writes.push(write(`staffUsers/${staffId}`, { fullName: `Sample Cashier ${number}`, email: `sample-cashier-${suffix}@example.invalid`, role: "Cashier", isActive: false, isSample: true, createdAtUtc: timestamp }));
  writes.push(write(`sales/${saleId}`, { receiptNumber: `SAMPLE-${String(number).padStart(3, "0")}`, idempotencyHash: `sample-${number}`, cashierUid: staffId, cashierName: `Sample Cashier ${number}`, customerName: `Sample Customer ${number}`, customerPhone: "", paymentMethod: index % 2 ? "Card" : "Cash", totalAmount: total, amountPaid: total + 2, changeAmount: 2, status: "Completed", saleDateUtc: saleTime, createdAtUtc: saleTime, isSample: true }));
  writes.push(write(`sales/${saleId}/items/${id}`, { productId: id, productName: name, quantityKg: 0.25, unitPricePerKg: pricePerKg, lineTotal: total }));
  writes.push(write(`stockMovements/sample-movement-${String(number).padStart(3, "0")}`, { productId: id, productName: name, quantityChangeKg: -0.25, reason: "Sale", staffUid: staffId, saleId, notes: `Sample receipt SAMPLE-${String(number).padStart(3, "0")}`, createdAtUtc: saleTime, isSample: true }));
});

// Firestore commit permits up to 500 writes per request; this batch is 50 writes.
const response = await fetch(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:commit`, {
  method: "POST",
  headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
  body: JSON.stringify({ writes })
});
if (!response.ok) throw new Error(`Firestore write failed (${response.status}): ${await response.text()}`);
const countCollection = async (collectionId) => {
  const result = await fetch(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents/${collectionId}?pageSize=100`, {
    headers: { authorization: `Bearer ${accessToken}` }
  });
  if (!result.ok) throw new Error(`Firestore verification failed for ${collectionId} (${result.status}): ${await result.text()}`);
  const data = await result.json();
  return (data.documents ?? []).filter((document) => document.fields?.isSample?.booleanValue === true).length;
};
const counts = {};
for (const collection of ["products", "staffUsers", "sales", "stockMovements"]) counts[collection] = await countCollection(collection);
const itemsResponse = await fetch(`https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:runQuery`, {
  method: "POST",
  headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
  body: JSON.stringify({ structuredQuery: { from: [{ collectionId: "items", allDescendants: true }], limit: 100 } })
});
if (!itemsResponse.ok) throw new Error(`Firestore verification failed for sale items (${itemsResponse.status}): ${await itemsResponse.text()}`);
const itemRows = await itemsResponse.json();
counts.saleItems = itemRows.filter((row) => row.document?.fields?.productId?.stringValue?.startsWith("sample-")).length;
if (Object.values(counts).some((count) => count < 10)) throw new Error(`Seed verification expected at least 10 simulated docs in each dataset: ${JSON.stringify(counts)}`);
console.log(`Seeded and verified at least 10 simulated records each: ${JSON.stringify(counts)}.`);
console.log("Sample staff rows are inactive and are not Firebase Authentication accounts.");
