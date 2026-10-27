package com.offerpoint.pos.ui.customize

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.offerpoint.pos.data.preferences.WorkspacePreferencesJson
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.ui.components.*
import com.offerpoint.pos.ui.screens.*
import com.offerpoint.pos.ui.theme.OfferPointTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WorkspaceCustomizationScreen(shop: Shop, model: WorkspaceCustomizationViewModel, state: WorkspaceCustomizationState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fileBusy by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    val busy = state.saving || fileBusy
    val requestBack: () -> Unit = { if (!busy) { if (state.dirty) discard = true else onBack() } }
    BackHandler(state.dirty || busy) { requestBack() }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileBusy = true
            scope.launch {
                try {
                    val value = withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openInputStream(uri)) { "The layout file could not be opened." }.use(WorkspacePreferencesJson::read)
                    }
                    model.imported(value)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    model.error(error.message ?: "The layout file could not be opened.")
                } finally { fileBusy = false }
            }
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val draft = state.draft
            fileBusy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val text = WorkspacePreferencesJson.encode(draft)
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Choose another layout-file destination." }
                            .bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                    }
                    model.message("Layout file saved. It contains appearance and shortcut settings only.")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    model.error(error.message ?: "The layout file could not be saved.")
                } finally { fileBusy = false }
            }
        }
    }
    val value = state.draft
    PosScaffold("Customize app", shop.name, requestBack) { padding ->
        if (state.loading) Column(Modifier.padding(padding)) { LoadingContent("Opening your layout…") }
        else LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { StatusCard("Make it yours — no coding", "Arrange existing tools, hide home shortcuts and change appearance. New tools arrive in app updates. Shop records, receipt branding and checkout calculations stay separate from these controls.") }
            if (state.error != null) item { ErrorNotice(state.error, model::load) }
            if (state.notice != null) item { StatusCard("Customization", state.notice) }
            item {
                Button(onClick = model::save, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("apply-customization")) { Text(if (state.saving) "Saving…" else "Apply changes") }
                Text(if (state.dirty) "Unsaved preview — apply when ready." else "Changes are saved only when you apply them.", style = MaterialTheme.typography.bodySmall)
            }
            item { LayoutPreview(shop, value) }
            item { OutlinedTextField(value.heading, { if (it.length <= 40) model.edit(value.copy(heading = it)) }, label = { Text("Dashboard heading") }, supportingText = { Text("Up to 40 characters") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
            item { PreferenceChoices("Dashboard layout", DashboardLayout.entries, value.layout, { it.title }, !busy) { model.edit(value.copy(layout = it)) } }
            item { PreferenceChoices("Accent color", AppColor.entries, value.color, { it.title }, !busy) { model.edit(value.copy(color = it)) } }
            item { PreferenceChoices("Light or dark", AppAppearance.entries, value.appearance, { it.title }, !busy) { model.edit(value.copy(appearance = it)) } }
            item { PreferenceChoices("Text size", AppTextSize.entries, value.textSize, { it.title }, !busy) { model.edit(value.copy(textSize = it)) }; Text("The phone's accessibility text size is respected too.", style = MaterialTheme.typography.bodySmall) }
            item { PreferenceChoices("Open checkout on", CheckoutStart.entries, value.checkoutStart, { it.title }, !busy) { model.edit(value.copy(checkoutStart = it)) }; Text("Scan, Cart and Products always remain available inside checkout.", style = MaterialTheme.typography.bodySmall) }
            item {
                Text("Arrange dashboard items", style = MaterialTheme.typography.titleLarge)
                Text("Use Up and Down to choose each position. Hiding a shortcut keeps its records and leaves it available in All tools.")
                OutlinedButton(onClick = {
                    val checkout = value.tiles.single { it.tile == DashboardTile.CHECKOUT }.copy(visible = true)
                    model.edit(value.copy(tiles = listOf(checkout) + value.tiles.filter { it.tile != DashboardTile.CHECKOUT }))
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Put Checkout first") }
            }
            itemsIndexed(value.tiles, key = { _, item -> item.tile.name }) { index, item ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${item.tile.title}", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Show on dashboard", Modifier.weight(1f).padding(top = 12.dp))
                            Switch(checked = item.visible, onCheckedChange = { model.tile(item.tile, visible = it) }, enabled = !busy,
                                modifier = Modifier.semantics { contentDescription = "Show ${item.tile.title} on dashboard" })
                        }
                        OutlinedTextField(item.label, { if (it.length <= 24) model.tile(item.tile, label = it) }, label = { Text("Shortcut name: ${item.tile.title}") }, placeholder = { Text(item.tile.title) }, supportingText = { Text("Optional nickname; up to 24 characters") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { model.move(item.tile, -1) }, enabled = !busy && index > 0, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Up: ${item.tile.title}") }
                            OutlinedButton(onClick = { model.move(item.tile, 1) }, enabled = !busy && index < value.tiles.lastIndex, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Down: ${item.tile.title}") }
                        }
                    }
                }
            }
            item { Text("Keep a layout copy", style = MaterialTheme.typography.titleLarge); Text("Layout files contain no products, prices, sales or PIN. Loading a file updates this preview; apply it after reviewing.") }
            item { OutlinedButton(onClick = { exportFile.launch("OfferPoint-layout.json") }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save layout file") } }
            item { OutlinedButton(onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Load layout file") } }
            item { OutlinedButton(onClick = { reset = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Reset customization") } }
            item { Button(onClick = model::save, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Apply changes") } }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Leave the preview?") }, text = { Text("Your unsaved layout changes will be discarded. Shop records are not affected.") }, confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("Discard preview") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset customization?") }, text = { Text("This restores the original layout and appearance in the preview. Apply changes to save it. Shop records and printer/PIN settings are kept.") }, confirmButton = { TextButton(onClick = { reset = false; model.reset() }) { Text("Reset preview") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
}

@Composable
private fun <T> PreferenceChoices(title: String, values: List<T>, selected: T, label: (T) -> String, enabled: Boolean, onChoose: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        for (item in values) FilterChip(selected = selected == item, onClick = { onChoose(item) }, enabled = enabled, label = { Text(label(item)) })
    }
}

@Composable
private fun LayoutPreview(shop: Shop, preferences: WorkspacePreferences) {
    OfferPointTheme(preferences = preferences) {
        Surface(color = MaterialTheme.colorScheme.background, shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Layout preview", style = MaterialTheme.typography.labelLarge)
                Text(preferences.heading, style = MaterialTheme.typography.titleLarge)
                for (row in preferences.dashboardRows()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (item in row) Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.fillMaxWidth().padding(if (preferences.layout == DashboardLayout.COMPACT) 8.dp else 14.dp)) {
                            Text(item.displayName, style = MaterialTheme.typography.titleSmall)
                            if (item.tile == DashboardTile.SHOP) Text(shop.name, style = MaterialTheme.typography.bodySmall)
                            if (item.tile == DashboardTile.TODAY) Text("Your saved sales will appear here", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (preferences.layout == DashboardLayout.GRID && row.size == 1 && row[0].tile.isTool) Spacer(Modifier.weight(1f))
                }
                Text("Preview only · Apply changes when ready", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
