package com.offerpoint.pos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offerpoint.pos.BuildConfig
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.ui.components.DemoBanner
import com.offerpoint.pos.ui.components.EmptyState
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.components.StatusCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PosScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("‹ Back") }
                    },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                // The app bar consumes status-bar insets before the banner is laid out.
                DemoBanner(BuildConfig.DEMO_MODE)
            }
        },
        content = content,
    )
}

@Composable
internal fun LoadingContent(message: String = "Loading local records…") {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
internal fun ShopSelectionScreen(state: AppState, onSelect: (Long) -> Unit, onBack: (() -> Unit)?) {
    PosScaffold("Choose your shop", "Offer Point POS", onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("OP", Modifier.padding(horizontal = 18.dp, vertical = 14.dp), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(22.dp))
                Text("Two shops.\nOne simple workspace.", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(12.dp))
                Text("Choose the shop you’re working in. Prices, stock, and business records stay separate.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            if (state.selectionError != null) item { ErrorNotice(state.selectionError) }
            items(state.shops, key = { it.id }) { shop ->
                Card(
                    onClick = { onSelect(shop.id) }, enabled = !state.selecting,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(shop.code, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.weight(1f))
                            if (state.selectedShop?.id == shop.id) Text("CURRENT SHOP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            else Text("SELECT →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(shop.name, style = MaterialTheme.typography.titleLarge)
                        Text(shop.address, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (shop.code == "OP") "Beauty · Accessories · Gifts · Home" else "Women’s footwear · Everyday style",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (state.selecting) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            item { Text("Works entirely offline. Your choice is saved on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun DashboardScreen(
    shop: Shop,
    today: TodaySalesState,
    onRetryToday: () -> Unit,
    onProducts: () -> Unit,
    onInventory: () -> Unit,
    onCheckout: () -> Unit,
    onSales: () -> Unit,
    onReports: () -> Unit,
    onRefunds: () -> Unit,
    onExpenses: () -> Unit,
    onSettings: () -> Unit,
    onSwitch: () -> Unit,
) {
    PosScaffold("Offer Point POS", "Your retail workspace", actions = { TextButton(onClick = onSettings) { Text("Settings") } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("CURRENT SHOP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Surface(shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)) {
                                Text("● Offline ready", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Text(shop.name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(shop.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("Offer Point is the primary workspace. Other shop records remain available in Settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item {
                when {
                    today.loading -> StatusCard("Today’s sales", "Loading saved activity…")
                    today.error != null -> ErrorNotice(today.error, onRetryToday)
                    else -> today.sales?.let { sales ->
                        StatusCard("Today’s net sales · Pakistan time", "${Money.format(sales.totalPaise)}\n${sales.transactionCount} completed ${if (sales.transactionCount == 1) "sale" else "sales"}")
                    }
                }
            }
            item { Text("At your counter", Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleLarge) }
            item { ShortcutCard("Products", "Add, edit, and find products", "MANAGE", onProducts) }
            item { ShortcutCard("Inventory", "View quantities and adjust stock", "MANAGE", onInventory) }
            item { ShortcutCard("Checkout", "Record a cash sale and deduct stock", "CASH SALE", onCheckout) }
            item { ShortcutCard("Sales history", "Open completed transactions", "VIEW", onSales) }
            item { ShortcutCard("Returns & refunds", "Saved refunds and original receipt links", "VIEW", onRefunds) }
            item { ShortcutCard("Expenses", "Record and review shop expenses", "MANAGE", onExpenses) }
            item { ShortcutCard("Reports", "Net sales, costs, profit and CSV export", "VIEW", onReports) }
            item { Text("Saved locally on this device · No account required", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ShortcutCard(title: String, subtitle: String, label: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun ProductsScreen(shop: Shop, state: ProductListState, onQuery: (String) -> Unit, onRetry: () -> Unit, onAdd: () -> Unit, onEdit: (Long) -> Unit, onLabel: (Long) -> Unit, onBack: () -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(search) { onQuery(search) }
    PosScaffold("Products", shop.name, onBack, actions = { TextButton(onClick = onAdd) { Text("+ Add") } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            SearchField(search, { search = it })
            Spacer(Modifier.height(14.dp))
            when {
                state.loading -> LoadingContent()
                state.error != null -> ErrorNotice(state.error, onRetry)
                state.products.isEmpty() -> {
                    EmptyState(if (search.isBlank()) "Build your product list" else "No products found", if (search.isBlank()) "Add your first product for ${shop.name}. Opening stock will be recorded with it." else "Try a name, category, SKU, variant, or barcode.")
                    if (search.isBlank()) Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add product") }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { Text("${state.products.size} ${if (state.products.size == 1) "product" else "products"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(state.products, key = { it.id }) { product -> ProductCard(product, onClick = { onEdit(product.id) }, onLabel = { onLabel(product.id) }) }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value, onValueChange, modifier = Modifier.fillMaxWidth(), label = { Text("Search products") },
        placeholder = { Text("Name, SKU, barcode, category…") }, singleLine = true,
        shape = RoundedCornerShape(14.dp),
        trailingIcon = { if (value.isNotEmpty()) TextButton(onClick = { onValueChange("") }) { Text("Clear") } },
    )
}

@Composable
private fun ProductCard(product: Product, onClick: () -> Unit, onLabel: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    if (product.variant.isNotBlank()) Text(product.variant, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Money.format(product.sellingPricePaise), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("${product.sku} · ${product.category}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${product.quantity} in stock", style = MaterialTheme.typography.labelLarge, color = if (product.quantity == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Edit →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = onLabel, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)) { Text("Barcode label") }
        }
    }
}

@Composable
internal fun InventoryScreen(
    shop: Shop,
    state: ProductListState,
    adjustment: StockAdjustmentState,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onAdjust: (Long, String, Boolean, String) -> Unit,
    onClearError: () -> Unit,
    onAdd: () -> Unit,
    onBack: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var adjustingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var adjustingName by rememberSaveable { mutableStateOf("") }
    var displayedQuantity by rememberSaveable { mutableStateOf(0L) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var decrease by rememberSaveable { mutableStateOf(false) }
    var lastCompletion by rememberSaveable { mutableStateOf(adjustment.completion) }
    var completionSession by rememberSaveable { mutableStateOf(adjustment.sessionId) }
    LaunchedEffect(search) { onQuery(search) }
    LaunchedEffect(adjustment.sessionId, adjustment.completion) {
        if (completionSession != adjustment.sessionId) {
            // A new process has no knowledge of the previous VM's event counter.
            // Preserve unsaved form input rather than treating reset-to-zero as success.
            completionSession = adjustment.sessionId
            lastCompletion = adjustment.completion
        } else if (lastCompletion != adjustment.completion) {
            adjustingId = null
            lastCompletion = adjustment.completion
        }
    }
    PosScaffold("Inventory", shop.name, onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            SearchField(search, { search = it })
            Spacer(Modifier.height(14.dp))
            when {
                state.loading -> LoadingContent()
                state.error != null -> ErrorNotice(state.error, onRetry)
                state.products.isEmpty() -> {
                    EmptyState(if (search.isBlank()) "No stock to show yet" else "No products found", if (search.isBlank()) "Add products and opening stock to start managing this shop’s inventory." else "Try another search.")
                    if (search.isBlank()) Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Add product") }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("Stock belongs to ${shop.name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(state.products, key = { it.id }) { product ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(product.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(product.sku, product.variant).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column {
                                        Text(product.quantity.toString(), style = MaterialTheme.typography.headlineMedium, color = if (product.quantity == 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                        if(product.quantity<=product.reorderLevel) Text("LOW STOCK · alert at ${product.reorderLevel}",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelMedium)
                                        Text("units on hand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(onClick = {
                                        adjustingId = product.id
                                        adjustingName = product.name
                                        displayedQuantity = product.quantity
                                        quantity = ""
                                        reason = ""
                                        decrease = false
                                        onClearError()
                                    }, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text("Adjust stock") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (adjustingId != null) {
        val latestQuantity = state.products.firstOrNull { it.id == adjustingId }?.quantity ?: displayedQuantity
        AlertDialog(
            onDismissRequest = { if (!adjustment.saving) adjustingId = null },
            title = { Text("Adjust stock") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(adjustingName, style = MaterialTheme.typography.titleMedium)
                    Text("Current stock: $latestQuantity units", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !decrease, onClick = { decrease = false }, label = { Text("Increase") }, enabled = !adjustment.saving)
                        FilterChip(selected = decrease, onClick = { decrease = true }, label = { Text("Decrease") }, enabled = !adjustment.saving)
                    }
                    OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity (whole units)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !adjustment.saving, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(reason, { reason = it }, label = { Text("Reason (required)") }, placeholder = { Text("e.g. Stock count correction") }, minLines = 2, maxLines = 4, enabled = !adjustment.saving, modifier = Modifier.fillMaxWidth())
                    Text("Every adjustment is recorded. Stock cannot go below zero.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (adjustment.error != null) ErrorNotice(adjustment.error)
                }
            },
            confirmButton = { Button(onClick = { adjustingId?.let { onAdjust(it, quantity, decrease, reason) } }, enabled = !adjustment.saving) { Text(if (adjustment.saving) "Saving…" else "Save adjustment") } },
            dismissButton = { TextButton(onClick = { adjustingId = null }, enabled = !adjustment.saving) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SettingsScreen(shop: Shop, databaseStatus: String, onBack: () -> Unit, onCostCodes: () -> Unit, onReceipts: () -> Unit, onBackups: () -> Unit, onSecurity: () -> Unit, onSwitch: () -> Unit) {
    PosScaffold("Settings", shop.name, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Your local workspace", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text("Everything you need to manage the foundation of your shop, on this device.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingRow("Application", "Offer Point POS")
                        HorizontalDivider()
                        SettingRow("App version", BuildConfig.VERSION_NAME + if (BuildConfig.DEMO_MODE) " · Demo" else "")
                        HorizontalDivider()
                        SettingRow("Current shop", shop.name)
                        OutlinedButton(onClick = onCostCodes, modifier = Modifier.fillMaxWidth()) { Text("Staff cost-code key") }
                        OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) { Text("Other shop / legacy inventory") }
                    }
                }
            }
            item { OutlinedButton(onClick=onSecurity,modifier=Modifier.fillMaxWidth().height(52.dp)){Text("Local app PIN & lock")} }
            item { StatusCard("Offline status", "Offline ready. All product and stock records are saved on this device. No internet connection or account is required.") }
            item { StatusCard("Database status", databaseStatus) }
            item { OutlinedButton(onClick = onReceipts, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Receipts & printing") } }
            item { OutlinedButton(onClick=onBackups, modifier=Modifier.fillMaxWidth().height(54.dp)) { Text("Backups & restore") } }
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
internal fun ComingSoonScreen(title: String, shop: Shop, onBack: () -> Unit) {
    PosScaffold(title, shop.name, onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Coming soon", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Detailed sales and financial reports will be added in a later milestone.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            Text("${shop.name} · No transactions are created on this screen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Back to dashboard") }
        }
    }
}
