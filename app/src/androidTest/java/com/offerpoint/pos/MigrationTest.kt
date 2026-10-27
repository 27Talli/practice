package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offerpoint.pos.data.local.database.MIGRATION_1_2
import com.offerpoint.pos.data.local.database.MIGRATION_3_4
import com.offerpoint.pos.data.local.database.MIGRATION_2_3
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.ProductDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the historical KSP-exported v1 JSON and Room's migration/schema validator. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val databaseName = "migration-v1-v2-${System.nanoTime()}.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    @Suppress("DEPRECATION")
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PosDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun cleanUp() { context.deleteDatabase(databaseName) }

    @Test fun migrationPreservesExistingBusinessRecordsAndSupportsTheFirstNewCheckout() = runBlocking {
        // Room creates the real old schema from the exported JSON, not a handwritten facsimile.
        helper.createDatabase(databaseName, 1).apply {
            execSQL("INSERT INTO shops(id, code, name, address, createdAt, updatedAt) VALUES (1, 'OP', 'Offer Point', 'Legacy Offer Point address', 1, 1)")
            execSQL("INSERT INTO shops(id, code, name, address, createdAt, updatedAt) VALUES (2, 'FF', 'Fancy Footwears', 'Legacy Fancy Footwears address', 1, 1)")
            execSQL("INSERT INTO app_settings(id, selectedShopId, demoSeeded) VALUES (1, 2, 0)")
            execSQL("""
                INSERT INTO products(id, sku, name, category, variant, isActive, createdAt, updatedAt)
                VALUES (10, 'OP-000010', 'Legacy perfume', 'Perfumes', 'Rose 50 ml', 1, 1, 1)
            """.trimIndent())
            execSQL("INSERT INTO product_barcodes(id, productId, value, kind, createdAt) VALUES (11, 10, 'OP-000010', 'INTERNAL_CODE128', 1)")
            execSQL("INSERT INTO product_barcodes(id, productId, value, kind, createdAt) VALUES (12, 10, '0000123456789', 'MANUFACTURER', 1)")
            execSQL("""
                INSERT INTO shop_products(shopId, productId, purchaseCostPaise, sellingPricePaise, isActive, createdAt, updatedAt)
                VALUES (1, 10, 12345, 20050, 1, 1, 1)
            """.trimIndent())
            execSQL("INSERT INTO inventory(shopId, productId, quantity, updatedAt) VALUES (1, 10, 7, 2)")
            execSQL("INSERT INTO sku_sequences(prefix, nextValue) VALUES ('OP', 11)")
            execSQL("""
                INSERT INTO sales(id, shopId, receiptNumber, status, subtotalPaise, discountPaise, totalPaise,
                    amountPaidPaise, changePaise, paymentMethod, createdAt, completedAt, updatedAt)
                VALUES (100, 1, 'OP-S-000005', 'COMPLETED', 20050, 0, 20050, 25000, 4950, 'CASH', 2, 2, 2)
            """.trimIndent())
            execSQL("""
                INSERT INTO sale_items(id, saleId, shopId, productId, skuSnapshot, nameSnapshot, variantSnapshot,
                    barcodeSnapshot, quantity, unitPricePaise, unitCostPaise, discountPaise, lineTotalPaise, createdAt)
                VALUES (200, 100, 1, 10, 'OP-000010', 'Legacy perfume at sale', 'Rose 50 ml', '0000123456789', 1, 20050, 12345, 0, 20050, 2)
            """.trimIndent())
            execSQL("""
                INSERT INTO stock_movements(id, shopId, productId, delta, quantityAfter, type, reason, createdAt, saleItemId, returnItemId)
                VALUES (20, 1, 10, 8, 8, 'OPENING', 'Opening stock', 1, NULL, NULL)
            """.trimIndent())
            execSQL("""
                INSERT INTO stock_movements(id, shopId, productId, delta, quantityAfter, type, reason, createdAt, saleItemId, returnItemId)
                VALUES (21, 1, 10, -1, 7, 'SALE', 'OP-S-000005', 2, 200, NULL)
            """.trimIndent())
            execSQL("""
                INSERT INTO audit_events(id, entityType, entityId, shopId, action, beforeValue, afterValue, createdAt)
                VALUES (30, 'PRODUCT', 10, 1, 'CREATE', NULL, '{"name":"Legacy perfume"}', 1)
            """.trimIndent())
            execSQL("INSERT INTO returns(id, saleId, shopId, reason, refundPaise, createdAt) VALUES (300, 100, 1, 'Legacy partial refund', 100, 3)")
            execSQL("""
                INSERT INTO return_items(id, returnId, saleId, shopId, saleItemId, productId, quantity, restockQuantity, unitRefundPaise, refundPaise, createdAt)
                VALUES (400, 300, 100, 1, 200, 10, 1, 0, 100, 100, 3)
            """.trimIndent())
            execSQL("""
                INSERT INTO expenses(id, shopId, amountPaise, category, description, incurredAt, createdAt, updatedAt)
                VALUES (500, 2, 500, 'Utilities', 'Legacy electricity expense', 3, 3, 3)
            """.trimIndent())
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 2, true, MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT checkoutRequestId, requestFingerprint, orderDiscountPaise FROM sales WHERE id = 100").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
                assertEquals(0L, it.getLong(2))
            }
            migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }

        val room = Room.databaseBuilder(context, PosDatabase::class.java, databaseName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
        try {
            val shops = RoomShopRepository(room, false)
            val catalog = RoomProductRepository(room)
            val checkout = RoomSalesRepository(room) { 1_792_987_200_000L }
            shops.initialize()
            assertEquals(2L, shops.observeSelectedShop().first()?.id)
            assertEquals(2, shops.observeShops().first().size)
            assertEquals("Legacy Offer Point address", shops.observeShops().first().single { it.id == 1L }.address)
            val product = requireNotNull(catalog.getProduct(1, 10))
            assertEquals("OP-000010", product.sku)
            assertEquals("Legacy perfume", product.name)
            assertEquals("0000123456789", product.manufacturerBarcode)
            assertEquals(12_345L, product.purchaseCostPaise)
            assertEquals(20_050L, product.sellingPricePaise)
            assertEquals(7L, product.quantity)
            assertEquals(10L, catalog.findByBarcode("0000123456789")?.id)
            assertEquals(2, room.posDao().getMovements(1, 10).size)
            assertEquals(1, room.posDao().getAudits().size)
            assertNull(room.posDao().getSaleSequence(1))
            val legacySale = requireNotNull(checkout.getSale(1, 100))
            assertEquals("OP-S-000005", legacySale.summary.receiptNumber)
            assertEquals("Legacy perfume at sale", legacySale.items.single().name)
            assertEquals(12_345L, legacySale.items.single().unitCostPaise)
            assertEquals(4_950L, legacySale.changePaise)
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM returns WHERE id = 300 AND refundPaise = 100"))
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM return_items WHERE id = 400 AND restockQuantity = 0"))
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM expenses WHERE id = 500 AND amountPaise = 500"))

            val newProduct = catalog.createProduct(1, ProductDraft("New after migration", "Perfumes", purchaseCostPaise = 50, sellingPricePaise = 100))
            assertEquals("OP-000011", catalog.getProduct(1, newProduct)?.sku)
            val request = CheckoutRequest("after-v1-migration", 1, listOf(CheckoutLine(10, 2, 20_050)), amountPaidPaise = 40_100)
            val saved = checkout.completeSale(request)
            assertEquals(40_100L, saved.summary.totalPaise)
            assertEquals(5L, catalog.getProduct(1, 10)?.quantity)
            assertEquals(saved, checkout.completeSale(request))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM sales"))
            assertEquals(3, room.posDao().getMovements(1, 10).size)
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM sales WHERE id = 100 AND receiptNumber = 'OP-S-000005'"))
        } finally {
            room.close()
        }
    }

    private fun scalar(database: PosDatabase, sql: String): Long = database.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst())
        it.getLong(0)
    }
}
