package za.co.ajouhaar.butchery

import android.os.Bundle
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import za.co.ajouhaar.butchery.data.*
import java.time.format.DateTimeFormatter
import java.math.BigDecimal

private fun formatKg(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

class MainActivity : ComponentActivity() {
    private val appViewModel by viewModels<AppViewModel> { AppViewModel.Factory(FirebaseApiRepository(BuildConfig.API_BASE_URL)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val biometricPrefs = getSharedPreferences("device_auth", MODE_PRIVATE)
        setContent {
            var biometricUid by remember { mutableStateOf(biometricPrefs.getString("uid", null)) }
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            val biometricEnabled = currentUid != null && currentUid == biometricUid
            AjouhaarApp(
                appViewModel,
                ::startGoogleSignIn,
                biometricEnabled,
                onBiometricSignIn = {
                    if (currentUid == null) appViewModel.showAuthError("Sign in with your username and password once, then enable biometrics in More.")
                    else showBiometricPrompt { appViewModel.restoreSession() }
                },
                onEnableBiometric = {
                    if (currentUid == null) appViewModel.showAuthError("Sign in before enabling biometric authorization.")
                    else showBiometricPrompt {
                        biometricPrefs.edit().putString("uid", currentUid).apply()
                        biometricUid = currentUid
                        appViewModel.showAuthError("Biometric sign-in is enabled on this device.")
                    }
                },
                onDisableBiometric = {
                    biometricPrefs.edit().remove("uid").apply()
                    biometricUid = null
                }
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            appViewModel.showAuthError("Biometric sign-in requires Android 9 or later. Use your password to sign in.")
            return
        }
        try {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle("Ajouhaar Butchery")
                .setSubtitle("Verify your identity to continue")
                .setNegativeButton("Cancel", mainExecutor) { _, _ -> }
                .build()
            prompt.authenticate(CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                        errorCode != android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        appViewModel.showAuthError(errString.toString())
                    }
                }
            })
        } catch (error: Exception) {
            appViewModel.showAuthError(error.localizedMessage ?: "Biometric authorization is unavailable on this device.")
        }
    }

    private fun startGoogleSignIn() {
        lifecycleScope.launch {
            val resourceId = resources.getIdentifier("default_web_client_id", "string", packageName)
            val serverClientId = if (resourceId == 0) "" else getString(resourceId)
            if (serverClientId.isBlank()) {
                appViewModel.showAuthError("Google SSO is not configured for this Firebase Android app. Check the OAuth client and google-services.json.")
                return@launch
            }
            try {
                val option = GetGoogleIdOption.Builder().setServerClientId(serverClientId).setFilterByAuthorizedAccounts(false).build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val result = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, request)
                val credential = result.credential
                if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    appViewModel.showAuthError("Google did not return a supported sign-in credential.")
                    return@launch
                }
                appViewModel.loginWithGoogle(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } catch (error: Exception) {
                appViewModel.showAuthError(error.localizedMessage ?: "Google sign-in was cancelled or failed.")
            }
        }
    }

}

