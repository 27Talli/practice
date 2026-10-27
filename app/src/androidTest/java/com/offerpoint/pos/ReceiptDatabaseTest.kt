package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.*
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.BuildReceipt
import com.offerpoint.pos.ui.receipts.ReceiptPdfExporter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "receipts-test-${System.nanoTime()}.db"
    private lateinit var db: PosDatabase
    @Before fun before() = runBlocking { db = Room.databaseBuilder(context, PosDatabase::class.java, name).build(); RoomShopRepository(db, false).initialize() }
    @After fun after() { db.close(); context.deleteDatabase(name) }

    @Test fun defaultsAreShopScopedAndSavedPreferencesSurviveDatabaseReopen() = runBlocking {
        val repo = RoomReceiptSettingsRepository(db)
        assertEquals(ReceiptSettings(1), repo.getSettings(1))
        repo.saveSettings(ReceiptSettings(1, ReceiptPaper.ROLL_58, "Visit again"))
        repo.saveSettings(ReceiptSettings(2, ReceiptPaper.ROLL_80, "Fancy Footwears footer"))
        db.close(); db = Room.databaseBuilder(context, PosDatabase::class.java, name).build()
        assertEquals(ReceiptSettings(1, ReceiptPaper.ROLL_58, "Visit again"), RoomReceiptSettingsRepository(db).getSettings(1))
        assertEquals(ReceiptSettings(2, ReceiptPaper.ROLL_80, "Fancy Footwears footer"), RoomReceiptSettingsRepository(db).getSettings(2))
    }

    @Test fun validationAndShopForeignKeysPreventInvalidPreferences() = runBlocking {
        val repo = RoomReceiptSettingsRepository(db)
        for (settings in listOf(ReceiptSettings(99), ReceiptSettings(1, footer = "X".repeat(241)), ReceiptSettings(1, footer = "bad\u0000text"))) {
            try { repo.saveSettings(settings); fail("Invalid settings must fail") } catch (_: IllegalArgumentException) { }
        }
        assertNull(db.posDao().getReceiptSettings(1))
        assertNull(db.posDao().getReceiptSettings(99))
        assertTrue(db.posDao().getAudits().isEmpty())
    }

    @Test fun changingPreferencesWritesAnAuditAndRepeatingTheSameSaveDoesNotDuplicateIt() = runBlocking {
        val repo = RoomReceiptSettingsRepository(db)
        val value = ReceiptSettings(1, ReceiptPaper.ROLL_80, "Quoted \"footer\"")
        repo.saveSettings(value); repo.saveSettings(value)
        assertEquals(1, db.posDao().getAudits().size)
        val audit = db.posDao().getAudits().single()
        assertEquals("RECEIPT_SETTINGS", audit.entityType)
        assertTrue(audit.afterValue!!.contains("ROLL_80"))
    }

    @Test fun auditFailureRollsBackReceiptSettingsAtomically() = runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TEMP TRIGGER fail_receipt_audit BEFORE INSERT ON audit_events BEGIN SELECT RAISE(ABORT, 'forced audit failure'); END")
        try { RoomReceiptSettingsRepository(db).saveSettings(ReceiptSettings(1, ReceiptPaper.ROLL_58)); fail("Expected rollback") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertNull(db.posDao().getReceiptSettings(1))
        assertTrue(db.posDao().getAudits().isEmpty())
    }

    @Test fun exportingAndReprintingUseSaleSnapshotsWithoutChangingAnyBusinessRecord() = runBlocking {
        val products = RoomProductRepository(db)
        val sales = RoomSalesRepository(db)
        val id = products.createProduct(1, ProductDraft("Name at sale", "Cosmetics", "Rose", purchaseCostPaise = 333, sellingPricePaise = 555, openingQuantity = 5))
        val saved = sales.completeSale(CheckoutRequest("receipt-snapshot-test", 1, listOf(CheckoutLine(id, 2, 555, 10)), 100, 2_000))
        products.updateProduct(1, id, ProductDraft("New catalog name", "Cosmetics", "Blue", purchaseCostPaise = 999, sellingPricePaise = 888))
        val auditCount = db.posDao().getAudits().size
        val movementCount = db.posDao().getMovements(1, id).size
        repeat(2) {
            val loaded = requireNotNull(sales.getSale(1, saved.summary.id))
            val receipt = BuildReceipt.create(ReceiptAndroidFixtures.shop, loaded, RoomReceiptSettingsRepository(db).getSettings(1), false)
            val text = receipt.lines.joinToString { it.toString() }
            assertTrue(text.contains("Name at sale") && text.contains("Rose") && text.contains("Rs. 5.55"))
            assertFalse(text.contains("New catalog name") || text.contains("Rs. 3.33") || text.contains("Rs. 9.99"))
            val file = ReceiptPdfExporter.write(context, receipt, ReceiptPaper.ROLL_58)
            assertTrue(file.length() > 0); file.delete()
        }
        assertNull(sales.getSale(2, saved.summary.id))
        assertEquals(saved, sales.getSale(1, saved.summary.id))
        assertEquals(1, db.posDao().countSales())
        assertEquals(3L, products.getProduct(1, id)?.quantity)
        assertEquals(2L, db.posDao().getSaleSequence(1)?.nextValue)
        assertEquals(auditCount, db.posDao().getAudits().size)
        assertEquals(movementCount, db.posDao().getMovements(1, id).size)
    }
}
