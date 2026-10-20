package za.co.ajouhaar.butchery.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class Product(val id: Int, val name: String, val category: String, val priceCents: Long, val stock: Double, val remoteId: String = id.toString()) {
    val price: BigDecimal get() = BigDecimal.valueOf(priceCents, 2)
}
data class StaffMember(val uid: String, val username: String, val fullName: String, val role: String, val googleEmail: String, val isActive: Boolean)

data class CartLine(val productId: Int, val quantity: Int)
data class SaleLine(val productName: String, val quantity: Double, val unitPriceCents: Long) {
    val totalCents: Long get() = BigDecimal.valueOf(unitPriceCents).multiply(BigDecimal.valueOf(quantity)).setScale(0, RoundingMode.HALF_UP).longValueExact()
}
data class Transaction(
    val number: String,
    val createdAt: LocalDateTime,
    val cashier: String,
    val paymentMethod: String,
    val paidCents: Long,
    val lines: List<SaleLine>
) {
    val totalCents: Long get() = lines.sumOf { it.totalCents }
    val changeCents: Long get() = paidCents - totalCents
}

fun formatMoney(cents: Long): String = "R" + BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.HALF_UP).toPlainString()

fun parseMoneyCents(text: String): Long? = try {
    BigDecimal(text.trim()).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
} catch (_: Exception) { null }

