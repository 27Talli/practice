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
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.RoomRefundRepository
import com.offerpoint.pos.data.repository.RoomExpenseRepository
import com.offerpoint.pos.data.repository.RoomReportRepository
import com.offerpoint.pos.data.repository.RoomSecurityRepository
import com.offerpoint.pos.data.repository.RoomInventoryRepository
import com.offerpoint.pos.data.repository.RoomBarcodeLabelRepository
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.data.repository.RoomBackupRepository
import com.offerpoint.pos.data.repository.RoomReceiptSettingsRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.ui.navigation.OfferPointApp
import com.offerpoint.pos.ui.security.SecureWorkspace
import com.offerpoint.pos.ui.security.SecurityViewModel
import com.offerpoint.pos.ui.theme.OfferPointTheme
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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

    @Test fun changingDatasetGenerationClearsNavigationAndUnpaidCartState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backups = RoomBackupRepository(context,database,false)
        compose.setContent { OfferPointApp(shops,products,inventory,sales,labels,RoomReceiptSettingsRepository(database),backups,RoomRefundRepository(database),RoomExpenseRepository(database),RoomReportRepository(database),RoomSecurityRepository(database)) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Your retail workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Checkout"))
        compose.onNodeWithText("Checkout").performClick();compose.onNodeWithText("Products").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("+ Add").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("Review cart",substring=true).performClick()
        runBlocking { val settings=requireNotNull(database.posDao().getSettings());database.posDao().upsertSettings(settings.copy(dataGeneration="restored-test-dataset")) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Your retail workspace").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Checkout"))
        compose.onNodeWithText("Checkout").performClick()
        compose.onNodeWithText("Cart (0)").performClick()
        compose.onNodeWithText("Your cart is empty").assertExists()
        runBlocking { assertEquals(0,database.posDao().countSales());assertEquals(3L,products.getProduct(1,productId)?.quantity) }
    }

    @Test fun cashierCanCompleteACashSaleAndReopenItsSavedHistory() {
        compose.setContent { OfferPointApp(shops, products, inventory, sales, labels, RoomReceiptSettingsRepository(database), RoomBackupRepository(ApplicationProvider.getApplicationContext(), database, false),RoomRefundRepository(database),RoomExpenseRepository(database),RoomReportRepository(database),RoomSecurityRepository(database)) }
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
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("View / print receipt"))
        compose.onNodeWithText("View / print receipt").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Receipt preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Share receipt PDF"))
        compose.onNodeWithText("Share receipt PDF").assertIsEnabled()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Print receipt"))
        compose.onNodeWithText("Print receipt").assertIsEnabled()
        compose.onNodeWithText("‹ Back").performClick()
        runBlocking {
            assertEquals(1, database.posDao().countSales())
            assertEquals(2L, products.getProduct(1, productId)?.quantity)
        }
    }

    @Test fun cashierCanReturnASavedSaleAndReopenTheRefundReceipt() {
        runBlocking { sales.completeSale(CheckoutRequest("ui-refund-sale",1,listOf(CheckoutLine(productId,1,500)),0,500)) }
        val refunds=RoomRefundRepository(database)
        compose.setContent { OfferPointApp(shops,products,inventory,sales,labels,RoomReceiptSettingsRepository(database),RoomBackupRepository(ApplicationProvider.getApplicationContext(),database,false),refunds,RoomExpenseRepository(database),RoomReportRepository(database),RoomSecurityRepository(database)) }
        compose.waitUntil(15_000){compose.onAllNodesWithText("Your retail workspace").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sales history"));compose.onNodeWithText("Sales history").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("OP-S-000001").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("OP-S-000001").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Return items / refund"));compose.onNodeWithText("Return items / refund").performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("Quantity to return").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Quantity to return").performTextReplacement("1")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Quantity to restock"));compose.onNodeWithText("Quantity to restock").performTextReplacement("1")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Return reason (required)"));compose.onNodeWithText("Return reason (required)").performTextReplacement("Wrong colour")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Confirm cash refund"));compose.onNodeWithText("Confirm cash refund").assertIsEnabled().performClick()
        compose.waitUntil(15_000){compose.onAllNodesWithText("Receipt preview").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("CASH REFUND RECEIPT"));compose.onNodeWithText("CASH REFUND RECEIPT").assertExists()
        runBlocking {assertEquals(1,refunds.observe(1).first().size);assertEquals(3L,products.getProduct(1,productId)?.quantity);assertEquals(1,database.posDao().countSales())}
    }

    @Test fun localPinLocksTheWorkspaceAndUnlockingPreservesTheUnpaidCart() {
        val context=ApplicationProvider.getApplicationContext<Context>();val alias="ui-pin-${System.nanoTime()}"
        val security=RoomSecurityRepository(database,alias);val backups=RoomBackupRepository(context,database,false)
        runBlocking {security.configure(charArrayOf(),"123456".toCharArray(),2)}
        val model=SecurityViewModel(security,backups)
        try {
            compose.setContent { OfferPointTheme {SecureWorkspace(model){OfferPointApp(shops,products,inventory,sales,labels,RoomReceiptSettingsRepository(database),backups,RoomRefundRepository(database),RoomExpenseRepository(database),RoomReportRepository(database),security,model::lock)}} }
            compose.waitUntil(15_000){compose.onAllNodesWithText("App PIN").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("App PIN").performTextReplacement("123456");compose.onNodeWithText("Unlock").performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText("Your retail workspace").fetchSemanticsNodes().isNotEmpty()}
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Checkout"));compose.onNodeWithText("Checkout").performClick();compose.onNodeWithText("Products").performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText("+ Add").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("+ Add").performClick();compose.onNodeWithText("Review cart",substring=true).performClick()
            compose.runOnIdle {model.lock()}
            compose.waitUntil(15_000){compose.onAllNodesWithText("App PIN").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("App PIN").performTextReplacement("123456");compose.onNodeWithText("Unlock").performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText("Cart (1)").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("UI test lipstick").assertExists()
            runBlocking {assertEquals(0,database.posDao().countSales());assertEquals(3L,products.getProduct(1,productId)?.quantity)}
        }finally{model.viewModelScope.cancel();java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}}
    }
}
