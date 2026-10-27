package com.offerpoint.pos.ui.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.repository.*
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.ui.components.*
import com.offerpoint.pos.ui.scanner.BarcodeCameraScanner
import com.offerpoint.pos.ui.screens.PosViewModelFactory

/** A neutral top-corner icon opens sensitive prices/profit only after an explicit staff tap. */
@Composable
fun StaffToolsAction(shop: Shop, products: ProductRepository, labels: BarcodeLabelRepository, cart: List<CheckoutLine>? = null,
    orderDiscount: String = "0", sale: SaleDetails? = null, enabled: Boolean = true, onVisibilityChanged: (Boolean) -> Unit = {}) {
    val vm: StaffToolsViewModel = viewModel(key = "staff-tools-${shop.id}", factory = PosViewModelFactory { StaffToolsViewModel(products, labels, shop.id) })
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(state.open) { onVisibilityChanged(state.open) }
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.close() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.close() }
    }
    LaunchedEffect(enabled) { if (!enabled) vm.close() }
    IconButton(onClick = { onVisibilityChanged(true); vm.open(cart, orderDiscount, sale) }, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = "Staff prices and profit" }) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = Stroke(width = 1.8.dp.toPx())
            drawRoundRect(color, Offset(size.width * .12f, size.height * .08f), Size(size.width * .65f, size.height * .64f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = stroke)
            drawLine(color, Offset(size.width * .28f, size.height * .28f), Offset(size.width * .61f, size.height * .28f), stroke.width)
            drawLine(color, Offset(size.width * .28f, size.height * .47f), Offset(size.width * .52f, size.height * .47f), stroke.width)
            drawCircle(color, size.width * .18f, Offset(size.width * .69f, size.height * .68f), style = stroke)
            drawLine(color, Offset(size.width * .82f, size.height * .82f), Offset(size.width * .97f, size.height * .97f), stroke.width)
        }
    }
    if (state.open) Dialog(onDismissRequest = vm::close, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text("Staff prices & profit", style = MaterialTheme.typography.titleLarge); Text(shop.name, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = vm::close) { Text("Hide") }
                }
                LazyColumn(Modifier.weight(1f, fill = false).testTag("staff-tools-list"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item { Text("Private staff view. Hide this panel before showing the phone to a customer. These details stay off receipts.", style = MaterialTheme.typography.bodySmall) }
                    if (state.hasSale) {
                        item {
                            if (state.profitLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                            state.profit?.let { p -> StatusCard(if (state.savedSale) "Saved sale gross profit" else "Estimated sale gross profit",
                                "${Money.format(p.profitPaise)}\nSale after discounts: ${Money.format(p.revenuePaise)}\nPurchase cost: ${Money.format(p.costPaise)}") }
                            state.profitError?.let { ErrorNotice(it) }
                            Text(if (state.savedSale) "Uses costs recorded when this sale was saved, before any later returns or expenses."
                                else "Uses current recorded purchase costs and all cart discounts. Final profit uses costs at payment. Shop expenses are excluded.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item { Text("Product price lookup", style = MaterialTheme.typography.titleMedium) }
                    item {
                        OutlinedTextField(state.query, vm::search, label = { Text("Find name, SKU or barcode") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { vm.scan(!state.scanning) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(if (state.scanning) "Stop price scan" else "Scan price barcode") }
                    }
                    if (state.scanning) item { BarcodeCameraScanner(enabled = state.scanning, onBarcode = vm::scanned) }
                    if (state.loading || state.lookupLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    state.productError?.let { item { ErrorNotice(it) } }
                    state.error?.let { item { ErrorNotice(it) } }
                    state.selected?.let { product -> item {
                        StatusCard(product.name, "${product.sku}\nSelling price: ${Money.format(product.sellingPricePaise)}\nBought for: ${Money.format(product.purchaseCostPaise)}\nStock: ${product.quantity}")
                        Text("Current prices for ${shop.name}. This lookup does not add to the cart or change stock.", style = MaterialTheme.typography.bodySmall)
                    } }
                    if (!state.scanning && state.selected == null) {
                        if (!state.loading && state.products.isEmpty()) item { Text("No products found in this shop.") }
                        items(state.products, key = { it.id }) { product -> OutlinedCard(onClick = { vm.select(product) }, modifier = Modifier.testTag("staff-price-product-${product.id}")) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) { Text(product.name, style = MaterialTheme.typography.titleMedium); Text("${product.sku} · ${Money.format(product.sellingPricePaise)}", style = MaterialTheme.typography.bodySmall) }
                        } }
                    }
                }
            }
        }
    }
}
