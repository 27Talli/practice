package com.offerpoint.pos

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.CheckoutException
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.ProductDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Integration tests execute the actual Room repository and Android SQLite transactions. */
@RunWith(AndroidJUnit4::class)
class CheckoutDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: PosDatabase
    private lateinit var products: RoomProductRepository
    private lateinit var sales: RoomSalesRepository
    private var now = Instant.parse("2026-10-26T12:00:00Z").toEpochMilli()

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).build()
        RoomShopRepository(database, false).initialize()
        products = RoomProductRepository(database)
        sales = RoomSalesRepository(database) { now }
    }

    @After fun tearDown() { database.close() }

    @Test fun cashSaleCommitsExactDiscountsSnapshotsStockAndLedgerTogether() = runBlocking {
        val first = products.createProduct(OP, draft("Lipstick", "0001234567", cost = 10_010, price = 20_025, stock = 8))
        val second = products.createProduct(OP, draft("Gift box", "0009876543", cost = 5_000, price = 7_500, stock = 4))
        val original = requireNotNull(products.getProduct(OP, first))
        val request = CheckoutRequest(token(), OP, listOf(
            CheckoutLine(first, 2, 20_025, 50), CheckoutLine(second, 1, 7_500, 500),
        ), orderDiscountPaise = 1_000, amountPaidPaise = 50_000)

        val saved = sales.completeSale(request)

        assertEquals(OP, saved.summary.shopId)
        assertEquals("OP-S-000001", saved.summary.receiptNumber)
        assertEquals(now, saved.summary.completedAt)
        assertEquals(47_550L, saved.subtotalPaise)
        assertEquals(1_550L, saved.discountPaise)
        assertEquals(1_000L, saved.orderDiscountPaise)
        assertEquals(46_000L, saved.summary.totalPaise)
        assertEquals(50_000L, saved.amountPaidPaise)
        assertEquals(4_000L, saved.changePaise)
        assertEquals("CASH", saved.paymentMethod)
        assertEquals(2, saved.items.size)
        val line = saved.items.single { it.productId == first }
        assertEquals(original.sku, line.sku)
        assertEquals("Lipstick", line.name)
        assertEquals("Rose", line.variant)
        assertEquals(2L, line.quantity)
        assertEquals(20_025L, line.unitPricePaise)
        assertEquals(10_010L, line.unitCostPaise)
        assertEquals(50L, line.discountPaise)
        assertEquals(40_000L, line.lineTotalPaise)
        assertEquals(6L, products.getProduct(OP, first)?.quantity)
        assertEquals(3L, products.getProduct(OP, second)?.quantity)
        assertEquals(1L, count("sales"))
        assertEquals(2L, count("sale_items"))
        val header = requireNotNull(database.posDao().getSale(OP, saved.summary.id))
        assertEquals("COMPLETED", header.status)
        assertEquals(request.requestId, header.checkoutRequestId)
        assertFalse(header.requestFingerprint.isNullOrBlank())
        val storedLine = database.posDao().getSaleItems(OP, saved.summary.id).single { it.productId == first }
        assertEquals("0001234567", storedLine.barcodeSnapshot)
        val movement = database.posDao().getMovements(OP, first).single { it.type == "SALE" }
        assertEquals(-2L, movement.delta)
        assertEquals(6L, movement.quantityAfter)
        assertEquals(storedLine.id, movement.saleItemId)
        assertTrue(database.posDao().getAudits().any { it.entityType == "SALE" && it.entityId == saved.summary.id })

        // Historical values remain fixed when the shared catalog and current cost change later.
        products.updateProduct(OP, first, draft("Renamed", "NEW-BARCODE", cost = 30_000, price = 50_000))
        assertEquals(saved, sales.getSale(OP, saved.summary.id))
        assertEquals(saved, sales.completeSale(request))
        assertEquals(6L, products.getProduct(OP, first)?.quantity)
    }

    @Test fun aFullyDiscountedSaleStillRecordsItsStockAndTransaction() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 2))
        val saved = sales.completeSale(CheckoutRequest(token(), OP,
            listOf(CheckoutLine(id, 1, 100, 25)), orderDiscountPaise = 75, amountPaidPaise = 0))
        assertEquals(0L, saved.summary.totalPaise)
        assertEquals(0L, saved.changePaise)
        assertEquals(1L, products.getProduct(OP, id)?.quantity)
        assertEquals(1, sales.observeTodaySales(OP).first().transactionCount)
    }

    @Test fun invalidPaymentDiscountQuantityAndOverflowNeverPostPartialSales() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 5))
        val valid = request(id, price = 100)
        val bad = listOf(
            valid.copy(amountPaidPaise = 99),
            valid.copy(lines = emptyList()),
            valid.copy(lines = listOf(CheckoutLine(id, 0, 100))),
            valid.copy(lines = listOf(CheckoutLine(id, 1, 100), CheckoutLine(id, 1, 100))),
            valid.copy(lines = listOf(CheckoutLine(id, 1, 100, 101))),
            valid.copy(orderDiscountPaise = 101),
            valid.copy(orderDiscountPaise = -1),
            valid.copy(lines = listOf(CheckoutLine(id, Long.MAX_VALUE, Long.MAX_VALUE)), amountPaidPaise = Long.MAX_VALUE),
        )
        bad.forEach { invalid -> assertTrue(expectFailure { sales.completeSale(invalid) } is CheckoutException) }
        assertEquals(0L, count("sales"))
        assertEquals(0L, count("sale_items"))
        assertEquals(5L, products.getProduct(OP, id)?.quantity)
        assertEquals(1, database.posDao().getMovements(OP, id).size)
        assertNull(database.posDao().getSaleSequence(OP))
    }

    @Test fun aPriceChangedAfterAddingToCartMustBeReviewedBeforePayment() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 5))
        val cart = request(id, price = 100)
        products.updateProduct(OP, id, draft(price = 150))
        assertTrue(expectFailure { sales.completeSale(cart) } is CheckoutException)
        assertEquals(0L, count("sales"))
        assertEquals(5L, products.getProduct(OP, id)?.quantity)
        assertNull(database.posDao().getSaleSequence(OP))
    }

    @Test fun oneInsufficientLineRejectsTheEntireMultiProductSale() = runBlocking {
        val first = products.createProduct(OP, draft("First", price = 100, stock = 5))
        val second = products.createProduct(OP, draft("Second", price = 100, stock = 1))
        val beforeAudit = database.posDao().getAudits().size
        val request = CheckoutRequest(token(), OP, listOf(CheckoutLine(first, 1, 100), CheckoutLine(second, 2, 100)), amountPaidPaise = 300)
        assertTrue(expectFailure { sales.completeSale(request) } is CheckoutException)
        assertEquals(0L, count("sales"))
        assertEquals(0L, count("sale_items"))
        assertEquals(5L, products.getProduct(OP, first)?.quantity)
        assertEquals(1L, products.getProduct(OP, second)?.quantity)
        assertEquals(2L, count("stock_movements"))
        assertEquals(beforeAudit, database.posDao().getAudits().size)
    }

    @Test fun anotherShopsProductCannotBeCheckedOutInTheSelectedShop() = runBlocking {
        val op = products.createProduct(OP, draft(price = 100, stock = 5))
        val ff = products.createProduct(FF, draft(price = 100, stock = 5))
        val request = CheckoutRequest(token(), FF, listOf(CheckoutLine(ff, 1, 100), CheckoutLine(op, 1, 100)), amountPaidPaise = 200)
        assertTrue(expectFailure { sales.completeSale(request) } is CheckoutException)
        assertEquals(0L, count("sales"))
        assertEquals(5L, products.getProduct(OP, op)?.quantity)
        assertEquals(5L, products.getProduct(FF, ff)?.quantity)
    }

    @Test fun archivedRegistryProductsAndShopAssociationsCannotBeSold() = runBlocking {
        val first = products.createProduct(OP, draft(price = 100, stock = 5))
        val second = products.createProduct(OP, draft(price = 100, stock = 5))
        database.openHelper.writableDatabase.execSQL("UPDATE products SET isActive = 0 WHERE id = ?", arrayOf(first))
        database.openHelper.writableDatabase.execSQL("UPDATE shop_products SET isActive = 0 WHERE shopId = ? AND productId = ?", arrayOf(OP, second))
        assertTrue(expectFailure { sales.completeSale(request(first, price = 100)) } is CheckoutException)
        assertTrue(expectFailure { sales.completeSale(request(second, price = 100)) } is CheckoutException)
        assertEquals(0L, count("sales"))
        assertEquals(5L, database.posDao().getInventory(OP, first)?.quantity)
        assertEquals(5L, database.posDao().getInventory(OP, second)?.quantity)
    }

    @Test fun identicalRetriesReturnOneSavedSaleEvenAfterStockIsNowEmpty() = runBlocking {
        val first = products.createProduct(OP, draft("First", price = 100, stock = 1))
        val second = products.createProduct(OP, draft("Second", price = 200, stock = 1))
        val request = CheckoutRequest(token(), OP, listOf(CheckoutLine(first, 1, 100), CheckoutLine(second, 1, 200)), amountPaidPaise = 500)
        val saved = sales.completeSale(request)
        assertEquals(saved, sales.completeSale(request.copy(lines = request.lines.reversed())))
        assertEquals(saved, sales.findCompletedRequest(OP, request.requestId))
        assertEquals(1L, count("sales"))
        assertEquals(2L, count("sale_items"))
        assertEquals(4L, count("stock_movements"))
        assertEquals(0L, products.getProduct(OP, first)?.quantity)
        assertEquals(2L, database.posDao().getSaleSequence(OP)?.nextValue)
    }

    @Test fun concurrentDuplicateSubmissionsDeductStockAndAllocateReceiptOnlyOnce() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 10))
        val request = request(id, quantity = 2, price = 100)
        val results = coroutineScope {
            (1..8).map { async(Dispatchers.IO) { sales.completeSale(request) } }.awaitAll()
        }
        assertEquals(1, results.map { it.summary.id }.toSet().size)
        assertEquals(1L, count("sales"))
        assertEquals(1L, count("sale_items"))
        assertEquals(8L, products.getProduct(OP, id)?.quantity)
        assertEquals(2, database.posDao().getMovements(OP, id).size)
        assertEquals(2L, database.posDao().getSaleSequence(OP)?.nextValue)
    }

    @Test fun distinctConcurrentSalesCannotOversellTheLastUnit() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 1))
        val results = coroutineScope {
            (1..2).map { async(Dispatchers.IO) { runCatching { sales.completeSale(request(id, price = 100)) } } }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.isFailure })
        assertEquals(1L, count("sales"))
        assertEquals(0L, products.getProduct(OP, id)?.quantity)
        assertEquals(2L, database.posDao().getSaleSequence(OP)?.nextValue)
    }

    @Test fun anIdempotencyKeyCannotBeReusedForDifferentCashCartOrShop() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 5))
        products.associateProduct(FF, id, 50, 100, 5)
        val request = request(id, price = 100)
        sales.completeSale(request)
        listOf(
            request.copy(amountPaidPaise = 200),
            request.copy(lines = listOf(CheckoutLine(id, 2, 100)), amountPaidPaise = 200),
            request.copy(shopId = FF),
        ).forEach { changed -> assertTrue(expectFailure { sales.completeSale(changed) } is CheckoutException) }
        assertEquals(1L, count("sales"))
        assertEquals(4L, products.getProduct(OP, id)?.quantity)
        assertEquals(5L, products.getProduct(FF, id)?.quantity)
        assertNull(sales.findCompletedRequest(FF, request.requestId))
    }

    @Test fun receiptNumbersUseSeparateTransactionalSequencesForEachShop() = runBlocking {
        val op = products.createProduct(OP, draft(price = 100, stock = 5))
        val ff = products.createProduct(FF, draft(price = 100, stock = 5))
        assertEquals("OP-S-000001", sales.completeSale(request(op, price = 100)).summary.receiptNumber)
        assertEquals("OP-S-000002", sales.completeSale(request(op, price = 100)).summary.receiptNumber)
        assertEquals("FF-S-000001", sales.completeSale(request(ff, FF, price = 100)).summary.receiptNumber)
        assertEquals(3L, database.posDao().getSaleSequence(OP)?.nextValue)
        assertEquals(2L, database.posDao().getSaleSequence(FF)?.nextValue)
    }

    @Test fun aPreexistingReceiptNumberIsSkippedWithoutOverwritingHistory() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 5))
        insertLegacyHeader(1000, OP, "OP-S-000001", 100, now)
        val saved = sales.completeSale(request(id, price = 100))
        assertEquals("OP-S-000002", saved.summary.receiptNumber)
        assertEquals(2L, count("sales"))
        assertEquals(4L, products.getProduct(OP, id)?.quantity)
        assertEquals("OP-S-000001", sales.getSale(OP, 1000)?.summary?.receiptNumber)
    }

    @Test fun aLedgerFailureRollsBackHeaderLinesSequenceStockAndAudit() = runBlocking {
        val first = products.createProduct(OP, draft("First", price = 100, stock = 5))
        val second = products.createProduct(OP, draft("Second", price = 100, stock = 5))
        val beforeAudit = database.posDao().getAudits().size
        val request = CheckoutRequest(token(), OP, listOf(CheckoutLine(first, 1, 100), CheckoutLine(second, 1, 100)), amountPaidPaise = 200)
        database.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_checkout_ledger BEFORE INSERT ON stock_movements
            WHEN NEW.type = 'SALE' AND NEW.productId = $second
            BEGIN SELECT RAISE(ABORT, 'checkout ledger failure'); END
        """.trimIndent())
        try {
            expectFailure { sales.completeSale(request) }
            assertEquals(0L, count("sales"))
            assertEquals(0L, count("sale_items"))
            assertEquals(5L, products.getProduct(OP, first)?.quantity)
            assertEquals(5L, products.getProduct(OP, second)?.quantity)
            assertEquals(2L, count("stock_movements"))
            assertEquals(beforeAudit, database.posDao().getAudits().size)
            assertNull(database.posDao().getSaleSequence(OP))
            assertNull(sales.findCompletedRequest(OP, request.requestId))
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_checkout_ledger")
        }
        assertEquals("OP-S-000001", sales.completeSale(request).summary.receiptNumber)
        assertEquals(4L, products.getProduct(OP, first)?.quantity)
        assertEquals(4L, products.getProduct(OP, second)?.quantity)
    }

    @Test fun saleHistoryDetailsAndRequestLookupAreShopScopedAndCompletedOnly() = runBlocking {
        val op = products.createProduct(OP, draft(price = 100, stock = 5))
        val ff = products.createProduct(FF, draft(price = 100, stock = 5))
        val first = sales.completeSale(request(op, price = 100))
        now += 1_000
        val second = sales.completeSale(request(op, price = 100))
        val ffRequest = request(ff, FF, price = 100)
        val other = sales.completeSale(ffRequest)
        insertLegacyHeader(9000, OP, "TEST-DRAFT", 999_999, now, "DRAFT")
        assertEquals(listOf(second.summary.id, first.summary.id), sales.observeSales(OP).first().map { it.id })
        assertEquals(listOf(other.summary.id), sales.observeSales(FF).first().map { it.id })
        assertNull(sales.getSale(FF, first.summary.id))
        assertNull(sales.getSale(OP, other.summary.id))
        assertNull(sales.getSale(OP, 9000))
        assertNull(sales.findCompletedRequest(OP, ffRequest.requestId))
    }

    @Test fun todaysTotalsUseKarachiMidnightInclusiveAndNextMidnightExclusive() = runBlocking {
        val start = Instant.parse("2026-10-25T19:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-10-26T19:00:00Z").toEpochMilli()
        val op = products.createProduct(OP, draft(price = 100, stock = 10))
        val ff = products.createProduct(FF, draft(price = 100, stock = 10))
        now = start - 1
        sales.completeSale(request(op, price = 100))
        now = start
        val atStart = sales.completeSale(request(op, price = 100))
        now = start + 12 * 60 * 60 * 1_000
        val middle = sales.completeSale(request(op, quantity = 2, price = 100))
        sales.completeSale(request(ff, FF, price = 100))
        insertLegacyHeader(9000, OP, "TEST-DRAFT", 99_999, now, "DRAFT")
        now = end
        sales.completeSale(request(op, price = 100))
        now = start + 18 * 60 * 60 * 1_000
        val totals = sales.observeTodaySales(OP).first()
        assertEquals(300L, totals.totalPaise)
        assertEquals(2, totals.transactionCount)
        assertEquals(setOf(atStart.summary.id, middle.summary.id), database.posDao().observeSalesInRange(OP, start, end).first().map { it.id }.toSet())
        assertEquals(100L, sales.observeTodaySales(FF).first().totalPaise)
    }

    @Test fun todayFlowStartsAtZeroAndObservesANewCompletedSale() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 5))
        assertEquals(0L, sales.observeTodaySales(OP).first().totalPaise)
        assertEquals(0, sales.observeTodaySales(OP).first().transactionCount)
        val observed = async { withTimeout(10_000) { sales.observeTodaySales(OP).first { it.transactionCount == 1 } } }
        sales.completeSale(request(id, price = 100))
        assertEquals(100L, observed.await().totalPaise)
        assertEquals(0, sales.observeTodaySales(FF).first().transactionCount)
    }

    @Test fun todaysMoneyTotalRejectsOverflowInsteadOfWrappingToANegativeAmount() = runBlocking {
        val id = products.createProduct(OP, draft(price = Long.MAX_VALUE, stock = 2))
        sales.completeSale(request(id, price = Long.MAX_VALUE))
        sales.completeSale(request(id, price = Long.MAX_VALUE))
        assertEquals(2L, count("sales"))
        assertTrue(expectFailure { sales.observeTodaySales(OP).first() } is CheckoutException)
        assertEquals(0L, products.getProduct(OP, id)?.quantity)
    }

    @Test fun sqliteEnforcesGlobalRequestIdUniquenessIndependentlyOfTheRepository() = runBlocking {
        val id = products.createProduct(OP, draft(price = 100, stock = 5))
        val request = request(id, price = 100)
        sales.completeSale(request)
        val failure = expectFailure {
            database.openHelper.writableDatabase.execSQL("""
                INSERT INTO sales(shopId, receiptNumber, status, subtotalPaise, discountPaise, totalPaise,
                    amountPaidPaise, changePaise, paymentMethod, createdAt, completedAt, updatedAt, checkoutRequestId)
                VALUES (?, 'TEST-DUPLICATE', 'COMPLETED', 100, 0, 100, 100, 0, 'CASH', 1, 1, 1, ?)
            """.trimIndent(), arrayOf(FF, request.requestId))
        }
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(1L, count("sales"))
    }

    @Test fun closingAndReopeningRetainsTheSaleAndMakesItsRetryIdempotent() = runBlocking {
        val name = "checkout-persistence-${System.nanoTime()}.db"
        var disk: PosDatabase? = null
        try {
            disk = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
            RoomShopRepository(disk, false).initialize()
            val catalog = RoomProductRepository(disk)
            val id = catalog.createProduct(OP, draft(price = 100, stock = 3))
            val request = request(id, price = 100)
            val saved = RoomSalesRepository(disk) { now }.completeSale(request)
            disk.close()
            disk = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
            val reopened = RoomSalesRepository(disk) { now }
            assertEquals(saved, reopened.getSale(OP, saved.summary.id))
            assertEquals(saved, reopened.findCompletedRequest(OP, request.requestId))
            assertEquals(saved, reopened.completeSale(request))
            assertEquals(2L, RoomProductRepository(disk).getProduct(OP, id)?.quantity)
            assertEquals(1L, disk.posDao().countSales().toLong())
            assertEquals(2L, disk.posDao().getSaleSequence(OP)?.nextValue)
        } finally {
            disk?.close()
            context.deleteDatabase(name)
        }
    }

    private fun draft(name: String = "Lipstick", barcode: String? = null, cost: Long = 50, price: Long = 100, stock: Long = 5) =
        ProductDraft(name, "Cosmetics", "Rose", barcode, cost, price, stock)

    private fun request(productId: Long, shopId: Long = OP, quantity: Long = 1, price: Long = 100) =
        CheckoutRequest(token(), shopId, listOf(CheckoutLine(productId, quantity, price)), amountPaidPaise = quantity * price)

    private fun token() = UUID.randomUUID().toString()

    private fun count(table: String): Long = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        check(it.moveToFirst()); it.getLong(0)
    }

    private fun insertLegacyHeader(id: Long, shopId: Long, receipt: String, total: Long, timestamp: Long, status: String = "COMPLETED") {
        database.openHelper.writableDatabase.execSQL("""
            INSERT INTO sales(id, shopId, receiptNumber, status, subtotalPaise, discountPaise, totalPaise,
                amountPaidPaise, changePaise, paymentMethod, createdAt, completedAt, updatedAt)
            VALUES (?, ?, ?, ?, ?, 0, ?, ?, 0, 'CASH', ?, ?, ?)
        """.trimIndent(), arrayOf(id, shopId, receipt, status, total, total, total, timestamp, timestamp, timestamp))
    }

    private suspend fun expectFailure(action: suspend () -> Unit): Exception {
        try { action() } catch (exception: Exception) { return exception }
        fail("Expected the operation to fail")
        throw AssertionError("Unreachable")
    }

    private companion object { const val OP = 1L; const val FF = 2L }
}
