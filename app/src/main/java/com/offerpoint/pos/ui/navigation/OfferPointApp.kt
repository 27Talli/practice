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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.offerpoint.pos.domain.repository.InventoryRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.ShopRepository
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
import com.offerpoint.pos.ui.theme.OfferPointTheme
import kotlinx.coroutines.flow.collect

/** Root UI entry point; startup/seeding is performed once by its retained ViewModel. */
@Composable
fun OfferPointApp(shopRepository: ShopRepository, productRepository: ProductRepository, inventoryRepository: InventoryRepository) {
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
                        composable("dashboard") {
                            val shop = appState.selectedShop
                            if (shop == null) {
                                LaunchedEffect(Unit) { nav.navigate("shops") { popUpTo(nav.graph.id) { inclusive = true } } }
                            } else {
                                DashboardScreen(
                                    shop,
                                    onProducts = { nav.navigate("products/${shop.id}") },
                                    onInventory = { nav.navigate("inventory/${shop.id}") },
                                    onCheckout = { nav.navigate("coming/${shop.id}/Checkout") },
                                    onReports = { nav.navigate("coming/${shop.id}/Reports") },
                                    onSettings = { nav.navigate("settings/${shop.id}") },
                                    onSwitch = { nav.navigate("shops") },
                                )
                            }
                        }
                        composable("products/{shopId}", arguments = listOf(navArgument("shopId") { type = NavType.LongType })) { entry ->
                            val id = entry.arguments?.getLong("shopId") ?: return@composable
                            val shop = appState.shops.firstOrNull { it.id == id } ?: return@composable
                            val vm: ProductsViewModel = viewModel(entry, factory = PosViewModelFactory { ProductsViewModel(productRepository, id) })
                            val state by vm.state.collectAsStateWithLifecycle()
                            ProductsScreen(shop, state, vm::search, vm::retry, { nav.navigate("editor/$id/new") }, { productId -> nav.navigate("editor/$id/$productId") }, { nav.popBackStack() })
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
                            SettingsScreen(shop, appState.databaseStatus, { nav.popBackStack() }, { nav.navigate("shops") })
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