private val Forest = androidx.compose.ui.graphics.Color(0xFF174B37)
private val Cream = androidx.compose.ui.graphics.Color(0xFFF6F4ED)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AjouhaarApp(
    vm: AppViewModel,
    googleSignIn: () -> Unit,
    biometricEnabled: Boolean,
    onBiometricSignIn: () -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit
) {
    val state by vm.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, secondary = androidx.compose.ui.graphics.Color(0xFFC79A42), background = Cream, surface = androidx.compose.ui.graphics.Color.White)) {
        Surface(Modifier.fillMaxSize(), color = Cream) {
            if (state.screen == Screen.LOGIN || state.screen == Screen.REGISTER) {
                AuthPage(state.error, vm::login, googleSignIn, biometricEnabled, onBiometricSignIn)
            } else {
                Scaffold(containerColor = Cream, topBar = {
                    TopAppBar(title = { Row(verticalAlignment = Alignment.CenterVertically) { Image(painterResource(R.drawable.ajouhaar_logo), contentDescription = "Ajouhaar Moslem Butchery logo", modifier = Modifier.size(36.dp, 30.dp), contentScale = ContentScale.Fit); Spacer(Modifier.width(8.dp)); Text("Ajouhaar Butchery", fontWeight = FontWeight.Bold) } }, actions = { if (vm.demoMode) Text("DEMO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 16.dp)) })
                }, bottomBar = { NavigationBar {
                    listOf(Triple(Screen.DASHBOARD, "Home", Icons.Default.Home), Triple(Screen.SALE, "Sale", Icons.Default.ShoppingCart), Triple(Screen.PRODUCTS, "Products", Icons.Default.Inventory2), Triple(Screen.STOCK, "Stock", Icons.Default.Warehouse), Triple(Screen.SETTINGS, "More", Icons.Default.Menu)).forEach { (screen, label, icon) ->
                        NavigationBarItem(selected = state.screen == screen, onClick = { vm.navigate(screen) }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) })
                    }
                } }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        if (vm.demoMode) DemoBanner()
                        when (state.screen) {
                            Screen.DASHBOARD -> DashboardPage(state, vm)
                            Screen.SALE -> SalePage(state, vm)
                            Screen.PRODUCTS -> ProductsPage(state, vm)
                            Screen.STOCK -> StockPage(state, vm)
                            Screen.TRANSACTIONS -> TransactionsPage(state)
                            Screen.REPORTS -> ReportsPage(state, vm)
                            Screen.SETTINGS -> MorePage(state, vm, biometricEnabled, onEnableBiometric, onDisableBiometric)
                            Screen.HELP -> HelpPage()
                            Screen.STAFF -> StaffPage(state, vm)
                            else -> DashboardPage(state, vm)
                        }
                    }
                }
            }
            state.error?.let { message -> AlertDialog(onDismissRequest = vm::dismissError, title = { Text("Please check") }, text = { Text(message) }, confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } }) }
            state.receipt?.let { sale -> ReceiptDialog(sale, vm::dismissReceipt) }
        }
    }
}

@Composable private fun DemoBanner() { Surface(color = androidx.compose.ui.graphics.Color(0xFFFFF2D7)) { Text("DEMO MODE · Data is stored only in memory on this device", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = androidx.compose.ui.graphics.Color(0xFF644A16), style = MaterialTheme.typography.labelMedium) } }

@Composable private fun AuthPage(error: String?, login: (String, String) -> Unit, googleSignIn: () -> Unit, biometricEnabled: Boolean, biometricSignIn: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(36.dp)); Image(painterResource(R.drawable.ajouhaar_logo), contentDescription = "Ajouhaar Moslem Butchery logo", modifier = Modifier.size(width = 170.dp, height = 140.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(16.dp)); Text("Ajouhaar", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Forest); Text("Moslem Butchery · Sales & Stock", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp)); Text("Welcome back", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp)); OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
        Spacer(Modifier.height(12.dp)); OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Hide" else "Show") } })
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(18.dp)); Button(onClick = { login(username, password) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Log in") }
        if (biometricEnabled) { Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = biometricSignIn, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Continue with biometrics") } }
        Spacer(Modifier.height(10.dp)); OutlinedButton(onClick = googleSignIn, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Continue with Google") }
    }
}
@Composable private fun DashboardPage(state: AppState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Hello, ${state.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${state.role} · Here's today's overview", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Today's sales", formatMoney(vm.todaySalesCents()), Icons.Default.Payments, Modifier.weight(1f))
            MetricCard("Products", state.products.size.toString(), Icons.Default.Inventory2, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCard("Low stock", state.products.count { it.stock <= 5 }.toString(), Icons.Default.Warning, Modifier.weight(1f))
            MetricCard("Transactions", state.transactions.size.toString(), Icons.Default.Receipt, Modifier.weight(1f))
        }
        SectionTitle("Quick actions")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Button(onClick = { vm.navigate(Screen.SALE) }, Modifier.weight(1f)) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(5.dp)); Text("New sale") }; OutlinedButton(onClick = { vm.navigate(Screen.PRODUCTS) }, Modifier.weight(1f)) { Text("View products") } }
        SectionTitle("Recent transactions")
        if (state.transactions.isEmpty()) EmptyMessage("No sales yet", "Completed demo sales will appear here.") else state.transactions.take(3).forEach { TransactionRow(it) }
    }
}

