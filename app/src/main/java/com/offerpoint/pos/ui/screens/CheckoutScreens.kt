package com.offerpoint.pos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.usecase.BusinessDay
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.ui.components.EmptyState
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.components.StatusCard
import com.offerpoint.pos.ui.scanner.BarcodeCameraScanner
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun CheckoutScreen(
    shop: Shop,
    state: CheckoutState,
    onSearch: (String) -> Unit,
    onRetryProducts: () -> Unit,
    onAdd: (Product) -> Unit,
    onScanBarcode: (String, String) -> Unit,
    onScanNext: () -> Unit,
    canChangeSection: () -> Boolean,
    onEditLine: (Long, String, String) -> Boolean,
    onRemove: (Long) -> Unit,
    onOrderDiscount: (String) -> Unit,
    onCashReceived: (String) -> Unit,
    onExactCash: () -> Unit,
    onRefresh: () -> Unit,
    onPay: () -> Unit,
    onRecover: () -> Unit,
    onSaved: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var section by rememberSaveable(shop.id) { mutableStateOf(if (shop.code == "OP") "scan" else "products") }
    var revealCost by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var quantity by rememberSaveable { mutableStateOf("") }
    var discount by rememberSaveable { mutableStateOf("") }
    val changeSection: (String) -> Unit = { target ->
        if (canChangeSection()) { revealCost = false; section = target }
    }
    val requestBack: () -> Unit = {
        if (!state.locked) {
            if (state.cart.isEmpty()) onBack() else discarding = true
        }
    }
    BackHandler(enabled = state.locked || state.cart.isNotEmpty(), onBack = requestBack)
    LaunchedEffect(state.completedSaleId) { state.completedSaleId?.let(onSaved) }
    LaunchedEffect(state.scanResetKey, state.scannedCostCode) { revealCost = false }
    PosScaffold("Checkout", shop.name, requestBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).imePadding()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = section == "scan", onClick = { changeSection("scan") }, label = { Text("Scan") }, enabled = !state.locked)
                FilterChip(selected = section == "cart", onClick = { changeSection("cart") }, label = { Text("Cart (${state.cart.size})") }, enabled = !state.locked)
                TextButton(onClick = { changeSection("products") }, enabled = !state.locked) { Text("Products") }
            }
            if (state.recovering || state.saving) Text(
                if (state.recovering) "Checking the previous checkout…" else "Saving sale and stock together…",
                Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.primary,
            )
            if (state.recoveryRequired && !state.recovering && !state.saving) {
                ErrorNotice(state.error ?: "Check the previous checkout before making changes.")
                Button(onClick = onRecover, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Check previous checkout") }
            }
            if (section == "scan") {
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Scan an item", style = MaterialTheme.typography.titleLarge)
                    Text("Point the camera at one barcode. A registered item is added to the cart automatically.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BarcodeCameraScanner(
                        enabled = !state.locked && !state.scanPaused,
                        onBarcode = { value, format ->
                            // Guard the frame before disposal/recomposition after a tab or lifecycle change.
                            if (section == "scan" && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                revealCost = false
                                onScanBarcode(value, format)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        resetKey = state.scanResetKey,
                    )
                    if (state.scanProcessing) Text("Finding the scanned product…", color = MaterialTheme.colorScheme.primary)
                    state.scanMessage?.let { StatusCard("Added to cart", it) }
                    state.scannedCostCode?.let { code ->
                        Text("Private purchase-cost code: $code", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { revealCost = !revealCost }) { Text(if (revealCost) "Hide purchase cost" else "Staff: show purchase cost") }
                        if (revealCost) state.scannedPurchaseCostPaise?.let { cost ->
                            StatusCard("Purchase cost on this label", "${Money.format(cost)}\nThis is the label's cost snapshot. Checkout prices come from the current inventory record.")
                        }
                    }
                    if (state.error != null && !state.recoveryRequired) ErrorNotice(state.error)
                    if (state.scanPaused) Button(onClick = { revealCost = false; onScanNext() }, enabled = !state.locked,
                        modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Scan next item") }
                    Text("Manufacturer barcodes and your own Code 128 labels are supported. Use Products for items without barcodes.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                }
                Button(onClick = { changeSection("cart") }, enabled = state.cart.isNotEmpty() && !state.scanProcessing,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(54.dp)) {
                    Text("Review cart (${state.cart.size})" + (state.totals?.let { " · ${Money.format(it.totalPaise)}" } ?: ""))
                }
            } else if (section == "products") {
                OutlinedTextField(state.query, onSearch, label = { Text("Search name, SKU, or barcode") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    trailingIcon = { if (state.query.isNotEmpty()) TextButton(onClick = { onSearch("") }) { Text("Clear") } })
                Spacer(Modifier.height(12.dp))
                when {
                    state.productsLoading -> Column(Modifier.weight(1f)) { LoadingContent() }
                    state.productError != null -> Column(Modifier.weight(1f)) { ErrorNotice(state.productError, onRetryProducts) }
                    state.products.isEmpty() -> Column(Modifier.weight(1f)) {
                        EmptyState("No products found", "Add products and opening stock from Products, or try another search.")
                    }
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(state.products, key = { it.id }) { product ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                                    Text(listOf(product.sku, product.variant).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column {
                                            Text(Money.format(product.sellingPricePaise), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                            Text("${product.quantity} in stock", style = MaterialTheme.typography.bodySmall)
                                        }
                                        val inCart = state.cart.firstOrNull { it.productId == product.id }?.quantity ?: 0
                                        Button(onClick = { onAdd(product) }, enabled = !state.locked && product.quantity > inCart) {
                                            Text(if (product.quantity == 0L) "Out of stock" else if (inCart > 0) "+ Add ($inCart)" else "+ Add")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.error != null && !state.recoveryRequired) ErrorNotice(state.error)
                Button(onClick = { changeSection("cart") }, enabled = state.cart.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(54.dp)) {
                    Text("Review cart (${state.cart.size})" + (state.totals?.let { " · ${Money.format(it.totalPaise)}" } ?: ""))
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { Text("Cart & payment", style = MaterialTheme.typography.titleLarge) }
                    if (state.cart.isEmpty()) item {
                        EmptyState("Your cart is empty", "Choose products for ${shop.name}. Stock is deducted only when a sale is completed.")
                        Button(onClick = { changeSection("products") }, modifier = Modifier.fillMaxWidth()) { Text("Choose products") }
                    }
                    items(state.cart, key = { it.productId }) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(item.sku, item.variant).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${item.quantity} × ${Money.format(item.unitPricePaise)}", style = MaterialTheme.typography.titleMedium)
                                if (item.discountPaise > 0) Text("Line discount: ${Money.format(item.discountPaise)}", color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { editingId = item.productId; quantity = item.quantity.toString(); discount = Money.input(item.discountPaise) }, enabled = !state.locked) { Text("Quantity / discount") }
                                    TextButton(onClick = { onRemove(item.productId) }, enabled = !state.locked) { Text("Remove") }
                                }
                            }
                        }
                    }
                    if (state.cart.isNotEmpty()) {
                        item { OutlinedButton(onClick = onRefresh, enabled = !state.locked, modifier = Modifier.fillMaxWidth()) { Text(if (state.refreshing) "Refreshing…" else "Refresh cart prices and stock") } }
                        if (state.notice != null) item { StatusCard("Cart", state.notice) }
                        item {
                            OutlinedTextField(state.orderDiscount, onOrderDiscount, label = { Text("Additional order discount (Rs.)") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.locked,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                supportingText = { Text("A fixed amount after any line discounts. Zero is allowed.") })
                        }
                        if (state.calculationError != null) item { ErrorNotice(state.calculationError) }
                        state.totals?.let { totals ->
                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AmountRow("Subtotal", Money.format(totals.subtotalPaise))
                                        AmountRow("Line discounts", Money.format(totals.lineDiscountPaise))
                                        AmountRow("Order discount", Money.format(totals.orderDiscountPaise))
                                        HorizontalDivider()
                                        AmountRow("Total", Money.format(totals.totalPaise), prominent = true)
                                    }
                                }
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Cash payment", style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = onExactCash, enabled = !state.locked) { Text("Exact cash") }
                                }
                                OutlinedTextField(state.cashReceived, onCashReceived, label = { Text("Cash received (Rs.)") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.locked,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    isError = state.cashError != null,
                                    supportingText = { Text(state.cashError ?: "Enter the cash handed over by the customer.") })
                                state.changePaise?.let { change -> Text("Change: ${Money.format(change)}", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                        if (state.error != null && !state.recoveryRequired) item { ErrorNotice(state.error) }
                        item {
                            Button(onClick = onPay, enabled = !state.locked && state.totals != null && state.changePaise != null,
                                modifier = Modifier.fillMaxWidth().height(56.dp)) { Text(if (state.saving) "Saving sale…" else "Complete cash sale") }
                            Text("This saves a real sale and deducts stock for ${shop.name}.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Open the saved sale to share or print its receipt.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (editingId != null && !state.locked) AlertDialog(
        onDismissRequest = { if (!state.locked) editingId = null },
        title = { Text("Cart item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity (whole units)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !state.locked, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(discount, { discount = it }, label = { Text("Total line discount (Rs.)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !state.locked, modifier = Modifier.fillMaxWidth())
                Text("The discount applies to the whole line, not each unit.", style = MaterialTheme.typography.bodySmall)
                state.error?.let { ErrorNotice(it) }
            }
        },
        confirmButton = { Button(onClick = { editingId?.let { if (onEditLine(it, quantity, discount)) editingId = null } }, enabled = !state.locked) { Text("Apply") } },
        dismissButton = { TextButton(onClick = { editingId = null }, enabled = !state.locked) { Text("Cancel") } },
    )
    if (discarding && !state.locked) AlertDialog(
        onDismissRequest = { if (!state.locked) discarding = false }, title = { Text("Discard this cart?") },
        text = { Text("No sale has been saved for this cart. Its products will remain in stock.") },
        confirmButton = { TextButton(onClick = { if (!state.locked) { discarding = false; onBack() } }, enabled = !state.locked) { Text("Discard cart") } },
        dismissButton = { TextButton(onClick = { if (!state.locked) discarding = false }, enabled = !state.locked) { Text("Keep checkout") } },
    )
}

@Composable
private fun AmountRow(label: String, value: String, prominent: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
        Text(value, style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
    }
}

private val SaleDateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy · h:mm a", Locale.ENGLISH).withZone(BusinessDay.zone)
internal fun saleDate(timestamp: Long): String = SaleDateFormat.format(Instant.ofEpochMilli(timestamp))

@Composable
internal fun SalesHistoryScreen(shop: Shop, state: SalesHistoryState, onRetry: () -> Unit, onOpen: (Long) -> Unit, onBack: () -> Unit) {
    PosScaffold("Sales history", shop.name, onBack) { padding ->
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding)) { LoadingContent("Loading saved sales…") }
            state.error != null -> Column(Modifier.padding(padding).padding(20.dp)) { ErrorNotice(state.error, onRetry) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Completed cash sales · Pakistan time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.sales.isEmpty()) item { EmptyState("No sales yet", "Completed checkouts for ${shop.name} will appear here.") }
                items(state.sales, key = { it.id }) { sale ->
                    Card(onClick = { onOpen(sale.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(sale.receiptNumber, style = MaterialTheme.typography.titleMedium)
                            Text(saleDate(sale.completedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AmountRow("Cash sale", Money.format(sale.totalPaise))
                            Text("View saved sale →", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SaleDetailsScreen(shop: Shop, state: SaleDetailsState, justCompleted: Boolean, onRetry: () -> Unit, onReceipt: () -> Unit, onNewSale: () -> Unit, onBack: () -> Unit) {
    PosScaffold(if (justCompleted) "Sale saved" else "Sale details", shop.name, onBack) { padding ->
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding)) { LoadingContent("Opening the saved sale…") }
            state.error != null -> Column(Modifier.padding(padding).padding(20.dp)) { ErrorNotice(state.error, onRetry) }
            else -> state.sale?.let { sale ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item {
                        StatusCard(if (justCompleted) "Sale completed" else "Saved transaction", "${sale.summary.receiptNumber}\n${saleDate(sale.summary.completedAt)}\n${shop.name}")
                        if (justCompleted) Text("The sale and stock deductions were saved together.", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { OutlinedButton(onClick = onReceipt, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("View / print receipt") } }
                    items(sale.items) { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(listOf(item.sku, item.variant).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${item.quantity} × ${Money.format(item.unitPricePaise)}")
                                if (item.discountPaise > 0) Text("Line discount: ${Money.format(item.discountPaise)}", style = MaterialTheme.typography.bodySmall)
                                AmountRow("Line total", Money.format(item.lineTotalPaise))
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                AmountRow("Subtotal", Money.format(sale.subtotalPaise))
                                AmountRow("All discounts", Money.format(sale.discountPaise))
                                AmountRow("Total", Money.format(sale.summary.totalPaise), prominent = true)
                                HorizontalDivider()
                                AmountRow("Cash received", Money.format(sale.amountPaidPaise))
                                AmountRow("Change", Money.format(sale.changePaise), prominent = true)
                            }
                        }
                    }
                    item { Text("Receipts are available above. Returns are coming soon.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Button(onClick = onNewSale, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start a new sale") } }
                }
            }
        }
    }
}
