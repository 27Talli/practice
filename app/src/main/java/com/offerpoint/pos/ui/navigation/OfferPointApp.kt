package com.offerpoint.pos.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.offerpoint.pos.domain.model.DashboardTile
import com.offerpoint.pos.domain.model.WorkspacePreferencesSnapshot
import com.offerpoint.pos.domain.repository.WorkspacePreferencesRepository
import com.offerpoint.pos.ui.customize.*
import com.offerpoint.pos.ui.staff.StaffToolsAction
import com.offerpoint.pos.ui.theme.LocalWorkspacePreferences
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.offerpoint.pos.domain.repository.BackupRepository
import com.offerpoint.pos.ui.backup.BackupScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.repository.InventoryRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.ShopRepository
import com.offerpoint.pos.domain.repository.SalesRepository
import com.offerpoint.pos.ui.components.ErrorNotice
import com.offerpoint.pos.ui.screens.AppViewModel
import com.offerpoint.pos.ui.screens.DashboardScreen
import com.offerpoint.pos.ui.screens.InventoryScreen
import com.offerpoint.pos.ui.screens.LoadingContent
import com.offerpoint.pos.ui.screens.PosViewModelFactory
import com.offerpoint.pos.ui.screens.ProductEditorScreen
import com.offerpoint.pos.ui.screens.ProductEditorViewModel
import com.offerpoint.pos.ui.screens.ProductsScreen
import com.offerpoint.pos.ui.screens.ProductsViewModel
import com.offerpoint.pos.ui.screens.SettingsScreen
import com.offerpoint.pos.ui.screens.ShopSelectionScreen
import com.offerpoint.pos.ui.screens.StockAdjustmentViewModel
import com.offerpoint.pos.ui.screens.CheckoutScreen
import com.offerpoint.pos.ui.screens.CheckoutViewModel
import com.offerpoint.pos.ui.screens.SalesHistoryScreen
import com.offerpoint.pos.ui.screens.SalesHistoryViewModel
import com.offerpoint.pos.ui.screens.SaleDetailsScreen
import com.offerpoint.pos.ui.screens.SaleDetailsViewModel
import com.offerpoint.pos.ui.labels.ProductLabelScreen
import com.offerpoint.pos.ui.labels.CostCodeSettingsScreen
import com.offerpoint.pos.ui.theme.OfferPointTheme
import com.offerpoint.pos.domain.repository.ReceiptSettingsRepository
import com.offerpoint.pos.domain.repository.RefundRepository
import com.offerpoint.pos.domain.repository.ExpenseRepository
import com.offerpoint.pos.domain.repository.ReportRepository
import com.offerpoint.pos.domain.repository.SecurityRepository
import com.offerpoint.pos.ui.finance.*
import com.offerpoint.pos.ui.security.SecuritySettingsScreen
import com.offerpoint.pos.ui.receipts.RefundReceiptScreen
import com.offerpoint.pos.ui.receipts.ReceiptScreen
import com.offerpoint.pos.ui.receipts.ReceiptSettingsScreen
import kotlinx.coroutines.flow.collect

private data class WorkspaceGeneration(val value:String?=null,val error:String?=null)

/** Root UI entry point; startup/seeding is performed once by its retained ViewModel. */
@Composable
fun OfferPointApp(shopRepository: ShopRepository, productRepository: ProductRepository, inventoryRepository: InventoryRepository, salesRepository: SalesRepository, barcodeLabelRepository: BarcodeLabelRepository, receiptSettingsRepository: ReceiptSettingsRepository, backupRepository: BackupRepository, refundRepository:RefundRepository, expenseRepository:ExpenseRepository, reportRepository:ReportRepository, securityRepository:SecurityRepository, workspaceRepository:WorkspacePreferencesRepository, onLock:()->Unit = {}) {
    val preferences by remember(workspaceRepository) { workspaceRepository.observe() }
        .collectAsStateWithLifecycle(initialValue = WorkspacePreferencesSnapshot(ready = false))
    CompositionLocalProvider(LocalWorkspacePreferences provides preferences.value) {
        OfferPointTheme {
            WorkspaceRoot(shopRepository, productRepository, inventoryRepository, salesRepository, barcodeLabelRepository, receiptSettingsRepository, backupRepository, refundRepository, expenseRepository, reportRepository, securityRepository, workspaceRepository, onLock, preferences.ready)
        }
    }
}