@Composable private fun MetricCard(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Icon(icon, null, tint = Forest); Text(title, style = MaterialTheme.typography.bodySmall); Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) } }
}
@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
@Composable private fun EmptyMessage(title: String, body: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } } }

@Composable private fun SalePage(state: AppState, vm: AppViewModel) {
    var productSearch by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("New sale", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(productSearch, { productSearch = it }, Modifier.fillMaxWidth(), label = { Text("Find a product") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        state.products.filter { it.name.contains(productSearch, true) || it.category.contains(productSearch, true) }.forEach { product ->
            Card(onClick = { vm.addToCart(product.id) }, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(product.name, fontWeight = FontWeight.SemiBold); Text("${product.category} · ${formatKg(product.stock)} kg in stock", style = MaterialTheme.typography.bodySmall) }; Text("${formatMoney(product.priceCents)}/kg", fontWeight = FontWeight.Bold); IconButton(onClick = { vm.addToCart(product.id) }) { Icon(Icons.Default.AddCircle, "Add ${product.name}", tint = Forest) } } }
        }
        SectionTitle("Basket")
        val lines = state.cart
        if (lines.isEmpty()) Text("Tap a product above to add it.", style = MaterialTheme.typography.bodySmall)
        lines.forEach { line -> val p = state.products.find { it.id == line.productId } ?: return@forEach
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.Medium); Text("${line.quantity} kg · ${formatMoney(p.priceCents * line.quantity)}", style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { vm.changeQuantity(p.id, line.quantity - 1) }) { Icon(Icons.Default.Remove, "Decrease quantity") }; Text("${line.quantity} kg"); IconButton(onClick = { vm.changeQuantity(p.id, line.quantity + 1) }) { Icon(Icons.Default.Add, "Increase quantity") } } }
        }
        HorizontalDivider(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Total", style = MaterialTheme.typography.titleMedium); Text(formatMoney(vm.totalCents()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        Text("Payment method", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Cash", "Card", "EFT").forEach { method -> FilterChip(selected = state.paymentMethod == method, onClick = { vm.setPayment(method) }, label = { Text(method) }) } }
        OutlinedTextField(state.paidText, vm::setPaid, Modifier.fillMaxWidth(), label = { Text("Amount paid (R)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        val paid = parseMoneyCents(state.paidText)
        if (paid != null && paid >= vm.totalCents() && vm.totalCents() > 0) Text("Change due: ${formatMoney(paid - vm.totalCents())}", color = Forest, fontWeight = FontWeight.SemiBold)
        Button(onClick = vm::completeSale, enabled = lines.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Complete sale") }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable private fun ProductsPage(state: AppState, vm: AppViewModel) {
    var showAdd by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }; var category by remember { mutableStateOf("") }; var price by remember { mutableStateOf("") }; var stock by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Products", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); if (state.role == "Manager") FilledTonalButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, null); Text("Add") } }
        OutlinedTextField(state.search, vm::setSearch, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), label = { Text("Search products") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(state.products.filter { it.name.contains(state.search, true) || it.category.contains(state.search, true) }, key = { it.id }) { product -> ProductCard(product) } }
    }
    if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Add product") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(name, { name = it }, label = { Text("Product name") }, singleLine = true); OutlinedTextField(category, { category = it }, label = { Text("Category") }, singleLine = true); OutlinedTextField(price, { price = it }, label = { Text("Price per kg (R)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)); OutlinedTextField(stock, { stock = it }, label = { Text("Opening stock (kg)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)) } }, confirmButton = { TextButton(onClick = { vm.addProduct(name, category, price, stock); showAdd = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } })
}

@Composable private fun ProductCard(p: Product) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(color = Cream, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.SetMeal, null, Modifier.padding(10.dp), tint = Forest) }; Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text(p.category, style = MaterialTheme.typography.bodySmall); Text("${formatKg(p.stock)} kg in stock", color = if (p.stock <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }; Text("${formatMoney(p.priceCents)}/kg", fontWeight = FontWeight.Bold) } } }

