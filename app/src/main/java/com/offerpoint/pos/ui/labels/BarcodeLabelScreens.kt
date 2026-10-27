package com.offerpoint.pos.ui.labels

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offerpoint.pos.BuildConfig
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.components.StatusCard
import com.offerpoint.pos.ui.screens.LoadingContent
import com.offerpoint.pos.ui.screens.PosScaffold
import com.offerpoint.pos.ui.screens.PosViewModelFactory
import kotlinx.coroutines.flow.collect

@Composable
fun ProductLabelScreen(shop: Shop, productId: Long, repository: BarcodeLabelRepository, onBack: () -> Unit) {
    val vm: ProductLabelViewModel = viewModel(key = "label-${shop.id}-$productId",
        factory = PosViewModelFactory { ProductLabelViewModel(repository, shop.id, productId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var quantity by rememberSaveable { mutableStateOf("1") }
    var revealCost by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(vm, context) {
        vm.pdfExports.collect { file ->
            try {
                val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Barcode labels", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Share barcode labels").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (error: Exception) { vm.reportError(error.message ?: "No application could open the label PDF.") }
        }
    }
    PosScaffold("Barcode label", shop.name, onBack) { padding ->
        when {
            state.loading -> Column(Modifier.fillMaxSize().padding(padding)) { LoadingContent("Preparing this product’s barcode…") }
            state.label == null -> Column(Modifier.padding(padding).padding(20.dp)) {
                ErrorNotice(state.error ?: "The barcode label is unavailable.", vm::load)
            }
            else -> {
                val label = state.label!!
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text("Your own Code 128 label", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("This barcode identifies the product and contains its coded purchase cost. Manufacturer barcodes also remain valid.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item { LabelPreview(shop.name, label) }
                    item { StatusCard("Customer label", "Only product details and a coded value are printed. The purchase amount and staff key stay off the PDF.") }
                    item {
                        OutlinedTextField(quantity, { quantity = it }, label = { Text("Number of labels (1–100)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                            enabled = !state.exporting, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { vm.export(context, shop.name, quantity) }, enabled = !state.exporting,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(54.dp)) {
                            Text(if (state.exporting) "Creating PDF…" else "Create / share label PDF")
                        }
                        Text("Print the PDF at actual size (100%). Keep the white margins around each barcode.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Staff reference", style = MaterialTheme.typography.titleMedium)
                                Text("Cost code: ${label.costCode}", style = MaterialTheme.typography.bodyLarge)
                                TextButton(onClick = { revealCost = !revealCost }) { Text(if (revealCost) "Hide purchase cost" else "Show purchase cost") }
                                if (revealCost) Text("Purchase cost when this label was created: ${Money.format(label.purchaseCostPaise)}", color = MaterialTheme.colorScheme.primary)
                                Text("Older labels keep their original cost reference. Checkout uses the product’s current saved selling price and purchase cost; this label does not change stock or prices.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (state.error != null) item { ErrorNotice(state.error!!) }
                    item { OutlinedButton(onClick = vm::load, enabled = !state.exporting, modifier = Modifier.fillMaxWidth()) { Text("Refresh label from current product") } }
                }
            }
        }
    }
}

@Composable
private fun LabelPreview(shopName: String, label: InternalBarcodeLabel) {
    val bars = remember(label.barcodeValue) { runCatching { LabelBarcodePattern.modules(label.barcodeValue) }.getOrNull() }
    val printContent = remember(shopName, label) { LabelExportText.content(shopName, label, BuildConfig.DEMO_MODE) }
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(printContent.shopName, color = Color.Black, style = MaterialTheme.typography.labelLarge)
            Text(label.name, color = Color.Black, style = MaterialTheme.typography.titleMedium)
            if (label.variant.isNotBlank()) Text(label.variant, color = Color.Black, style = MaterialTheme.typography.bodySmall)
            Text(label.sku, color = Color.Black, style = MaterialTheme.typography.bodySmall)
            if (bars == null) Text("This code cannot be drawn. Refresh the label or contact support.", color = Color.Black)
            else Canvas(Modifier.fillMaxWidth().height(100.dp).background(Color.White)) {
                val module = size.width / (bars.size + LabelBarcodePattern.QUIET_MODULES * 2)
                val left = LabelBarcodePattern.QUIET_MODULES * module
                for (index in bars.indices) if (bars[index]) drawRect(Color.Black,
                    topLeft = Offset(left + index * module, 0f), size = Size(module, size.height))
            }
            Text(label.barcodeValue, color = Color.Black, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun CostCodeSettingsScreen(shop: Shop, repository: BarcodeLabelRepository, onBack: () -> Unit) {
    val vm: CostCodeViewModel = viewModel(key = "cost-key-${shop.id}", factory = PosViewModelFactory { CostCodeViewModel(repository, shop.id) })
    val state by vm.state.collectAsStateWithLifecycle()
    var alphabet by rememberSaveable { mutableStateOf("") }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.settings?.alphabet) {
        if (!initialized && state.settings != null) { alphabet = state.settings!!.alphabet; initialized = true }
    }
    PosScaffold("Staff cost-code key", shop.name, onBack) { padding ->
        if (state.loading) Column(Modifier.fillMaxSize().padding(padding)) { LoadingContent("Loading the private shop key…") }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { StatusCard("Keep this key private", "Ten different letters stand for digits 0–9. The barcode encodes purchase cost in paise: Rs. 125.50 means the digits 12550. This is a staff shorthand code, not encryption.") }
            item {
                OutlinedTextField(alphabet, { alphabet = it }, label = { Text("10-letter cost-code key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !state.saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") } },
                    supportingText = { Text("Exactly ten different A–Z letters; spaces are not included.") })
            }
            if (revealed && state.settings != null) item {
                Text("Saved digit mapping", style = MaterialTheme.typography.titleMedium)
                for (start in listOf(0, 5)) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (digit in start until start + 5) Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(digit.toString(), style = MaterialTheme.typography.labelLarge)
                            Text(state.settings!!.alphabet[digit].toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            item { Text("A new key affects new labels only. Old labels still identify the same product and retain their original purchase-cost reference. No products, sales, or stock records are deleted.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.error != null) item { ErrorNotice(state.error!!, if (state.settings == null) vm::load else null) }
            if (state.notice != null) item { StatusCard("Saved", state.notice!!) }
            item { Button(onClick = { vm.save(alphabet) }, enabled = !state.saving && state.settings != null, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(if (state.saving) "Saving…" else "Save staff key") } }
        }
    }
}
