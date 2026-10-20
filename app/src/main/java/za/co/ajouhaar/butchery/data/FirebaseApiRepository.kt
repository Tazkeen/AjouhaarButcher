package za.co.ajouhaar.butchery.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToLong

/** Firebase Authentication client plus the group's ASP.NET/Firestore API. Call from a background dispatcher. */
class FirebaseApiRepository(private val baseUrl: String) : ButcheryRepository {
    override val demoMode = false
    private val auth = FirebaseAuth.getInstance()
    private val remoteIds = mutableMapOf<Int, String>()

    override fun authenticate(username: String, password: String): UserAccount? {
        val response = api("POST", "/api/auth/login", JSONObject().put("username", username.trim()).put("password", password), authenticated = false)
        val customToken = response.optString("customToken")
        if (customToken.isBlank()) throw IllegalStateException("The server did not return an authentication token.")
        val user = Tasks.await(auth.signInWithCustomToken(customToken)).user ?: return null
        return UserAccount(response.optString("fullName", user.displayName ?: username), response.optString("username", username), response.optString("role", "Cashier"))
    }

    override fun restoreSession(): Result<UserAccount> = runCatching {
        val user = auth.currentUser ?: throw IllegalStateException("Sign in with your username and password first.")
        loadProfile(user.displayName)
            ?: throw IllegalStateException("No active staff profile is linked to this account.")
    }

    override fun register(name: String, email: String, password: String): Result<UserAccount> = Result.failure(
        IllegalStateException("Staff accounts are created by the manager from More → Staff.")
    )

    override fun authenticateGoogle(idToken: String): Result<UserAccount> = runCatching {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val user = Tasks.await(auth.signInWithCredential(credential)).user
            ?: throw IllegalStateException("Google sign-in did not return an account.")
        loadProfile(user.displayName)
    }

    private fun loadProfile(displayName: String?): UserAccount {
        val profile = api("GET", "/api/staff/me")
        return UserAccount(profile.optString("fullName", displayName ?: "Staff"), profile.optString("username", profile.optString("email")), profile.optString("role", "Cashier"))
    }

