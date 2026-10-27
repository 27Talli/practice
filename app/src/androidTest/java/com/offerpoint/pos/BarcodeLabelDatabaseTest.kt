package com.offerpoint.pos

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.entity.ProductBarcodeEntity
import com.offerpoint.pos.data.repository.RoomBarcodeLabelRepository
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.BarcodeLookupException
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.ValidationException
import com.offerpoint.pos.domain.usecase.CostCode
import com.offerpoint.pos.domain.usecase.LookupScannedProduct
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generated labels, lookup and migrations use real Android Room/SQLite; no camera or Internet is required. */
@RunWith(AndroidJUnit4::class)
class BarcodeLabelDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: PosDatabase
    private lateinit var products: RoomProductRepository
    private lateinit var labels: RoomBarcodeLabelRepository
    private lateinit var lookup: LookupScannedProduct

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).build()
        RoomShopRepository(database, false).initialize()
        products = RoomProductRepository(database)
        labels = RoomBarcodeLabelRepository(database)
        lookup = LookupScannedProduct(products, labels)
    }

    @After fun tearDown() { database.close() }

    @Test fun labelRegistersItsCodedCostWithoutChangingCatalogStockOrSales() = runBlocking {
        val id = products.createProduct(OP, draft(barcode = "0001234567890"))
        val before = requireNotNull(products.getProduct(OP, id))
        labels.updateCostCodeAlphabet(OP, KEY)
        val label = labels.createLabel(OP, id)

        assertEquals(id, label.productId)
        assertEquals(OP, label.shopId)
        assertEquals(before.sku, label.sku)
        assertEquals(before.name, label.name)
        assertEquals(before.variant, label.variant)
        assertEquals(12_550L, label.purchaseCostPaise)
        assertEquals("LAHHB", label.costCode)
        assertEquals(KEY, label.alphabetSnapshot)
        assertEquals(12_550L, CostCode.decode(label.costCode, label.alphabetSnapshot))
        assertTrue(label.barcodeValue.contains(label.costCode))
        assertTrue(!label.barcodeValue.contains("12550") && !label.barcodeValue.contains("125.50"))
        assertEquals(id, products.findByBarcode(label.barcodeValue)?.id)
        assertEquals(before, products.findProductForBarcode(OP, label.barcodeValue))
        assertEquals(before, products.getProduct(OP, id))
        assertEquals(0L, count("sales"))
        assertEquals(1, database.posDao().getMovements(OP, id).size)
        val match = lookup(OP, label.barcodeValue, "CODE_128")
        assertEquals(before, match.product)
        assertEquals(label, match.label)
        assertEquals(label, labels.getLabel(OP, label.barcodeId))
    }

    @Test fun creatingTheSameLabelReusesItsRegisteredIdentity() = runBlocking {
        val id = products.createProduct(OP, draft())
        labels.updateCostCodeAlphabet(OP, KEY)
        val first = labels.createLabel(OP, id)
        val barcodeCount = count("product_barcodes")
        val second = labels.createLabel(OP, id)
        assertEquals(first, second)
        assertEquals(barcodeCount, count("product_barcodes"))
        assertEquals(1L, count("product_labels"))
    }

    @Test fun demoAndLiveLabelsUseDifferentNamespacesEvenWithIdenticalProductAndCostData() = runBlocking {
        val id = products.createProduct(OP, draft())
        labels.updateCostCodeAlphabet(OP, KEY)
        val live = labels.createLabel(OP, id)
        // Share the same registry deliberately: namespace separation must not rely on a random key or a different SKU.
        val demo = RoomBarcodeLabelRepository(database, demoMode = true).createLabel(OP, id)
        assertTrue(demo.barcodeValue.startsWith("DEMO~"))
        assertTrue(!live.barcodeValue.startsWith("DEMO~"))
        assertNotEquals(live.barcodeValue, demo.barcodeValue)
        assertNotEquals(live.barcodeId, demo.barcodeId)
        assertEquals(live.sku, demo.sku)
        assertEquals(live.costCode, demo.costCode)
        assertEquals(live.purchaseCostPaise, demo.purchaseCostPaise)
        assertEquals(live.alphabetSnapshot, demo.alphabetSnapshot)
        assertEquals(id, products.findByBarcode(live.barcodeValue)?.id)
        assertEquals(id, products.findByBarcode(demo.barcodeValue)?.id)
        assertEquals(2L, count("product_labels"))
    }

    @Test fun defaultSecretKeyIsValidAndStableInsteadOfBeingRegeneratedOnEveryOpen() = runBlocking {
        val first = labels.getOrCreateCostCodeSettings(OP)
        assertEquals(OP, first.shopId)
        assertEquals(10, first.alphabet.length)
        assertEquals(10, first.alphabet.toSet().size)
        assertTrue(first.alphabet.all { it in 'A'..'Z' })
        assertEquals(first, RoomBarcodeLabelRepository(database).getOrCreateCostCodeSettings(OP))
        assertEquals(1L, count("cost_code_settings"))
    }

    @Test fun changingThePrivateKeyKeepsEarlierLabelCostsReadable() = runBlocking {
        val id = products.createProduct(OP, draft())
        labels.updateCostCodeAlphabet(OP, KEY)
        val old = labels.createLabel(OP, id)
        labels.updateCostCodeAlphabet(OP, " abcdefghij ")
        assertEquals("ABCDEFGHIJ", labels.getOrCreateCostCodeSettings(OP).alphabet)
        val newer = labels.createLabel(OP, id)

        assertNotEquals(old.barcodeId, newer.barcodeId)
        assertNotEquals(old.barcodeValue, newer.barcodeValue)
        assertEquals(old, labels.findLabelByValue(old.barcodeValue))
        assertEquals(KEY, old.alphabetSnapshot)
        assertEquals("ABCDEFGHIJ", newer.alphabetSnapshot)
        assertEquals(old.purchaseCostPaise, CostCode.decode(old.costCode, old.alphabetSnapshot))
        assertEquals(newer.purchaseCostPaise, CostCode.decode(newer.costCode, newer.alphabetSnapshot))
        assertEquals(id, lookup(OP, old.barcodeValue, "CODE_128").product.id)
        assertEquals(2L, count("product_labels"))
    }

    @Test fun oldLabelRetainsItsCostAndNameWhileCheckoutUsesCurrentRoomPrices() = runBlocking {
        val id = products.createProduct(OP, draft())
        labels.updateCostCodeAlphabet(OP, KEY)
        val old = labels.createLabel(OP, id)
        products.updateProduct(OP, id, draft(name = "New name", cost = 13_000, price = 23_000, stock = 0))
        val current = labels.createLabel(OP, id)
        assertNotEquals(old.barcodeId, current.barcodeId)
        assertEquals("Rose lipstick", labels.getLabel(OP, old.barcodeId)?.name)
        assertEquals("New name", current.name)
        assertEquals(13_000L, current.purchaseCostPaise)
        val scanned = lookup(OP, old.barcodeValue, "CODE_128")
        assertEquals(12_550L, scanned.label?.purchaseCostPaise)
        assertEquals(13_000L, scanned.product.purchaseCostPaise)
        assertEquals(23_000L, scanned.product.sellingPricePaise)
        val sale = RoomSalesRepository(database).completeSale(CheckoutRequest(
            "scan-current-prices", OP, listOf(CheckoutLine(id, 1, scanned.product.sellingPricePaise)), amountPaidPaise = 23_000,
        ))
        assertEquals(13_000L, sale.items.single().unitCostPaise)
        assertEquals(23_000L, sale.items.single().unitPricePaise)
        assertEquals(old, labels.findLabelByValue(old.barcodeValue))
    }

    @Test fun generatedPayloadCollisionDoesNotStealAnotherProductsManufacturerBarcode() = runBlocking {
        labels.updateCostCodeAlphabet(OP, KEY)
        val first = products.createProduct(OP, draft())
        val sku = requireNotNull(products.getProduct(OP, first)).sku
        val occupied = "$sku~${CostCode.encode(12_550, KEY)}"
        val second = products.createProduct(OP, draft(name = "Different item", barcode = occupied))
        val label = labels.createLabel(OP, first)
        assertNotEquals(occupied, label.barcodeValue)
        assertEquals(second, products.findByBarcode(occupied)?.id)
        assertEquals(first, products.findByBarcode(label.barcodeValue)?.id)
        assertEquals(first, lookup(OP, label.barcodeValue, "CODE_128").product.id)
        assertEquals(second, lookup(OP, occupied, "CODE_128").product.id)
    }

    @Test fun globalBarcodeConstraintAlsoProtectsGeneratedLabelsFromDirectDuplicateWrites() = runBlocking {
        val first = products.createProduct(OP, draft())
        val second = products.createProduct(OP, draft(name = "Different product"))
        val label = labels.createLabel(OP, first)
        val failure = expectFailure {
            database.posDao().insertBarcode(ProductBarcodeEntity(productId = second, value = label.barcodeValue, kind = "MANUFACTURER", createdAt = 1))
        }
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(first, products.findByBarcode(label.barcodeValue)?.id)
    }

    @Test fun costLabelIsRestrictedToItsOwnShopEvenForASharedProduct() = runBlocking {
        val id = products.createProduct(OP, draft(barcode = "012345678905"))
        products.associateProduct(FF, id, 100, 200, 3)
        val label = labels.createLabel(OP, id)
        assertNull(labels.getLabel(FF, label.barcodeId))
        assertTrue(expectFailure { lookup(FF, label.barcodeValue, "CODE_128") } is BarcodeLookupException)
        assertEquals(200L, lookup(FF, "012345678905", "UPC_A").product.sellingPricePaise)
        assertEquals(8L, products.getProduct(OP, id)?.quantity)
        assertEquals(3L, products.getProduct(FF, id)?.quantity)
    }

    @Test fun labelForeignKeysPreventWrongProductAndWrongShopAssociations() = runBlocking {
        val first = products.createProduct(OP, draft())
        val second = products.createProduct(OP, draft(name = "Second"))
        val label = labels.createLabel(OP, first)
        val original = requireNotNull(database.posDao().getProductLabel(label.barcodeId))
        val otherBarcode = database.posDao().insertBarcode(ProductBarcodeEntity(
            productId = second, value = "DB-WRONG-PRODUCT", kind = "COST_LABEL", createdAt = 1,
        ))
        assertTrue(expectFailure { database.posDao().insertProductLabel(original.copy(barcodeId = otherBarcode)) } is SQLiteConstraintException)
        val firstBarcode = database.posDao().insertBarcode(ProductBarcodeEntity(
            productId = first, value = "DB-WRONG-SHOP", kind = "COST_LABEL", createdAt = 1,
        ))
        assertTrue(expectFailure { database.posDao().insertProductLabel(original.copy(barcodeId = firstBarcode, shopId = FF)) } is SQLiteConstraintException)
        assertEquals(1L, count("product_labels"))
    }

    @Test fun failedLabelInsertRollsBackItsBarcodeAndAuditWrites() = runBlocking {
        val id = products.createProduct(OP, draft())
        labels.updateCostCodeAlphabet(OP, KEY)
        val beforeBarcodes = count("product_barcodes")
        val beforeAudits = count("audit_events")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER test_fail_label BEFORE INSERT ON product_labels
            BEGIN SELECT RAISE(ABORT, 'test label failure'); END
        """.trimIndent())
        try {
            expectFailure { labels.createLabel(OP, id) }
            assertEquals(beforeBarcodes, count("product_barcodes"))
            assertEquals(beforeAudits, count("audit_events"))
            assertEquals(0L, count("product_labels"))
            assertEquals(8L, products.getProduct(OP, id)?.quantity)
            assertEquals(0L, count("sales"))
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER test_fail_label") }
    }

    @Test fun failureOfFirstLabelAlsoRollsBackItsNewSecretKey() = runBlocking {
        val id = products.createProduct(OP, draft())
        assertEquals(0L, count("cost_code_settings"))
        val beforeBarcodes = count("product_barcodes")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER test_fail_first_label BEFORE INSERT ON product_labels
            BEGIN SELECT RAISE(ABORT, 'test first label failure'); END
        """.trimIndent())
        try {
            expectFailure { labels.createLabel(OP, id) }
            assertEquals(0L, count("cost_code_settings"))
            assertEquals(0L, count("product_labels"))
            assertEquals(beforeBarcodes, count("product_barcodes"))
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER test_fail_first_label") }
    }

    @Test fun failedSettingsAuditRollsBackTheKeyChange() = runBlocking {
        labels.updateCostCodeAlphabet(OP, KEY)
        val before = labels.getOrCreateCostCodeSettings(OP)
        val beforeAudits = count("audit_events")
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER test_fail_key_audit BEFORE INSERT ON audit_events
            BEGIN SELECT RAISE(ABORT, 'test key audit failure'); END
        """.trimIndent())
        try {
            expectFailure { labels.updateCostCodeAlphabet(OP, "ABCDEFGHIJ") }
            assertEquals(before, labels.getOrCreateCostCodeSettings(OP))
            assertEquals(beforeAudits, count("audit_events"))
        } finally { database.openHelper.writableDatabase.execSQL("DROP TRIGGER test_fail_key_audit") }
    }

    @Test fun invalidKeysAndUnknownShopsNeverCreateOrReplaceSettings() = runBlocking {
        labels.updateCostCodeAlphabet(OP, KEY)
        listOf("", "ABC", "AAAAAAAAAA", "ABC1234567", "ABCDEFGHIJK").forEach { key ->
            assertTrue(expectFailure { labels.updateCostCodeAlphabet(OP, key) } is ValidationException)
        }
        expectFailure { labels.getOrCreateCostCodeSettings(999) }
        expectFailure { labels.updateCostCodeAlphabet(999, "ABCDEFGHIJ") }
        assertEquals(KEY, labels.getOrCreateCostCodeSettings(OP).alphabet)
        assertEquals(1L, count("cost_code_settings"))
    }

    @Test fun scannerPreservesLeadingZerosAndResolvesAnUnambiguousUpcEanAlias() = runBlocking {
        val id = products.createProduct(OP, draft(barcode = "0012345678905"))
        assertEquals(id, lookup(OP, "012345678905", "UPC_A").product.id)
        assertEquals("0012345678905", lookup(OP, "0012345678905", "EAN_13").product.manufacturerBarcode)
    }

    @Test fun conflictingUpcEanRecordsAreRejectedRatherThanPickingTheWrongProduct() = runBlocking {
        val upc = products.createProduct(OP, draft(name = "UPC item", barcode = "012345678905"))
        val ean = products.createProduct(OP, draft(name = "EAN item", barcode = "0012345678905"))
        assertTrue(expectFailure { lookup(OP, "012345678905", "UPC_A") } is BarcodeLookupException)
        assertTrue(expectFailure { lookup(OP, "0012345678905", "EAN_13") } is BarcodeLookupException)
        assertEquals(upc, lookup(OP, "012345678905", "CODE_128").product.id)
        assertEquals(ean, lookup(OP, "0012345678905", "CODE_128").product.id)
        assertEquals(8L, products.getProduct(OP, upc)?.quantity)
        assertEquals(8L, products.getProduct(OP, ean)?.quantity)
        assertEquals(0L, count("sales"))
    }

    @Test fun unknownInactiveOutOfStockAndWrongShopScansNeverInventProductsOrStock() = runBlocking {
        val id = products.createProduct(OP, draft(barcode = "ACTIVE-BARCODE"))
        val empty = products.createProduct(OP, draft(name = "Empty", barcode = "EMPTY-BARCODE", stock = 0))
        assertTrue(expectFailure { lookup(OP, "UNKNOWN", "CODE_128") } is BarcodeLookupException)
        assertTrue(expectFailure { lookup(FF, "ACTIVE-BARCODE", "CODE_128") } is BarcodeLookupException)
        assertTrue(expectFailure { lookup(OP, "EMPTY-BARCODE", "CODE_128") } is BarcodeLookupException)
        database.openHelper.writableDatabase.execSQL("UPDATE shop_products SET isActive = 0 WHERE shopId = ? AND productId = ?", arrayOf(OP, id))
        assertNull(products.findProductForBarcode(OP, "ACTIVE-BARCODE"))
        assertTrue(expectFailure { lookup(OP, "ACTIVE-BARCODE", "CODE_128") } is BarcodeLookupException)
        database.openHelper.writableDatabase.execSQL("UPDATE shop_products SET isActive = 1 WHERE shopId = ? AND productId = ?", arrayOf(OP, id))
        database.openHelper.writableDatabase.execSQL("UPDATE products SET isActive = 0 WHERE id = ?", arrayOf(id))
        assertNull(products.findProductForBarcode(OP, "ACTIVE-BARCODE"))
        assertEquals(2L, count("products"))
        assertEquals(8L, database.posDao().getInventory(OP, id)?.quantity)
        assertEquals(0L, products.getProduct(OP, empty)?.quantity)
        assertEquals(0L, count("sales"))
    }

    @Test fun zeroAndMaximumCostSnapshotsRemainExactIntegerPaise() = runBlocking {
        labels.updateCostCodeAlphabet(OP, KEY)
        val zero = products.createProduct(OP, draft(name = "Zero cost", cost = 0))
        val maximum = products.createProduct(OP, draft(name = "Maximum cost", cost = Long.MAX_VALUE))
        for (id in listOf(zero, maximum)) {
            val label = labels.createLabel(OP, id)
            assertEquals(products.getProduct(OP, id)?.purchaseCostPaise, CostCode.decode(label.costCode, label.alphabetSnapshot))
            assertTrue(label.costCode.all { it in 'A'..'Z' })
        }
    }

    @Test fun privateKeyAndRegisteredLabelsSurviveDatabaseCloseAndReopen() = runBlocking {
        val name = "barcode-label-persistence-${System.nanoTime()}.db"
        var disk: PosDatabase? = null
        try {
            disk = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
            RoomShopRepository(disk, false).initialize()
            val catalog = RoomProductRepository(disk)
            val diskLabels = RoomBarcodeLabelRepository(disk)
            diskLabels.updateCostCodeAlphabet(OP, KEY)
            val id = catalog.createProduct(OP, draft())
            val original = diskLabels.createLabel(OP, id)
            disk.close()
            disk = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
            val reopened = RoomBarcodeLabelRepository(disk)
            assertEquals(KEY, reopened.getOrCreateCostCodeSettings(OP).alphabet)
            assertEquals(original, reopened.getLabel(OP, original.barcodeId))
            assertEquals(original, reopened.createLabel(OP, id))
            assertEquals(id, LookupScannedProduct(RoomProductRepository(disk), reopened)(OP, original.barcodeValue, "CODE_128").product.id)
        } finally {
            disk?.close()
            context.deleteDatabase(name)
        }
    }

    private fun draft(name: String = "Rose lipstick", barcode: String? = null, cost: Long = 12_550, price: Long = 19_999, stock: Long = 8) =
        ProductDraft(name, "Cosmetics", "Rose", barcode, cost, price, stock)

    private fun count(table: String): Long = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    private suspend fun expectFailure(action: suspend () -> Unit): Exception {
        try { action() } catch (error: Exception) { return error }
        fail("Expected operation to fail")
        throw AssertionError("Unreachable")
    }

    private companion object { const val OP = 1L; const val FF = 2L; const val KEY = "BLACKHORSE" }
}
