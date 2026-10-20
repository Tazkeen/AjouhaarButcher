package za.co.ajouhaar.butchery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import za.co.ajouhaar.butchery.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Screen { LOGIN, REGISTER, DASHBOARD, SALE, PRODUCTS, STOCK, TRANSACTIONS, REPORTS, SETTINGS, HELP, STAFF }
data class AppState(
    val screen: Screen = Screen.LOGIN,
    val name: String = "",
    val email: String = "",
    val role: String = "Cashier",
    val products: List<Product> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val staff: List<StaffMember> = emptyList(),
    val cart: List<CartLine> = emptyList(),
    val search: String = "",
    val paymentMethod: String = "Cash",
    val paidText: String = "",
    val error: String? = null,
    val receipt: Transaction? = null,
    val registering: Boolean = false
)

class AppViewModel(private val repository: ButcheryRepository = DemoButcheryRepository()) : ViewModel() {
    class Factory(private val repository: ButcheryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(AppViewModel::class.java)) throw IllegalArgumentException("Unknown ViewModel class")
            return AppViewModel(repository) as T
        }
    }
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    val demoMode get() = repository.demoMode
    init { if (demoMode) refreshData() }
    private fun update(block: (AppState) -> AppState) { _state.value = block(_state.value) }
    private fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.products() to repository.transactions() }
                .onSuccess { (products, transactions) -> update { it.copy(products = products, transactions = transactions) } }
                .onFailure { error -> update { state -> state.copy(error = state.error ?: error.message) } }
        }
    }
    fun navigate(screen: Screen) {
        update { it.copy(screen = screen, error = null, search = "") }
        if (screen == Screen.STAFF && _state.value.role == "Manager") loadStaff()
    }
    fun loadStaff() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.staffUsers() }
                .onSuccess { staff -> update { it.copy(staff = staff, error = null) } }
                .onFailure { error -> update { it.copy(error = error.message ?: "Could not load staff accounts.") } }
        }
    }
    fun addStaff(username: String, fullName: String, password: String, role: String, googleEmail: String) {
        if (_state.value.role != "Manager") { showAuthError("Only the manager can add staff accounts."); return }
        viewModelScope.launch(Dispatchers.IO) {
            repository.addStaff(username, fullName, password, role, googleEmail)
                .onSuccess { update { it.copy(error = "Staff account added.") }; loadStaff() }
                .onFailure { error -> update { it.copy(error = error.message ?: "Could not add the staff account.") } }
        }
    }
    fun removeStaff(uid: String) {
        if (_state.value.role != "Manager") { showAuthError("Only the manager can remove staff accounts."); return }
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeStaff(uid)
                .onSuccess { update { it.copy(error = "Staff account removed.") }; loadStaff() }
                .onFailure { error -> update { it.copy(error = error.message ?: "Could not remove the staff account.") } }
        }
    }
    fun login(username: String, password: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.authenticate(username.trim(), password) }
                .onSuccess { account -> if (account == null) update { it.copy(error = "Username or password is incorrect.") } else enter(account) }
                .onFailure { error -> update { it.copy(error = error.message ?: "Sign-in failed.") } }
        }
    }
    fun register(name: String, email: String, password: String, confirm: String) {
        val normalized = email.trim().lowercase()
        val problem = validateRegistration(name, email, password, confirm)
        if (problem != null) update { it.copy(error = problem) }
        else {
            viewModelScope.launch(Dispatchers.IO) {
                repository.register(name, normalized, password)
                    .onSuccess(::enter)
                    .onFailure { error -> update { it.copy(error = error.message ?: "Could not create the account.") } }
            }
        }
    }
    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.authenticateGoogle(idToken)
                .onSuccess(::enter)
                .onFailure { error -> update { it.copy(error = error.message ?: "Google sign-in failed.") } }
        }
    }
    fun restoreSession() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.restoreSession().onSuccess(::enter)
                .onFailure { error -> update { it.copy(error = error.message ?: "Biometric sign-in failed.") } }
        }
    }
    fun lockSession() = update { it.copy(screen = Screen.LOGIN, error = null) }
    fun showAuthError(message: String) = update { it.copy(error = message) }
    private fun enter(account: UserAccount) {
        val data = runCatching { repository.products() to repository.transactions() }
        data.onSuccess { (products, transactions) -> update { it.copy(name = account.name, email = account.username, role = account.role, products = products, transactions = transactions, screen = Screen.DASHBOARD, error = null) } }
            .onFailure { error -> update { it.copy(name = account.name, email = account.username, role = account.role, screen = Screen.DASHBOARD, error = "Signed in, but could not load the server data: ${error.message}") } }
    }
    fun logout() { repository.signOut(); update { AppState() }; if (demoMode) refreshData() }
    fun setSearch(value: String) = update { it.copy(search = value) }
    fun setPayment(method: String) = update { it.copy(paymentMethod = method) }
    fun setPaid(value: String) = update { it.copy(paidText = value.filter { c -> c.isDigit() || c == '.' }) }
    fun addToCart(productId: Int) = update { s ->
        val old = s.cart.find { it.productId == productId }
        val qty = (old?.quantity ?: 0) + 1
        if (qty > (s.products.find { it.id == productId }?.stock ?: 0.0)) s.copy(error = "No more stock is available.")
        else s.copy(cart = if (old == null) s.cart + CartLine(productId, 1) else s.cart.map { if (it.productId == productId) it.copy(quantity = qty) else it }, error = null)
    }
    fun changeQuantity(productId: Int, quantity: Int) = update { s ->
        val product = s.products.find { it.id == productId }
        when { quantity <= 0 -> s.copy(cart = s.cart.filterNot { it.productId == productId }); quantity > (product?.stock ?: 0.0) -> s.copy(error = "Quantity exceeds available stock."); else -> s.copy(cart = s.cart.map { if (it.productId == productId) it.copy(quantity = quantity) else it }, error = null) }
    }
    fun totalCents(): Long { val s = _state.value; return s.cart.sumOf { line -> (s.products.find { it.id == line.productId }?.priceCents ?: 0L) * line.quantity } }
    fun completeSale() {
        val s = _state.value
        val paid = parseMoneyCents(s.paidText)
        if (paid == null) { update { it.copy(error = "Enter a valid amount paid (for example 150.00).") }; return }
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.completeSale(s.cart, paid, s.paymentMethod, s.name)
            if (result.isSuccess) {
                val data = runCatching { repository.products() to repository.transactions() }.getOrNull()
                update { it.copy(products = data?.first ?: it.products, transactions = data?.second ?: it.transactions, cart = emptyList(), paidText = "", receipt = result.getOrNull(), screen = Screen.SALE, error = null) }
            } else update { it.copy(error = result.exceptionOrNull()?.message ?: "Sale could not be completed.") }
        }
    }
    fun dismissReceipt() = update { it.copy(receipt = null) }
    fun addProduct(name: String, category: String, price: String, stock: String) {
        val cents = parseMoneyCents(price); val qty = stock.toDoubleOrNull()
        if (cents == null || qty == null) { update { it.copy(error = "Enter a valid price and stock quantity in kilograms.") }; return }
        viewModelScope.launch(Dispatchers.IO) { val result = repository.addProduct(name, category, cents, qty); val products = runCatching { repository.products() }.getOrNull(); update { it.copy(products = products ?: it.products, error = result.exceptionOrNull()?.message) } }
    }
    fun adjustStock(id: Int, delta: Int) { viewModelScope.launch(Dispatchers.IO) { val result = repository.adjustStock(id, delta); val products = runCatching { repository.products() }.getOrNull(); update { it.copy(products = products ?: it.products, error = result.exceptionOrNull()?.message) } } }
    fun dismissError() = update { it.copy(error = null) }
    fun todaySalesCents(): Long = _state.value.transactions.filter { it.createdAt.toLocalDate() == LocalDate.now() }.sumOf { it.totalCents }
}
