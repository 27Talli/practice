package com.offerpoint.pos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.domain.usecase.CapturedProductBarcode
import com.offerpoint.pos.ui.scanner.BarcodeCaptureDialog
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.components.StatusCard

@Composable
internal fun ProductEditorScreen(
    shop: Shop,
    editing: Boolean,
    state: ProductEditorState,
    onFindExisting: (String) -> Unit,
    onClearMatch: () -> Unit,
    onRetry: () -> Unit,
    onSave: (ProductFormInput, Long?) -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("") }
    var variant by rememberSaveable { mutableStateOf("") }
    var barcode by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var reorder by rememberSaveable { mutableStateOf("5") }
    var opening by rememberSaveable { mutableStateOf("0") }
    var sku by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var associatedProductId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var discardDialog by rememberSaveable { mutableStateOf(false) }
    var scanning by androidx.compose.runtime.remember { mutableStateOf(false) }
    var barcodeError by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val busy = state.saving || state.finding
    val sharedReadOnly = associatedProductId != null

    LaunchedEffect(state.original?.id) {
        val original = state.original
        if (!initialized && original != null) {
            name = original.name
            category = original.category
            variant = original.variant
            barcode = original.manufacturerBarcode.orEmpty()
            cost = Money.input(original.purchaseCostPaise)
            price = Money.input(original.sellingPricePaise)
            sku = original.sku
            reorder = original.reorderLevel.toString()
            initialized = true
        }
    }
    LaunchedEffect(state.matched?.id) {
        state.matched?.let { match ->
            associatedProductId = match.id
            name = match.name
            category = match.category
            variant = match.variant
            sku = match.sku
            dirty = true
        }
    }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    val requestBack: () -> Unit = { if (!busy) { if (dirty) discardDialog = true else onBack() } }
    BackHandler(enabled = dirty || busy) { requestBack() }

    PosScaffold(if (editing) "Edit product" else if (sharedReadOnly) "Associate product" else "Add product", shop.name, requestBack) { padding ->
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding)) { LoadingContent("Loading product…") }
            state.loadError != null -> Column(Modifier.padding(padding).padding(20.dp)) { ErrorNotice(state.loadError, onRetry) }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    StatusCard(
                        if (editing) "Product details are shared" else "Stocked at ${shop.name}",
                        if (editing) "Name, category, variant, and barcodes are shared across shops. Prices and inventory belong only to ${shop.name}."
                        else "Each size or colour with its own stock should be a separate product. Prices and stock will belong to this shop.",
                    )
                }
                item {
                    OutlinedTextField(
                        sku.ifBlank { "Assigned when saved (${shop.code}-000001…)" }, {}, readOnly = true,
                        label = { Text("SKU / internal Code 128 value") }, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("A unique SKU is generated once and cannot be changed.") }, singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        barcode, { barcode = it; dirty = true }, label = { Text("Existing manufacturer barcode (optional)") },
                        enabled = !busy, readOnly = sharedReadOnly, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        supportingText = { Text("Scan the packaging barcode or type it. Leading zeros are kept.") },
                    )
                    if (!sharedReadOnly) OutlinedButton(
                        onClick = { barcodeError = null; scanning = true }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                    ) { Text("Scan existing barcode") }
                    barcodeError?.let { ErrorNotice(it) }
                    if (!editing && !sharedReadOnly) OutlinedButton(
                        onClick = { onFindExisting(barcode) }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(if (state.finding) "Finding…" else "Find existing product by barcode / SKU") }
                    if (sharedReadOnly) TextButton(onClick = {
                        associatedProductId = null
                        name = ""
                        category = ""
                        variant = ""
                        sku = ""
                        barcode = ""
                        onClearMatch()
                        dirty = true
                    }, enabled = !busy) { Text("Clear match and create a new product") }
                }
                if (state.notice != null) item { StatusCard(if (sharedReadOnly) "Use existing registry product" else "Product lookup", state.notice) }
                item { Text("Product identity", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(name, { name = it; dirty = true }, label = { Text("Product name *") }, enabled = !busy, readOnly = sharedReadOnly, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    OutlinedTextField(category, { category = it; dirty = true }, label = { Text("Category *") }, placeholder = { Text("e.g. Cosmetics or Sandals") }, enabled = !busy, readOnly = sharedReadOnly, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item {
                    OutlinedTextField(variant, { variant = it; dirty = true }, label = { Text("Variant (optional)") }, placeholder = { Text("e.g. Rose / Size 38 / Black") }, enabled = !busy, readOnly = sharedReadOnly, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
                item { Text("${shop.name} · Prices & stock", style = MaterialTheme.typography.titleMedium) }
                item {
                    OutlinedTextField(
                        cost, { cost = it; dirty = true }, label = { Text("Purchase cost (Rs.) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy,
                        modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("Up to two decimal places. Zero is allowed.") },
                    )
                }
                item {
                    OutlinedTextField(
                        price, { price = it; dirty = true }, label = { Text("Selling price (Rs.) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !busy,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }
                item { OutlinedTextField(reorder,{reorder=it;dirty=true},label={Text("Low-stock alert level (whole units)")},enabled=!busy,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth()) }
                if (!editing) item {
                    OutlinedTextField(
                        opening, { opening = it; dirty = true }, label = { Text("Opening stock (whole units) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !busy,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        supportingText = { Text("Zero or more. Opening stock is saved with an audit movement.") },
                    )
                }
                if (editing) item {
                    StatusCard("Current stock: ${state.original?.quantity ?: 0} units", "Use Inventory → Adjust stock to change quantity with a recorded reason.")
                }
                if (state.error != null) item { ErrorNotice(state.error) }
                item {
                    Button(
                        onClick = { onSave(ProductFormInput(name, category, variant, barcode, cost, price, opening, reorder), associatedProductId) },
                        enabled = !busy && !state.saved,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) { Text(if (state.saving) "Saving…" else if (editing) "Save changes" else if (sharedReadOnly) "Associate with ${shop.name}" else "Save product") }
                    Spacer(Modifier.height(8.dp))
                    Text("Saved locally. No internet required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    if (scanning && !busy && !sharedReadOnly) BarcodeCaptureDialog(
        title = "Scan existing barcode",
        description = "Point at the product's packaging barcode. It fills the barcode field; review the details and tap Save product to register it.",
        onCaptured = { value, _ ->
            scanning = false
            try { barcode = CapturedProductBarcode.validate(value); barcodeError = null; dirty = true }
            catch (error: IllegalArgumentException) { barcodeError = error.message }
        },
        onDismiss = { scanning = false },
    )
    if (discardDialog) AlertDialog(
        onDismissRequest = { discardDialog = false },
        title = { Text("Discard unsaved changes?") },
        text = { Text("Your changes to this product have not been saved.") },
        confirmButton = { TextButton(onClick = { discardDialog = false; onBack() }) { Text("Discard changes") } },
        dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("Keep editing") } },
    )
}