    override fun staffUsers(): List<StaffMember> = buildList {
        val array = apiArray("GET", "/api/staff")
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            add(StaffMember(item.optString("uid"), item.optString("username"), item.optString("fullName"), item.optString("role"), item.optString("googleEmail"), item.optBoolean("isActive")))
        }
    }

    override fun addStaff(username: String, fullName: String, password: String, role: String, googleEmail: String): Result<Unit> = runCatching {
        api("POST", "/api/staff", JSONObject().put("username", username.trim()).put("fullName", fullName.trim()).put("password", password).put("role", role).put("googleEmail", googleEmail.trim()))
        Unit
    }

    override fun removeStaff(uid: String): Result<Unit> = runCatching { api("DELETE", "/api/staff/${encodePathSegment(uid)}"); Unit }

    override fun signOut() { auth.signOut() }

    override fun products(): List<Product> {
        val array = apiArray("GET", "/api/products")
        remoteIds.clear()
        return buildList {
            for (i in 0 until array.length()) {
                val p = array.getJSONObject(i)
                val remoteId = p.getString("id")
                val id = remoteId.hashCode()
                val collision = remoteIds.put(id, remoteId)
                if (collision != null && collision != remoteId) throw IllegalStateException("Product id collision; refresh the product catalogue.")
                add(Product(id, p.optString("name"), p.optString("category"), (p.optDouble("pricePerKg") * 100).roundToLong(), p.optDouble("stockKg"), remoteId))
            }
        }
    }

    override fun transactions(): List<Transaction> {
        val response = api("GET", "/api/sales?page=1&pageSize=100")
        val items = response.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until items.length()) {
                val sale = items.getJSONObject(i)
                val lines = sale.optJSONArray("items") ?: JSONArray()
                val saleLines = buildList {
                    for (j in 0 until lines.length()) {
                        val line = lines.getJSONObject(j)
                        add(SaleLine(line.optString("productName"), line.optDouble("quantityKg"), (line.optDouble("unitPricePerKg") * 100).roundToLong()))
                    }
                }
                val total = (sale.optDouble("totalAmount") * 100).roundToLong()
                val paid = (sale.optDouble("amountPaid", sale.optDouble("totalAmount")) * 100).roundToLong()
                add(Transaction(sale.optString("receiptNumber", sale.optString("id")), parseDate(sale.optString("saleDateUtc")), sale.optString("cashierName"), sale.optString("paymentMethod"), paid, saleLines))
            }
        }
    }

    override fun completeSale(lines: List<CartLine>, paidCents: Long, method: String, cashier: String): Result<Transaction> = runCatching {
        val products = products().associateBy { it.id }
        validateSale(lines, products.values.toList(), paidCents)?.let { throw IllegalArgumentException(it) }
        val payload = JSONObject().put("customerName", "Walk-in customer").put("customerPhone", "").put("paymentMethod", method).put("amountPaid", paidCents / 100.0)
        val items = JSONArray()
        lines.forEach { line ->
            val product = products[line.productId] ?: throw IllegalArgumentException("A selected product is unavailable.")
            items.put(JSONObject().put("productId", product.remoteId).put("quantityKg", line.quantity.toDouble()))
        }
        payload.put("items", items)
        val sale = api("POST", "/api/sales", payload, mapOf("Idempotency-Key" to UUID.randomUUID().toString()))
        val resultItems = sale.optJSONArray("items") ?: JSONArray()
        val receiptLines = buildList {
            for (i in 0 until resultItems.length()) {
                val line = resultItems.getJSONObject(i)
                add(SaleLine(line.optString("productName"), line.optDouble("quantityKg"), (line.optDouble("unitPricePerKg") * 100).roundToLong()))
            }
        }
        Transaction(sale.optString("receiptNumber", sale.optString("id")), parseDate(sale.optString("saleDateUtc")), sale.optString("cashierName", cashier), method, (sale.optDouble("amountPaid", sale.optDouble("totalAmount")) * 100).roundToLong(), receiptLines)
    }

    override fun addProduct(name: String, category: String, priceCents: Long, stock: Double): Result<Unit> = runCatching {
        require(name.isNotBlank() && category.isNotBlank() && priceCents > 0 && stock >= 0) { "Enter a product name, category, positive price, and valid stock." }
        val body = JSONObject().put("name", name.trim()).put("category", category.trim()).put("pricePerKg", priceCents / 100.0)
            .put("costPerKg", 0.0).put("stockKg", stock).put("reorderLevelKg", minOf(stock, 5.0))
        api("POST", "/api/products", body)
        Unit
    }

    override fun adjustStock(productId: Int, delta: Int): Result<Unit> = runCatching {
        val remoteId = remoteIds[productId] ?: products().find { it.id == productId }?.remoteId ?: throw IllegalArgumentException("Product not found.")
        val body = JSONObject().put("productId", remoteId).put("quantityChangeKg", delta.toDouble()).put("reason", "Correction").put("notes", "Adjusted in mobile app")
        api("POST", "/api/stock-movements", body)
        Unit
    }

    private fun api(method: String, path: String, body: JSONObject? = null, extraHeaders: Map<String, String> = emptyMap(), authenticated: Boolean = true): JSONObject =
        apiText(method, path, body, extraHeaders, authenticated).let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private fun apiArray(method: String, path: String): JSONArray = JSONArray(apiText(method, path))

    private fun apiText(method: String, path: String, body: JSONObject? = null, extraHeaders: Map<String, String> = emptyMap(), authenticated: Boolean = true): String {
        val token = if (authenticated) auth.currentUser?.let { Tasks.await(it.getIdToken(false)).token }
            ?: throw IllegalStateException("Sign in to Firebase first.") else null
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            extraHeaders.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val code: Int
        val text: String
        try {
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        } catch (error: IOException) {
            throw IllegalStateException(
                "Can't reach the butchery server. Make sure the API is running on this computer and both devices are on the same Wi-Fi.",
                error
            )
        } finally {
            connection.disconnect()
        }
        if (code !in 200..299) {
            val response = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            throw ApiException(code, response.optString("message", response.optString("title", "API request failed ($code).")))
        }
        return text
    }

    private fun parseDate(value: String): LocalDateTime = runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDateTime() }.getOrElse { LocalDateTime.now() }
    private fun encodePathSegment(value: String) = java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    private class ApiException(val statusCode: Int, message: String) : IllegalStateException(message)
}