fun validateCredentials(email: String, password: String): String? = when {
    !email.trim().matches(Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")) -> "Enter a valid email address."
    password.length < 8 -> "Password must be at least 8 characters."
    else -> null
}

fun validateRegistration(name: String, email: String, password: String, confirmation: String): String? = when {
    name.trim().length < 2 -> "Enter your full name."
    validateCredentials(email, password) != null -> validateCredentials(email, password)
    password != confirmation -> "Passwords do not match."
    else -> null
}

fun validateSale(lines: List<CartLine>, products: List<Product>, paidCents: Long): String? {
    if (lines.isEmpty()) return "Add at least one product to the sale."
    for (line in lines) {
        if (line.quantity <= 0) return "Quantities must be greater than zero."
        val product = products.find { it.id == line.productId } ?: return "A selected product is unavailable."
        if (line.quantity > product.stock) return "Not enough stock for ${product.name}."
    }
    val total = lines.sumOf { line -> (products.find { it.id == line.productId }!!.priceCents) * line.quantity }
    if (paidCents < total) return "Amount paid is less than the sale total."
    return null
}

interface ButcheryRepository {
    val demoMode: Boolean
    fun products(): List<Product>
    fun transactions(): List<Transaction>
    fun completeSale(lines: List<CartLine>, paidCents: Long, method: String, cashier: String): Result<Transaction>
    fun addProduct(name: String, category: String, priceCents: Long, stock: Double): Result<Unit>
    fun adjustStock(productId: Int, delta: Int): Result<Unit>
    fun authenticate(username: String, password: String): UserAccount?
    fun restoreSession(): Result<UserAccount>
    fun register(name: String, email: String, password: String): Result<UserAccount>
    fun authenticateGoogle(idToken: String): Result<UserAccount>
    fun staffUsers(): List<StaffMember>
    fun addStaff(username: String, fullName: String, password: String, role: String, googleEmail: String): Result<Unit>
    fun removeStaff(uid: String): Result<Unit>
    fun signOut()
}

class DemoButcheryRepository : ButcheryRepository {
    override val demoMode = true
    private val items = mutableListOf(
        Product(1, "Beef Stew", "Beef", 8999, 24.0), Product(2, "Lamb Chops", "Lamb", 15999, 12.0),
        Product(3, "Boerewors", "Sausage", 10999, 18.0), Product(4, "Chicken Portions", "Poultry", 6999, 30.0),
        Product(5, "Mince", "Beef", 9499, 16.0), Product(6, "Rump Steak", "Beef", 18999, 8.0)
    )
    private val sales = mutableListOf<Transaction>()
    private var signedInAccount: UserAccount? = null
    private var nextId = 1001
    private data class DemoStaff(val profile: StaffMember, val salt: ByteArray, val hash: ByteArray)
    private val users = mutableMapOf<String, DemoStaff>()
    override fun authenticate(username: String, password: String): UserAccount? {
        val staff = users[username.trim().lowercase()]
        val valid = staff != null && MessageDigest.isEqual(staff.hash, derive(password, staff.salt))
        return (staff?.profile?.takeIf { valid }?.let { UserAccount(it.fullName, it.username, it.role) }).also { signedInAccount = it }
    }
    override fun restoreSession(): Result<UserAccount> = signedInAccount?.let { Result.success(it) }
        ?: Result.failure(IllegalStateException("Sign in with your username and password first."))
    override fun register(name: String, email: String, password: String): Result<UserAccount> = Result.failure(IllegalStateException("Staff accounts are created by the manager from More → Staff."))
    override fun authenticateGoogle(idToken: String) = Result.failure<UserAccount>(IllegalStateException("Google sign-in requires Firebase setup."))
    override fun staffUsers() = users.values.map { it.profile }
    override fun addStaff(username: String, fullName: String, password: String, role: String, googleEmail: String): Result<Unit> = runCatching {
        val normalized = username.trim().lowercase()
        require(normalized.matches(Regex("^[a-z0-9._-]{3,32}$"))) { "Enter a valid username." }
        require(normalized !in users) { "That username is already in use." }
        require(fullName.trim().length >= 2 && password.length >= 4) { "Enter a full name and password with at least 4 characters." }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val profile = StaffMember("demo-${UUID.randomUUID()}", normalized, fullName.trim(), role, googleEmail.trim(), true)
        users[normalized] = DemoStaff(profile, salt, derive(password, salt))
    }
    override fun removeStaff(uid: String): Result<Unit> = runCatching {
        require(users.values.none { it.profile.uid == uid && it.profile.role == "Manager" }) { "The manager account cannot be removed." }
        val key = users.entries.firstOrNull { it.value.profile.uid == uid }?.key ?: throw IllegalArgumentException("Staff account not found.")
        users.remove(key)
    }
    override fun signOut() { signedInAccount = null }
    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    @Synchronized override fun products() = items.toList()
    @Synchronized override fun transactions() = sales.toList().reversed()
    @Synchronized override fun completeSale(lines: List<CartLine>, paidCents: Long, method: String, cashier: String): Result<Transaction> {
        validateSale(lines, items, paidCents)?.let { return Result.failure(IllegalArgumentException(it)) }
        val saleLines = lines.map { line ->
            val p = items.first { it.id == line.productId }
            SaleLine(p.name, line.quantity.toDouble(), p.priceCents)
        }
        lines.forEach { line -> val index = items.indexOfFirst { it.id == line.productId }; items[index] = items[index].copy(stock = items[index].stock - line.quantity) }
        val sale = Transaction("AJ-${nextId++}", LocalDateTime.now(), cashier, method, paidCents, saleLines)
        sales.add(sale)
        return Result.success(sale)
    }
    @Synchronized override fun addProduct(name: String, category: String, priceCents: Long, stock: Double): Result<Unit> {
        if (name.isBlank() || category.isBlank() || priceCents <= 0 || stock < 0) return Result.failure(IllegalArgumentException("Enter a product name, category, positive price, and valid stock."))
        items.add(Product((items.maxOfOrNull { it.id } ?: 0) + 1, name.trim(), category.trim(), priceCents, stock)); return Result.success(Unit)
    }
    @Synchronized override fun adjustStock(productId: Int, delta: Int): Result<Unit> {
        val index = items.indexOfFirst { it.id == productId }
        if (index < 0) return Result.failure(IllegalArgumentException("Product not found."))
        if (items[index].stock + delta < 0) return Result.failure(IllegalArgumentException("Stock cannot be negative."))
        items[index] = items[index].copy(stock = items[index].stock + delta); return Result.success(Unit)
    }
}