@Composable
private fun WorkspaceRoot(shopRepository: ShopRepository, productRepository: ProductRepository, inventoryRepository: InventoryRepository, salesRepository: SalesRepository, barcodeLabelRepository: BarcodeLabelRepository, receiptSettingsRepository: ReceiptSettingsRepository, backupRepository: BackupRepository, refundRepository:RefundRepository, expenseRepository:ExpenseRepository, reportRepository:ReportRepository, securityRepository:SecurityRepository, workspaceRepository:WorkspacePreferencesRepository, onLock:()->Unit, preferencesReady: Boolean) {
    var retry by remember { mutableStateOf(0) }
    val generations = remember(backupRepository,retry) { backupRepository.observeGeneration().map { WorkspaceGeneration(value=it) }.catch { emit(WorkspaceGeneration(error=it.message ?: "The local database could not be opened.")) } }
    val generation by generations.collectAsStateWithLifecycle(initialValue=WorkspaceGeneration())
    val holder = rememberSaveableStateHolder()
    var previousGeneration by rememberSaveable { mutableStateOf<String?>(null) }
    if(generation.error != null) { OfferPointTheme { Column(Modifier.padding(24.dp)) { ErrorNotice(generation.error!!,{retry++}) } }; return }
    if (!preferencesReady) { LoadingContent("Opening your app layout…"); return }
    if(generation.value == null) { LoadingContent("Checking the local workspace…"); return }
    val currentGeneration = generation.value!!
    LaunchedEffect(currentGeneration) {
        previousGeneration?.takeIf { it != currentGeneration }?.let(holder::removeState)
        previousGeneration = currentGeneration
    }
    key(currentGeneration) {
        holder.SaveableStateProvider(currentGeneration) {
            OfferPointWorkspace(shopRepository, productRepository, inventoryRepository, salesRepository, barcodeLabelRepository, receiptSettingsRepository, backupRepository, refundRepository, expenseRepository, reportRepository, securityRepository, workspaceRepository, onLock, currentGeneration)
        }
    }
}

