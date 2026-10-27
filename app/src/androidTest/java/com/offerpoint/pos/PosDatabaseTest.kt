package com.offerpoint.pos

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.RoomInventoryRepository
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.BarcodeConflictException
import com.offerpoint.pos.domain.model.ProductDraft
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's SQLite implementation; all test data stays on the device. */
@RunWith(AndroidJUnit4::class)
class PosDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: PosDatabase
    private lateinit var shops: RoomShopRepository
    private lateinit var products: RoomProductRepository
    private lateinit var inventory: RoomInventoryRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).build()
        shops = RoomShopRepository(database, demoMode = false)
        products = RoomProductRepository(database)
        inventory = RoomInventoryRepository(database)
        shops.initialize()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun firstLaunchSeedsExactlyTwoRealShopsAndNoProducts() = runBlocking {
        shops.initialize()
        val seeded = shops.observeShops().first()
        assertEquals(2, seeded.size)
        assertEquals(setOf("Offer Point", "Fancy Footwears"), seeded.map { it.name }.toSet())
        assertTrue(seeded.all { "Mardan" in it.address })
        assertEquals(OP, shops.observeSelectedShop().first()?.id)
        assertTrue(products.observeProducts(OP).first().isEmpty())
        assertTrue(products.observeProducts(FF).first().isEmpty())
    }

    @Test
    fun selectionValidatesShopAndSurvivesRepositoryRecreation() = runBlocking {
        shops.selectShop(FF)
        assertEquals(FF, RoomShopRepository(database, false).observeSelectedShop().first()?.id)
        expectFailure { shops.selectShop(999_999L) }
        assertEquals(FF, shops.observeSelectedShop().first()?.id)
    }

    @Test
    fun insertingProductRetainsExactMoneyBarcodeAndOpeningStock() = runBlocking {
        val id = products.createProduct(OP, draft(barcode = "0001234567890", opening = 12))
        val stored = requireNotNull(products.getProduct(OP, id))
        assertEquals("Rose perfume", stored.name)
        assertEquals("Perfumes", stored.category)
        assertEquals("50 ml", stored.variant)
        assertEquals("0001234567890", stored.manufacturerBarcode)
        assertEquals(12_345L, stored.purchaseCostPaise)
        assertEquals(20_050L, stored.sellingPricePaise)
        assertEquals(12L, stored.quantity)
        assertTrue(stored.sku.startsWith("OP-"))
        assertEquals(id, products.findByBarcode(stored.sku)?.id)
        assertEquals(id, products.findByBarcode("0001234567890")?.id)
        assertNull(products.getProduct(FF, id))
        assertEquals(1, database.posDao().getMovements(OP, id).size)
    }

    @Test
    fun productEditChangesDetailsAndPricesWithoutReplacingStock() = runBlocking {
        val id = products.createProduct(OP, draft(opening = 7))
        val original = requireNotNull(products.getProduct(OP, id))
        val auditCount = database.posDao().getAudits().size
        products.updateProduct(
            OP, id,
            draft(barcode = "9988776655", opening = 0).copy(
                name = "Rose perfume premium", category = "Beauty", variant = "100 ml",
                purchaseCostPaise = 15_005, sellingPricePaise = 25_099,
            ),
        )
        val edited = requireNotNull(products.getProduct(OP, id))
        assertEquals(original.sku, edited.sku)
        assertEquals("Rose perfume premium", edited.name)
        assertEquals("Beauty", edited.category)
        assertEquals("100 ml", edited.variant)
        assertEquals("9988776655", edited.manufacturerBarcode)
        assertEquals(15_005L, edited.purchaseCostPaise)
        assertEquals(25_099L, edited.sellingPricePaise)
        assertEquals(7L, edited.quantity)
        assertEquals(1, database.posDao().getMovements(OP, id).size)
        assertTrue(database.posDao().getAudits().size > auditCount)
    }

    @Test
    fun explicitAssociationSharesIdentityButKeepsPricesAndStockSeparate() = runBlocking {
        val id = products.createProduct(OP, draft(opening = 9))
        products.associateProduct(FF, id, 16_000, 24_000, 3)
        products.updateProduct(OP, id, draft(opening = 0).copy(name = "Shared perfume", sellingPricePaise = 22_000))
        val op = requireNotNull(products.getProduct(OP, id))
        val ff = requireNotNull(products.getProduct(FF, id))
        assertEquals("Shared perfume", ff.name)
        assertEquals(op.sku, ff.sku)
        assertEquals(22_000L, op.sellingPricePaise)
        assertEquals(24_000L, ff.sellingPricePaise)
        assertEquals(9L, op.quantity)
        assertEquals(3L, ff.quantity)
        expectFailure { products.associateProduct(FF, id, 1, 1, 100) }
        assertEquals(3L, products.getProduct(FF, id)?.quantity)
    }

    @Test
    fun productListsAndSearchStayWithinTheSelectedShop() = runBlocking {
        val opId = products.createProduct(OP, draft(barcode = "OP-MANUFACTURER"))
        val ffId = products.createProduct(FF, draft(name = "Blue sandal", barcode = "FF-MANUFACTURER"))
        assertEquals(listOf(opId), products.observeProducts(OP).first().map { it.id })
        assertEquals(listOf(ffId), products.observeProducts(FF).first().map { it.id })
        assertTrue(products.observeProducts(OP, "Blue sandal").first().isEmpty())
        assertEquals(opId, products.observeProducts(OP, "rose").first().single().id)
        assertEquals(opId, products.observeProducts(OP, "Perfumes").first().single().id)
        assertEquals(opId, products.observeProducts(OP, "50 ml").first().single().id)
        assertEquals(opId, products.observeProducts(OP, "OP-MANUFACTURER").first().single().id)
        val sku = requireNotNull(products.getProduct(OP, opId)).sku
        assertEquals(opId, products.observeProducts(OP, sku).first().single().id)
        assertTrue(products.observeProducts(OP, "%_").first().isEmpty())
    }

    @Test
    fun manufacturerBarcodeIsGloballyUniqueAndFailedInsertLeavesNoPartialProduct() = runBlocking {
        val first = products.createProduct(OP, draft(barcode = "0123456789"))
        val beforeOp = products.observeProducts(OP).first()
        val failure = expectFailure {
            products.createProduct(FF, draft(name = "Another item", barcode = "0123456789", opening = 99))
        }
        assertTrue(failure is BarcodeConflictException)
        assertEquals(beforeOp, products.observeProducts(OP).first())
        assertTrue(products.observeProducts(FF).first().isEmpty())
        assertEquals(first, products.findByBarcode("0123456789")?.id)
    }

    @Test
    fun manufacturerBarcodeCannotCollideWithAnotherProductsInternalSku() = runBlocking {
        val first = products.createProduct(OP, draft())
        val sku = requireNotNull(products.getProduct(OP, first)).sku
        val failure = expectFailure { products.createProduct(FF, draft(barcode = sku)) }
        assertTrue(failure is BarcodeConflictException)
        assertTrue(products.observeProducts(FF).first().isEmpty())
    }

    @Test
    fun sqliteItselfRejectsDuplicateBarcodesEvenWhenRepositoryIsBypassed() = runBlocking {
        val first = products.createProduct(OP, draft(barcode = "DB-UNIQUE-001"))
        val second = products.createProduct(FF, draft(barcode = "DB-UNIQUE-002"))
        val failure = expectFailure {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO product_barcodes(productId, value, kind, createdAt) VALUES (?, ?, ?, ?)",
                arrayOf(second, "DB-UNIQUE-001", "TEST_OTHER_KIND", 1L),
            )
        }
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(first, products.findByBarcode("DB-UNIQUE-001")?.id)
        assertEquals(second, products.findByBarcode("DB-UNIQUE-002")?.id)
    }

    @Test
    fun failedBarcodeEditRollsBackSharedMetadataAndShopPrice() = runBlocking {
        products.createProduct(OP, draft(barcode = "111111"))
        val secondId = products.createProduct(OP, draft(name = "Second perfume", barcode = "222222"))
        val before = requireNotNull(products.getProduct(OP, secondId))
        val auditCount = database.posDao().getAudits().size
        expectFailure {
            products.updateProduct(OP, secondId, draft(name = "Should roll back", barcode = "111111").copy(sellingPricePaise = 1))
        }
        assertEquals(before, products.getProduct(OP, secondId))
        assertEquals(secondId, products.findByBarcode("222222")?.id)
        assertEquals(auditCount, database.posDao().getAudits().size)
    }

    @Test
    fun stockAdjustmentRecordsMovementAndReasonAndNeverChangesAnotherShop() = runBlocking {
        val id = products.createProduct(OP, draft(opening = 8))
        products.associateProduct(FF, id, 100, 200, 5)
        inventory.adjustStock(OP, id, -3, "Damaged packaging")
        assertEquals(5L, products.getProduct(OP, id)?.quantity)
        assertEquals(5L, products.getProduct(FF, id)?.quantity)
        assertEquals(2, database.posDao().getMovements(OP, id).size)
        assertEquals(1, database.posDao().getMovements(FF, id).size)
        val adjustment = database.posDao().getMovements(OP, id).single { it.type == "ADJUSTMENT" }
        assertEquals(-3L, adjustment.delta)
        assertEquals(5L, adjustment.quantityAfter)
        assertEquals("Damaged packaging", adjustment.reason)
        inventory.adjustStock(FF, id, 2, "Count correction")
        assertEquals(5L, products.getProduct(OP, id)?.quantity)
        assertEquals(7L, products.getProduct(FF, id)?.quantity)
    }

    @Test
    fun invalidAdjustmentsLeaveBothQuantityAndMovementHistoryUnchanged() = runBlocking {
        val id = products.createProduct(OP, draft(opening = 2))
        expectFailure { inventory.adjustStock(OP, id, -3, "Too many") }
        expectFailure { inventory.adjustStock(OP, id, 1, "   ") }
        expectFailure { inventory.adjustStock(OP, id, 0, "No change") }
        expectFailure { inventory.adjustStock(FF, id, 1, "Wrong shop") }
        assertEquals(2L, products.getProduct(OP, id)?.quantity)
        assertEquals(1, database.posDao().getMovements(OP, id).size)
        inventory.adjustStock(OP, id, -2, "Counted empty")
        assertEquals(0L, products.getProduct(OP, id)?.quantity)
    }

    @Test
    fun ledgerInsertFailureRollsBackTheInventoryUpdate() = runBlocking {
        val id = products.createProduct(OP, draft(opening = 6))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            """
            CREATE TRIGGER test_fail_stock_movement
            BEFORE INSERT ON stock_movements
            WHEN NEW.reason = 'force-ledger-failure'
            BEGIN SELECT RAISE(ABORT, 'test ledger failure'); END
            """.trimIndent(),
        )
        try {
            expectFailure { inventory.adjustStock(OP, id, -2, "force-ledger-failure") }
            assertEquals(6L, products.getProduct(OP, id)?.quantity)
            assertEquals(1, database.posDao().getMovements(OP, id).size)
        } finally {
            sqlite.execSQL("DROP TRIGGER test_fail_stock_movement")
        }
    }

    @Test
    fun stockAdditionRejectsIntegerOverflowWithoutChangingHistory() = runBlocking {
        val id = products.createProduct(OP, draft(opening = Long.MAX_VALUE))
        expectFailure { inventory.adjustStock(OP, id, 1, "Must not overflow") }
        assertEquals(Long.MAX_VALUE, products.getProduct(OP, id)?.quantity)
        assertEquals(1, database.posDao().getMovements(OP, id).size)
    }

    @Test
    fun databaseForeignKeysRejectSaleItemsFromADifferentShop() = runBlocking {
        val id = products.createProduct(OP, draft())
        products.associateProduct(FF, id, 100, 200, 1)
        insertSale(1001, OP)
        val failure = expectFailure { insertSaleItem(2001, 1001, FF, id) }
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(0L, tableCount("sale_items"))
    }

    @Test
    fun databaseForeignKeysRejectReturnsForADifferentShop() = runBlocking {
        insertSale(1001, OP)
        val failure = expectFailure { insertReturn(3001, 1001, FF) }
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(0L, tableCount("returns"))
    }

    @Test
    fun databaseForeignKeysKeepReturnItemsWithinTheOriginalSale() = runBlocking {
        val id = products.createProduct(OP, draft())
        insertSale(1001, OP)
        insertSale(1002, OP)
        insertSaleItem(2001, 1001, OP, id)
        insertSaleItem(2002, 1002, OP, id)
        insertReturn(3001, 1001, OP)
        val failure = expectFailure {
            database.openHelper.writableDatabase.execSQL(
                """
                INSERT INTO return_items(
                    id, returnId, saleId, shopId, saleItemId, productId, quantity,
                    restockQuantity, unitRefundPaise, refundPaise, createdAt
                ) VALUES (?, ?, ?, ?, ?, ?, 1, 1, 200, 200, 1)
                """.trimIndent(),
                arrayOf(4001L, 3001L, 1001L, OP, 2002L, id),
            )
        }
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(0L, tableCount("return_items"))
    }

    @Test
    fun invalidPriceAndOpeningStockNeverCreateRecords() = runBlocking {
        expectFailure { products.createProduct(OP, draft().copy(purchaseCostPaise = -1)) }
        expectFailure { products.createProduct(OP, draft().copy(sellingPricePaise = -1)) }
        expectFailure { products.createProduct(OP, draft(opening = -1)) }
        expectFailure { products.createProduct(OP, draft(name = "   ")) }
        expectFailure { products.createProduct(OP, draft().copy(category = "   ")) }
        assertTrue(products.observeProducts(OP).first().isEmpty())
    }

    @Test
    fun recordsAndSelectedShopSurviveClosingAndReopeningTheDatabase() = runBlocking {
        val name = "persistence-${System.nanoTime()}.db"
        var diskDb: PosDatabase? = null
        try {
            diskDb = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
            val diskShops = RoomShopRepository(diskDb, false)
            val diskProducts = RoomProductRepository(diskDb)
            diskShops.initialize()
            diskShops.selectShop(FF)
            val id = diskProducts.createProduct(FF, draft(barcode = "PERSISTED", opening = 11))
            RoomInventoryRepository(diskDb).adjustStock(FF, id, -1, "Count correction")
            val expected = requireNotNull(diskProducts.getProduct(FF, id))
            diskDb.close()
            diskDb = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
            val reopenedShops = RoomShopRepository(diskDb, false)
            reopenedShops.initialize()
            assertEquals(FF, reopenedShops.observeSelectedShop().first()?.id)
            assertEquals(2, reopenedShops.observeShops().first().size)
            assertEquals(expected, RoomProductRepository(diskDb).getProduct(FF, id))
            assertEquals(10L, expected.quantity)
            assertEquals(2, diskDb.posDao().getMovements(FF, id).size)
        } finally {
            diskDb?.close()
            context.deleteDatabase(name)
        }
    }

    private fun draft(
        name: String = "Rose perfume",
        barcode: String? = null,
        opening: Long = 4,
    ) = ProductDraft(
        name = name, category = "Perfumes", variant = "50 ml", manufacturerBarcode = barcode,
        purchaseCostPaise = 12_345L, sellingPricePaise = 20_050L, openingQuantity = opening,
    )

    // Future transaction entities are populated only as isolated constraint fixtures.
    private fun insertSale(id: Long, shopId: Long) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO sales(
                id, shopId, receiptNumber, status, subtotalPaise, discountPaise,
                totalPaise, amountPaidPaise, changePaise, paymentMethod,
                createdAt, completedAt, updatedAt
            ) VALUES (?, ?, ?, 'COMPLETED', 200, 0, 200, 200, 0, 'CASH', 1, 1, 1)
            """.trimIndent(),
            arrayOf(id, shopId, "TEST-$shopId-$id"),
        )
    }

    private fun insertSaleItem(id: Long, saleId: Long, shopId: Long, productId: Long) {
        database.openHelper.writableDatabase.execSQL(
            """
            INSERT INTO sale_items(
                id, saleId, shopId, productId, skuSnapshot, nameSnapshot, variantSnapshot,
                barcodeSnapshot, quantity, unitPricePaise, unitCostPaise,
                discountPaise, lineTotalPaise, createdAt
            ) VALUES (?, ?, ?, ?, 'TEST-SKU', 'Test item', '', NULL, 1, 200, 100, 0, 200, 1)
            """.trimIndent(),
            arrayOf(id, saleId, shopId, productId),
        )
    }

    private fun insertReturn(id: Long, saleId: Long, shopId: Long) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO returns(id, saleId, shopId, reason, refundPaise, createdAt) VALUES (?, ?, ?, 'Test return', 200, 1)",
            arrayOf(id, saleId, shopId),
        )
    }

    private fun tableCount(table: String): Long =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private suspend fun expectFailure(action: suspend () -> Unit): Exception {
        try {
            action()
        } catch (exception: Exception) {
            return exception
        }
        fail("Expected the operation to fail")
        throw AssertionError("Unreachable")
    }

    private companion object {
        const val OP = 1L
        const val FF = 2L
    }
}