@Composable private fun StockPage(state: AppState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Stock", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Low stock is 5 kg or less", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)); LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(state.products, key = { it.id }) { p -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text(if (p.stock <= 5) "LOW STOCK · ${formatKg(p.stock)} kg" else "${formatKg(p.stock)} kg", color = if (p.stock <= 5) MaterialTheme.colorScheme.error else Forest, style = MaterialTheme.typography.bodySmall) }; if (state.role == "Manager") { IconButton(onClick = { vm.adjustStock(p.id, -1) }, enabled = p.stock > 0) { Icon(Icons.Default.Remove, "Remove 1 kg") } }; Text("${formatKg(p.stock)} kg", fontWeight = FontWeight.Bold); if (state.role == "Manager") IconButton(onClick = { vm.adjustStock(p.id, 1) }) { Icon(Icons.Default.Add, "Add 1 kg") } } } } } }
}

@Composable private fun TransactionsPage(state: AppState) { Column(Modifier.fillMaxSize().padding(16.dp)) { Text("Transactions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); if (state.transactions.isEmpty()) EmptyMessage("No transactions", "Sales appear here after checkout.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) { items(state.transactions, key = { it.number }) { TransactionRow(it) } } } }
@Composable private fun TransactionRow(t: Transaction) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(t.number, fontWeight = FontWeight.SemiBold); Text(t.createdAt.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")), style = MaterialTheme.typography.bodySmall); Text("${t.lines.size} line item(s) · ${t.paymentMethod}", style = MaterialTheme.typography.bodySmall) }; Text(formatMoney(t.totalCents), fontWeight = FontWeight.Bold) } } }

@Composable private fun ReportsPage(state: AppState, vm: AppViewModel) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Reports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Daily summary · ${java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))}"); MetricCard("Sales today", formatMoney(vm.todaySalesCents()), Icons.Default.Payments, Modifier.fillMaxWidth()); MetricCard("Transactions recorded", state.transactions.size.toString(), Icons.Default.Receipt, Modifier.fillMaxWidth()); MetricCard("Units sold", state.transactions.sumOf { t -> t.lines.sumOf { it.quantity } }.toString(), Icons.Default.ShoppingCart, Modifier.fillMaxWidth()); Text("Report data is limited to this demo session.", style = MaterialTheme.typography.bodySmall) } }

@Composable private fun MorePage(
    state: AppState,
    vm: AppViewModel,
    biometricEnabled: Boolean,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("More", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(state.name, fontWeight = FontWeight.Bold); Text(state.email, style = MaterialTheme.typography.bodySmall); Text("Role: ${state.role}") } }
        Text("Device security", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (biometricEnabled) {
            Text("Biometric sign-in is enabled on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::lockSession, Modifier.fillMaxWidth()) { Icon(Icons.Default.Lock, null); Spacer(Modifier.width(8.dp)); Text("Lock app") }
            TextButton(onClick = onDisableBiometric, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Turn off biometric sign-in") }
        } else {
            Text("Sign in first, then enable biometrics to unlock this account on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onEnableBiometric, Modifier.fillMaxWidth()) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Enable biometric sign-in") }
        }
        ListItem(headlineContent = { Text("Transactions") }, leadingContent = { Icon(Icons.Default.Receipt, null) }, modifier = Modifier.fillMaxWidth(), trailingContent = { TextButton(onClick = { vm.navigate(Screen.TRANSACTIONS) }) { Text("Open") } })
        if (state.role == "Manager") ListItem(headlineContent = { Text("Staff") }, leadingContent = { Icon(Icons.Default.People, null) }, trailingContent = { TextButton(onClick = { vm.navigate(Screen.STAFF) }) { Text("Manage") } })
        ListItem(headlineContent = { Text("Reports") }, leadingContent = { Icon(Icons.Default.BarChart, null) }, trailingContent = { TextButton(onClick = { vm.navigate(Screen.REPORTS) }) { Text("Open") } })
        ListItem(headlineContent = { Text("Help") }, leadingContent = { Icon(Icons.Default.Help, null) }, trailingContent = { TextButton(onClick = { vm.navigate(Screen.HELP) }) { Text("Open") } })
        OutlinedButton(onClick = vm::logout, Modifier.fillMaxWidth()) { Icon(Icons.Default.Logout, null); Spacer(Modifier.width(8.dp)); Text("Log out") }
    }
}