@Composable
private fun OfferPointWorkspace(shopRepository: ShopRepository, productRepository: ProductRepository, inventoryRepository: InventoryRepository, salesRepository: SalesRepository, barcodeLabelRepository: BarcodeLabelRepository, receiptSettingsRepository: ReceiptSettingsRepository, backupRepository: BackupRepository, refundRepository:RefundRepository, expenseRepository:ExpenseRepository, reportRepository:ReportRepository, securityRepository:SecurityRepository, workspaceRepository:WorkspacePreferencesRepository, onLock:()->Unit, generation: String) {
    OfferPointTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val appViewModel: AppViewModel = viewModel(key="app-$generation", factory = PosViewModelFactory { AppViewModel(shopRepository) })
            val appState by appViewModel.state.collectAsStateWithLifecycle()
            when {
                appState.loading -> LoadingContent("Opening your local workspace…")
                appState.error != null -> Column(Modifier.padding(24.dp)) { ErrorNotice(appState.error!!, appViewModel::initialize) }
                else -> {
                    val nav = rememberNavController()
                    // Keep the graph stable when a shop-selection Flow emits a new value.
                    val startDestination = remember { if (appState.selectedShop == null) "shops" else "dashboard" }
                    LaunchedEffect(nav, appViewModel) {
                        appViewModel.shopSelected.collect {
                            nav.navigate("dashboard") {
                                popUpTo(nav.graph.id) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    NavHost(navController = nav, startDestination = startDestination) {
                        composable("shops") {
                            ShopSelectionScreen(appState, appViewModel::selectShop, if (appState.selectedShop == null) null else ({ nav.navigate("dashboard") { popUpTo(nav.graph.id) { inclusive = true }; launchSingleTop = true } }))
                        }
                        composable("dashboard") { entry ->
                            val shop = appState.selectedShop
                            if (shop == null) {
                                LaunchedEffect(Unit) { nav.navigate("shops") { popUpTo(nav.graph.id) { inclusive = true } } }
                            } else {
                                val todayVm: TodayNetSalesViewModel = viewModel(entry, key = "today-${shop.id}", factory = PosViewModelFactory { TodayNetSalesViewModel(reportRepository, shop.id) })
                                val today by todayVm.state.collectAsStateWithLifecycle()
                                DashboardScreen(
                                    shop,
                                    today,
                                    onRetryToday = todayVm::retry,
                                    onProducts = { nav.navigate("products/${shop.id}") },
                                    onInventory = { nav.navigate("inventory/${shop.id}") },
                                    onCheckout = { nav.navigate("checkout/${shop.id}") },
                                    onSales = { nav.navigate("sales/${shop.id}") },
                                    onReports = { nav.navigate("reports/${shop.id}") },
                                    onRefunds = { nav.navigate("refunds/${shop.id}") },
                                    onExpenses = { nav.navigate("expenses/${shop.id}") },
                                    onSettings = { nav.navigate("settings/${shop.id}") },
                                    onSwitch = { nav.navigate("shops") },
                                    onTools = { nav.navigate("tools/${shop.id}") },
                                    onCustomize = { nav.navigate("customize/${shop.id}") },
                                    staffActions = { StaffToolsAction(shop, productRepository, barcodeLabelRepository) },
                                )
                            }
                        }
                        composable("checkout/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val vm: CheckoutViewModel = viewModel(entry, factory = viewModelFactory {
                                initializer { CheckoutViewModel(productRepository, salesRepository, id, createSavedStateHandle(), barcodeLabels = barcodeLabelRepository) }
                            })
                            val state by vm.state.collectAsStateWithLifecycle()
                            CheckoutScreen(shop, state, vm::search, vm::retryProducts, vm::add, vm::scanBarcode, vm::scanNextItem, vm::canLeaveCheckout, vm::editLine,
                                vm::remove, vm::setOrderDiscount, vm::setCashReceived, vm::exactCash, vm::refreshCart,
                                vm::pay, vm::recoverPending,
                                onSaved = { saleId ->
                                    nav.navigate("sale/$id/$saleId?completed=true") {
                                        popUpTo(entry.destination.id) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }, onBack = { if (vm.canLeaveCheckout()) nav.popBackStack() },
                                staffActions = { onVisible -> StaffToolsAction(shop, productRepository, barcodeLabelRepository,
                                    cart = state.cart.map { it.requestLine() }, orderDiscount = state.orderDiscount, enabled = !state.locked,
                                    onVisibilityChanged = { visible -> if (visible) vm.pauseScanningForStaff(); onVisible(visible) }) })
                        }
                        composable("sales/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val vm: SalesHistoryViewModel = viewModel(entry, factory = PosViewModelFactory { SalesHistoryViewModel(salesRepository, id) })
                            val state by vm.state.collectAsStateWithLifecycle()
                            SalesHistoryScreen(shop, state, vm::retry, { saleId -> nav.navigate("sale/$id/$saleId?completed=false") }, { nav.popBackStack() })
                        }
                        composable("sale/{shopId}/{saleId}?completed={completed}", arguments = listOf(
                            navArgument("shopId") { type = NavType.LongType },
                            navArgument("saleId") { type = NavType.LongType },
                            navArgument("completed") { type = NavType.BoolType; defaultValue = false },
                        )) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val saleId = entry.arguments?.getLong("saleId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val vm: SaleDetailsViewModel = viewModel(entry, factory = PosViewModelFactory { SaleDetailsViewModel(salesRepository, id, saleId) })
                            val state by vm.state.collectAsStateWithLifecycle()
                            SaleDetailsScreen(shop, state, entry.arguments?.getBoolean("completed") == true, vm::load,
                                onReceipt = { nav.navigate("receipt/$id/$saleId") },
                                onNewSale = {
                                    nav.navigate("checkout/$id") { popUpTo(entry.destination.id) { inclusive = true } }
                                }, onBack = { nav.popBackStack() },
                                staffActions = { StaffToolsAction(shop, productRepository, barcodeLabelRepository, sale = state.sale, enabled = state.sale != null) })
                        }
                        composable("refund/{shopId}/{saleId}",arguments=listOf(navArgument("shopId"){type=NavType.LongType},navArgument("saleId"){type=NavType.LongType})){entry->
                            val id=entry.arguments?.getLong("shopId") ?: return@composable
                            val saleId=entry.arguments?.getLong("saleId") ?: return@composable
                            val shop=appState.shops.firstOrNull{it.id==id} ?: return@composable
                            val vm:RefundViewModel=viewModel(entry,factory=viewModelFactory{initializer{RefundViewModel(refundRepository,id,saleId,createSavedStateHandle())}})
                            val state by vm.state.collectAsStateWithLifecycle()
                            RefundScreen(shop,state,vm::edit,vm::reason,vm::post,vm::recover,{returnId->nav.navigate("refund-receipt/$id/$returnId"){popUpTo(entry.destination.id){inclusive=true};launchSingleTop=true}},{if(!state.busy&&!state.recoveryRequired)nav.popBackStack()})
                        }
                        composable("refunds/{shopId}",arguments=listOf(navArgument("shopId"){type=NavType.LongType})){entry->
                            val id=entry.arguments?.getLong("shopId") ?: return@composable
                            val shop=appState.shops.firstOrNull{it.id==id} ?: return@composable
                            val vm: RefundHistoryViewModel = viewModel(entry, factory = viewModelFactory { initializer { RefundHistoryViewModel(refundRepository, salesRepository, shop, createSavedStateHandle()) } })
                            val state by vm.state.collectAsStateWithLifecycle()
                            RefundHistoryScreen(shop,state,vm::reference,vm::find,vm::scanned,
                                onReturn={vm.state.value.sale?.let{sale->nav.navigate("refund/$id/${sale.summary.id}")}},onRetry=vm::retry,
                                onOpen={returnId->nav.navigate("refund-receipt/$id/$returnId")},onBack={nav.popBackStack()})
                        }
                        composable("refund-receipt/{shopId}/{returnId}",arguments=listOf(navArgument("shopId"){type=NavType.LongType},navArgument("returnId"){type=NavType.LongType})){entry->
                            val id=entry.arguments?.getLong("shopId") ?: return@composable
                            val shop=appState.shops.firstOrNull{it.id==id} ?: return@composable
                            RefundReceiptScreen(shop,entry.arguments?.getLong("returnId") ?: return@composable,refundRepository,receiptSettingsRepository){nav.popBackStack()}
                        }
                        composable("expenses/{shopId}",arguments=listOf(navArgument("shopId"){type=NavType.LongType})){entry->
                            val id=entry.arguments?.getLong("shopId") ?: return@composable
                            val shop=appState.shops.firstOrNull{it.id==id} ?: return@composable
                            val vm:ExpenseViewModel=viewModel(entry,factory=viewModelFactory{initializer{ExpenseViewModel(expenseRepository,id,createSavedStateHandle())}})
                            val state by vm.state.collectAsStateWithLifecycle()
                            ExpenseScreen(shop,state,vm::fields,vm::add,vm::recover,vm::void){if(!state.busy&&!state.recoveryRequired)nav.popBackStack()}
                        }
                        composable("reports/{shopId}",arguments=listOf(navArgument("shopId"){type=NavType.LongType})){entry->
                            val id=entry.arguments?.getLong("shopId") ?: return@composable
                            val shop=appState.shops.firstOrNull{it.id==id} ?: return@composable
                            val vm:ReportViewModel=viewModel(entry,factory=PosViewModelFactory{ReportViewModel(reportRepository,id)})
                            val state by vm.state.collectAsStateWithLifecycle()
                            ReportsScreen(shop,state,vm::load){nav.popBackStack()}
                        }
                        composable("customize/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val vm: WorkspaceCustomizationViewModel = viewModel(entry, factory = viewModelFactory {
                                initializer { WorkspaceCustomizationViewModel(workspaceRepository, createSavedStateHandle()) }
                            })
                            val state by vm.state.collectAsStateWithLifecycle()
                            WorkspaceCustomizationScreen(shop, vm, state) { nav.popBackStack() }
                        }
                        composable("tools/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            AllToolsScreen(shop, { tile ->
                                val route = when (tile) {
                                    DashboardTile.PRODUCTS -> "products"
                                    DashboardTile.INVENTORY -> "inventory"
                                    DashboardTile.CHECKOUT -> "checkout"
                                    DashboardTile.SALES -> "sales"
                                    DashboardTile.RETURNS -> "refunds"
                                    DashboardTile.EXPENSES -> "expenses"
                                    DashboardTile.REPORTS -> "reports"
                                    else -> null
                                }
                                if (route != null) nav.navigate("$route/$id")
                            }, { nav.navigate("customize/$id") }, { nav.popBackStack() })
                        }
                        composable("security"){SecuritySettingsScreen(securityRepository,onLock){nav.popBackStack()}}
                        composable("receipt/{shopId}/{saleId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType }, navArgument("saleId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val saleId = entry.arguments?.getLong("saleId") ?: return@composable
                            ReceiptScreen(shop, saleId, salesRepository, receiptSettingsRepository) { nav.popBackStack() }
                        }
                        composable("backups") { BackupScreen(backupRepository) { nav.popBackStack() } }
                        composable("receipt-settings/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            ReceiptSettingsScreen(shop, receiptSettingsRepository) { nav.popBackStack() }
                        }
                        composable("products/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val vm: ProductsViewModel = viewModel(entry, factory = PosViewModelFactory { ProductsViewModel(productRepository, id) })
                            val state by vm.state.collectAsStateWithLifecycle()
                            ProductsScreen(shop, state, vm::search, vm::retry, { nav.navigate("editor/$id/new") }, { productId -> nav.navigate("editor/$id/$productId") }, { productId -> nav.navigate("label/$id/$productId") }, { nav.popBackStack() })
                        }
                        composable("editor/{shopId}/{productId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType }, navArgument("productId") { type = NavType.StringType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val rawId = entry.arguments?.getString("productId")
                            val productId = if (rawId == "new") null else rawId?.toLongOrNull()
                            val vm: ProductEditorViewModel = viewModel(entry, factory = PosViewModelFactory { ProductEditorViewModel(productRepository, id, productId) })
                            val state by vm.state.collectAsStateWithLifecycle()
                            ProductEditorScreen(shop, productId != null, state, vm::findExisting, vm::clearMatch, vm::load, vm::save, { nav.popBackStack() }, { nav.popBackStack() })
                        }
                        composable("inventory/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val listVm: ProductsViewModel = viewModel(entry, factory = PosViewModelFactory { ProductsViewModel(productRepository, id) })
                            val stockVm: StockAdjustmentViewModel = viewModel(entry, factory = PosViewModelFactory { StockAdjustmentViewModel(inventoryRepository, id) })
                            val state by listVm.state.collectAsStateWithLifecycle()
                            val adjustment by stockVm.state.collectAsStateWithLifecycle()
                            InventoryScreen(shop, state, adjustment, listVm::search, listVm::retry, stockVm::adjust, stockVm::clearError, { nav.navigate("editor/$id/new") }, { nav.popBackStack() })
                        }
                        composable("settings/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            SettingsScreen(shop, appState.databaseStatus, { nav.popBackStack() }, { nav.navigate("costcodes/$id") }, { nav.navigate("receipt-settings/$id") }, { nav.navigate("backups") }, { nav.navigate("security") }, { nav.navigate("shops") }, { nav.navigate("customize/$id") })
                        }
                        composable("label/{shopId}/{productId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType }, navArgument("productId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val productId = entry.arguments?.getLong("productId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            ProductLabelScreen(shop, productId, barcodeLabelRepository) { nav.popBackStack() }
                        }
                        composable("costcodes/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            CostCodeSettingsScreen(shop, barcodeLabelRepository) { nav.popBackStack() }
                        }

                    }
                }
            }
        }
    }
}
