package com.offerpoint.pos.ui.customize

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.Money
import com.offerpoint.pos.ui.components.*
import com.offerpoint.pos.ui.screens.TodaySalesState

@Composable
fun CustomizedDashboard(
    shop: Shop,
    today: TodaySalesState,
    preferences: WorkspacePreferences,
    padding: PaddingValues,
    onRetry: () -> Unit,
    onOpen: (DashboardTile) -> Unit,
    onTools: () -> Unit,
    onCustomize: () -> Unit,
) {
    val rows = preferences.dashboardRows()
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text(preferences.heading, style = MaterialTheme.typography.headlineMedium) }
        items(rows, key = { row -> row.joinToString("-") { it.tile.name } }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (item in row) {
                    Box(Modifier.weight(1f)) {
                        when (item.tile) {
                            DashboardTile.SHOP -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(item.displayName, style = MaterialTheme.typography.labelLarge)
                                    Text(shop.name, style = MaterialTheme.typography.headlineMedium)
                                    Text(shop.address)
                                    Text("● Offline ready", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            DashboardTile.TODAY -> when {
                                today.loading -> StatusCard(item.displayName, "Loading saved activity…")
                                today.error != null -> ErrorNotice(today.error, onRetry)
                                else -> today.sales?.let { sales ->
                                    StatusCard(item.displayName, "Net sales · Pakistan time\n${Money.format(sales.totalPaise)}\n${sales.transactionCount} completed ${if (sales.transactionCount == 1) "sale" else "sales"}")
                                }
                            }
                            else -> DashboardTool(item, preferences.layout) { onOpen(item.tile) }
                        }
                    }
                }
                if (preferences.layout == DashboardLayout.GRID && row.size == 1 && row[0].tile.isTool) Spacer(Modifier.weight(1f))
            }
        }
        if (rows.isEmpty()) item { EmptyState("Your dashboard is clear", "Open All tools to use any feature, or Customize app to show shortcuts again.") }
        item {
            OutlinedButton(onClick = onTools, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("All tools") }
            TextButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Customize app") }
        }
        item { Text("Saved locally on this device · No account required", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun DashboardTool(item: DashboardTilePreference, layout: DashboardLayout, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        if (layout == DashboardLayout.GRID) {
            Column(Modifier.fillMaxWidth().heightIn(min = 126.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                if (item.label.isNotBlank() && item.displayName != item.tile.title) Text(item.tile.title, style = MaterialTheme.typography.bodySmall)
                Text(item.tile.action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(if (layout == DashboardLayout.COMPACT) 14.dp else 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                    if (layout != DashboardLayout.COMPACT || item.label.isNotBlank()) Text(if (layout == DashboardLayout.COMPACT) item.tile.title else item.tile.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(item.tile.action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun AllToolsScreen(shop: Shop, onOpen: (DashboardTile) -> Unit, onCustomize: () -> Unit, onBack: () -> Unit) {
    com.offerpoint.pos.ui.screens.PosScaffold("All tools", shop.name, onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Every tool is available here, including shortcuts hidden from your dashboard.") }
            items(DashboardTile.entries.filter { it.isTool }, key = { it.name }) { tile ->
                DashboardTool(DashboardTilePreference(tile), DashboardLayout.CARDS) { onOpen(tile) }
            }
            item { OutlinedButton(onClick = onCustomize, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("Customize app") } }
        }
    }
}
