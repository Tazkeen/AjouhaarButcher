package za.co.ajouhaar.butchery.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class UserAccount(val name: String, val username: String, val role: String)

/** Local persistent store for the standalone demo. Replace this implementation with the agreed API repository when available. */
class SQLiteButcheryRepository(context: Context) : ButcheryRepository {
    override val demoMode = true
    private val helper = Store(context.applicationContext)
    private var signedInAccount: UserAccount? = null

    override fun products(): List<Product> = helper.readableDatabase.rawQuery(
        "SELECT id,name,category,price_cents,stock FROM products WHERE active=1 ORDER BY id", null
    ).use { c -> buildList { while (c.moveToNext()) add(Product(c.getInt(0), c.getString(1), c.getString(2), c.getLong(3), c.getDouble(4))) } }

    override fun transactions(): List<Transaction> {
        val db = helper.readableDatabase
        val result = mutableListOf<Transaction>()
        db.rawQuery("SELECT id,receipt_number,created_at,cashier,payment_method,paid_cents FROM sales ORDER BY created_at DESC", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val lines = db.rawQuery("SELECT product_name,quantity,unit_price_cents FROM sale_lines WHERE sale_id=? ORDER BY id", arrayOf(id.toString())).use { lineCursor ->
                    buildList { while (lineCursor.moveToNext()) add(SaleLine(lineCursor.getString(0), lineCursor.getDouble(1), lineCursor.getLong(2))) }
                }
                result.add(Transaction(c.getString(1), LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(c.getLong(2)), ZoneId.systemDefault()), c.getString(3), c.getString(4), c.getLong(5), lines))
            }
        }
        return result
    }

    override fun completeSale(lines: List<CartLine>, paidCents: Long, method: String, cashier: String): Result<Transaction> = runCatching {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val current = products()
            validateSale(lines, current, paidCents)?.let { throw IllegalArgumentException(it) }
            val saleLines = lines.map { line -> val p = current.first { it.id == line.productId }; SaleLine(p.name, line.quantity.toDouble(), p.priceCents) }
            lines.forEach { line ->
                val changed = db.compileStatement("UPDATE products SET stock=stock-? WHERE id=? AND active=1 AND stock>=?").run { bindLong(1, line.quantity.toLong()); bindLong(2, line.productId.toLong()); bindLong(3, line.quantity.toLong()); executeUpdateDelete() }
                if (changed != 1) throw IllegalArgumentException("Stock changed while completing the sale. Please try again.")
            }
            val receipt = "AJ-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(4).uppercase()}"
            val timestamp = System.currentTimeMillis()
            val saleId = db.insertOrThrow("sales", null, ContentValues().apply { put("receipt_number", receipt); put("created_at", timestamp); put("cashier", cashier); put("payment_method", method); put("paid_cents", paidCents) })
            saleLines.forEachIndexed { index, line -> db.insertOrThrow("sale_lines", null, ContentValues().apply { put("sale_id", saleId); put("product_id", lines[index].productId); put("product_name", line.productName); put("quantity", line.quantity); put("unit_price_cents", line.unitPriceCents) }) }
            db.setTransactionSuccessful()
            Transaction(receipt, LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault()), cashier, method, paidCents, saleLines)
        } finally { db.endTransaction() }
    }

    override fun addProduct(name: String, category: String, priceCents: Long, stock: Double): Result<Unit> = runCatching {
        require(name.isNotBlank() && category.isNotBlank() && priceCents > 0 && stock >= 0) { "Enter a product name, category, positive price, and valid stock." }
        helper.writableDatabase.insertOrThrow("products", null, ContentValues().apply { put("name", name.trim()); put("category", category.trim()); put("price_cents", priceCents); put("stock", stock); put("active", 1) })
        Unit
    }

    override fun adjustStock(productId: Int, delta: Int): Result<Unit> = runCatching {
        val db = helper.writableDatabase
        val changed = db.compileStatement("UPDATE products SET stock=stock+? WHERE id=? AND active=1 AND stock+?>=0").run { bindLong(1, delta.toLong()); bindLong(2, productId.toLong()); bindLong(3, delta.toLong()); executeUpdateDelete() }
        if (changed != 1) throw IllegalArgumentException("Product not found or stock cannot be negative.")
    }

    override fun authenticate(username: String, password: String): UserAccount? {
        val db = helper.readableDatabase
        val account = db.rawQuery("SELECT full_name,username,role,password_hash FROM users WHERE username=? COLLATE NOCASE AND active=1", arrayOf(username.trim())).use { c ->
            if (!c.moveToFirst() || !verify(password, c.getBlob(3))) null
            else UserAccount(c.getString(0), c.getString(1), c.getString(2))
        }
        signedInAccount = account
        return account
    }

    override fun restoreSession(): Result<UserAccount> = signedInAccount?.let { Result.success(it) }
        ?: Result.failure(IllegalStateException("Sign in with your username and password first."))

    override fun register(name: String, email: String, password: String): Result<UserAccount> = Result.failure(
        IllegalStateException("Staff accounts are created by the manager from More → Staff.")
    )

    override fun authenticateGoogle(idToken: String): Result<UserAccount> =
        Result.failure(IllegalStateException("Google sign-in requires Firebase setup."))

    override fun staffUsers(): List<StaffMember> = helper.readableDatabase.rawQuery(
        "SELECT username,full_name,role,active FROM users ORDER BY full_name", null
    ).use { c -> buildList { while (c.moveToNext()) add(StaffMember(c.getString(0), c.getString(0), c.getString(1), c.getString(2), "", c.getInt(3) == 1)) } }

    override fun addStaff(username: String, fullName: String, password: String, role: String, googleEmail: String): Result<Unit> = runCatching {
        require(username.trim().matches(Regex("^[a-zA-Z0-9._-]{3,32}$"))) { "Enter a valid username." }
        require(fullName.trim().length >= 2 && password.length >= 4) { "Enter a full name and password with at least 4 characters." }
        require(role == "Cashier" || role == "Manager") { "Choose a valid staff role." }
        helper.writableDatabase.insertOrThrow("users", null, ContentValues().apply { put("full_name", fullName.trim()); put("username", username.trim().lowercase()); put("role", role); put("password_hash", hash(password)); put("active", 1); put("created_at", System.currentTimeMillis()) })
        Unit
    }.recoverCatching { error -> if (error.message?.contains("unique", true) == true) throw IllegalArgumentException("That username is already in use.") else throw error }

    override fun removeStaff(uid: String): Result<Unit> = runCatching {
        val isManager = helper.readableDatabase.rawQuery("SELECT role FROM users WHERE username=?", arrayOf(uid)).use { c -> c.moveToFirst() && c.getString(0) == "Manager" }
        require(!isManager) { "The manager account cannot be removed." }
        val removed = helper.writableDatabase.delete("users", "username=?", arrayOf(uid))
        require(removed == 1) { "Staff account not found." }
    }

    override fun signOut() { signedInAccount = null }

    private fun hash(password: String): ByteArray {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        return salt + derive(password, salt)
    }

    private fun verify(password: String, stored: ByteArray): Boolean {
        if (stored.size != 48) return false
        val salt = stored.copyOfRange(0, 16)
        return MessageDigest.isEqual(stored.copyOfRange(16, stored.size), derive(password, salt))
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, 310_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }

    private class Store(context: Context) : SQLiteOpenHelper(context, "ajouhaar_butchery.db", null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,category TEXT NOT NULL,price_cents INTEGER NOT NULL CHECK(price_cents>0),stock INTEGER NOT NULL CHECK(stock>=0),active INTEGER NOT NULL DEFAULT 1)")
            db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,full_name TEXT NOT NULL,username TEXT NOT NULL COLLATE NOCASE UNIQUE,role TEXT NOT NULL CHECK(role IN ('Manager','Cashier')),password_hash BLOB NOT NULL,active INTEGER NOT NULL DEFAULT 1,created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sales(id INTEGER PRIMARY KEY AUTOINCREMENT,receipt_number TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,cashier TEXT NOT NULL,payment_method TEXT NOT NULL,paid_cents INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE sale_lines(id INTEGER PRIMARY KEY AUTOINCREMENT,sale_id INTEGER NOT NULL REFERENCES sales(id) ON DELETE CASCADE,product_id INTEGER REFERENCES products(id),product_name TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity>0),unit_price_cents INTEGER NOT NULL CHECK(unit_price_cents>0))")
            val products = listOf(Triple("Beef Stew", "Beef", 8999L) to 24, Triple("Lamb Chops", "Lamb", 15999L) to 12, Triple("Boerewors", "Sausage", 10999L) to 18, Triple("Chicken Portions", "Poultry", 6999L) to 30, Triple("Mince", "Beef", 9499L) to 16, Triple("Rump Steak", "Beef", 18999L) to 8)
            products.forEach { (p, stock) -> db.insertOrThrow("products", null, ContentValues().apply { put("name", p.first); put("category", p.second); put("price_cents", p.third); put("stock", stock) }) }
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.delete("users", null, null)
        }
    }
}
