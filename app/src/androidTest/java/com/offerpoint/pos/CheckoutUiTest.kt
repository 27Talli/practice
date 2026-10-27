package com.offerpoint.pos

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.RoomInventoryRepository
import com.offerpoint.pos.data.repository.RoomBarcodeLabelRepository
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.ui.navigation.OfferPointApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/** Drive the real Compose navigation and Room checkout without touching an installed shop database. */
@RunWith(AndroidJUnit4::class)
class CheckoutUiTest {
    private val compose = createComposeRule()
    private lateinit var database: PosDatabase
    private lateinit var shops: RoomShopRepository
    private lateinit var products: RoomProductRepository
    private lateinit var inventory: RoomInventoryRepository
    private lateinit var sales: RoomSalesRepository
    private lateinit var labels: RoomBarcodeLabelRepository
    private var productId = 0L

    private val isolatedDatabase = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            database = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).build()
            shops = RoomShopRepository(database, false)
            products = RoomProductRepository(database)
            inventory = RoomInventoryRepository(database)
            sales = RoomSalesRepository(database)
            labels = RoomBarcodeLabelRepository(database)
            runBlocking {
                shops.initialize()
                shops.selectShop(1)
                productId = products.createProduct(1, ProductDraft(
                    "UI test lipstick", "Cosmetics", purchaseCostPaise = 300,
                    sellingPricePaise = 500, openingQuantity = 3,
                ))
            }
        }

        override fun after() {
            // Outer rule closes after Compose disposes its content and its Activity.
            if (::database.isInitialized) database.close()
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(isolatedDatabase).around(compose)

    @Test fun cashierCanCompleteACashSaleAndReopenItsSavedHistory() {
        compose.setContent { OfferPointApp(shops, products, inventory, sales, labels) }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Your retail workspace").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Checkout"))
        compose.onNodeWithText("Checkout").performClick()
        compose.onNodeWithText("Products").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("+ Add").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("Review cart", substring = true).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Exact cash"))
        compose.onNodeWithText("Exact cash").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Complete cash sale"))
        compose.onNodeWithText("Complete cash sale").assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Sale completed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("OP-S-000001", substring = true).assertExists()
        runBlocking {
            assertEquals(2L, products.getProduct(1, productId)?.quantity)
            assertEquals(1, database.posDao().countSales())
        }
        compose.onNodeWithText("‹ Back").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Your retail workspace").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sales history"))
        compose.onNodeWithText("Sales history").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("OP-S-000001").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("OP-S-000001").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Saved transaction").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("UI test lipstick").assertExists()
        runBlocking { assertEquals(1, database.posDao().countSales()) }
    }
}
