package com.offerpoint.pos.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.offerpoint.pos.ui.screens.ComingSoonScreen
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
import com.offerpoint.pos.ui.screens.TodaySalesViewModel
import com.offerpoint.pos.ui.labels.ProductLabelScreen
import com.offerpoint.pos.ui.labels.CostCodeSettingsScreen
import com.offerpoint.pos.ui.theme.OfferPointTheme
import kotlinx.coroutines.flow.collect

/** Root UI entry point; startup/seeding is performed once by its retained ViewModel. */
@Composable
fun OfferPointApp(shopRepository: ShopRepository, productRepository: ProductRepository, inventoryRepository: InventoryRepository, salesRepository: SalesRepository, barcodeLabelRepository: BarcodeLabelRepository) {
    OfferPointTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val appViewModel: AppViewModel = viewModel(factory = PosViewModelFactory { AppViewModel(shopRepository) })
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
                                val todayVm: TodaySalesViewModel = viewModel(entry, key = "today-${shop.id}", factory = PosViewModelFactory { TodaySalesViewModel(salesRepository, shop.id) })
                                val today by todayVm.state.collectAsStateWithLifecycle()
                                DashboardScreen(
                                    shop,
                                    today,
                                    onRetryToday = todayVm::retry,
                                    onProducts = { nav.navigate("products/${shop.id}") },
                                    onInventory = { nav.navigate("inventory/${shop.id}") },
                                    onCheckout = { nav.navigate("checkout/${shop.id}") },
                                    onSales = { nav.navigate("sales/${shop.id}") },
                                    onReports = { nav.navigate("coming/${shop.id}/Reports") },
                                    onSettings = { nav.navigate("settings/${shop.id}") },
                                    onSwitch = { nav.navigate("shops") },
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
                                }, onBack = { if (vm.canLeaveCheckout()) nav.popBackStack() })
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
                                onNewSale = {
                                    nav.navigate("checkout/$id") { popUpTo(entry.destination.id) { inclusive = true } }
                                }, onBack = { nav.popBackStack() })
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
                            SettingsScreen(shop, appState.databaseStatus, { nav.popBackStack() }, { nav.navigate("costcodes/$id") }, { nav.navigate("shops") })
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
                        composable("coming/{shopId}/{feature}", arguments = listOf(navArgument("shopId") { type = NavType.LongType }, navArgument("feature") { type = NavType.StringType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            ComingSoonScreen(entry.arguments?.getString("feature") ?: "Feature", shop) { nav.popBackStack() }
                        }
                    }
                }
            }
        }
    }
}