@Composable private fun StaffPage(state: AppState, vm: AppViewModel) {
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var googleEmail by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Cashier") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Staff", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = vm::loadStaff) { Icon(Icons.Default.Refresh, null); Text("Refresh") }
            Button(onClick = { showAdd = true }) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Add staff") }
        }
        Text("Only managers can add or remove staff. Passwords are stored as salted PBKDF2 hashes.", style = MaterialTheme.typography.bodySmall)
        if (state.staff.isEmpty()) EmptyMessage("No staff loaded", "Use Refresh to load staff from Firebase.")
        state.staff.forEach { staff ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(staff.fullName, fontWeight = FontWeight.SemiBold)
                        Text("${staff.username} · ${staff.role}", style = MaterialTheme.typography.bodySmall)
                        if (staff.googleEmail.isNotBlank()) Text("Google: ${staff.googleEmail}", style = MaterialTheme.typography.bodySmall)
                        if (!staff.isActive) Text("Inactive", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { vm.removeStaff(staff.uid) }) { Icon(Icons.Default.Delete, "Remove ${staff.username}", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (showAdd) AlertDialog(
        onDismissRequest = { showAdd = false },
        title = { Text("Add staff member") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true)
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Password (4+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(googleEmail, { googleEmail = it }, label = { Text("Google email (optional for SSO)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = role == "Cashier", onClick = { role = "Cashier" }, label = { Text("Cashier") })
                FilterChip(selected = role == "Manager", onClick = { role = "Manager" }, label = { Text("Manager") })
            }
        } },
        confirmButton = { TextButton(onClick = { vm.addStaff(username, name, password, role, googleEmail); showAdd = false; name = ""; username = ""; password = ""; googleEmail = "" }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
    )
}

@Composable private fun HelpPage() { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Help", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); HelpItem("New sale", "Open Sale, tap products to add them, adjust quantities, choose a payment method and enter the amount received. Complete sale updates the demo stock and shows a receipt."); HelpItem("Products", "Cashiers can search and view the catalogue. The manager can also add products."); HelpItem("Stock", "Cashiers can view stock. The manager can adjust stock. Sales automatically reduce stock."); HelpItem("Reports and transactions", "These screens summarize completed sales from the current demo session. Data is cleared when the app process is reset."); HelpItem("Accounts", "Demo accounts are preconfigured for local role-based walkthroughs. No account credentials are sent to a server.") } }
@Composable private fun HelpItem(title: String, body: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodySmall) } } }

@Composable private fun ReceiptDialog(t: Transaction, close: () -> Unit) { AlertDialog(onDismissRequest = close, title = { Text("Sale complete · ${t.number}") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("Ajouhaar Moslem Butchery", fontWeight = FontWeight.Bold); Text(t.createdAt.format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))); HorizontalDivider(); t.lines.forEach { Text("${it.quantity} × ${it.productName}  ${formatMoney(it.totalCents)}") }; HorizontalDivider(); Text("Total: ${formatMoney(t.totalCents)}", fontWeight = FontWeight.Bold); Text("Paid: ${formatMoney(t.paidCents)} · ${t.paymentMethod}"); Text("Change: ${formatMoney(t.changeCents)}", fontWeight = FontWeight.SemiBold); Text("Cashier: ${t.cashier}", style = MaterialTheme.typography.bodySmall); Text("Demo receipt · print/share requires backend integration", style = MaterialTheme.typography.bodySmall) } }, confirmButton = { Button(onClick = close) { Text("Done") } }) }
