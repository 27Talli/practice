package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offerpoint.pos.data.local.database.MIGRATION_2_3
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.RoomBarcodeLabelRepository
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.CheckoutLine
import com.offerpoint.pos.domain.model.CheckoutRequest
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.usecase.CheckoutMath
import com.offerpoint.pos.domain.usecase.CostCode
import com.offerpoint.pos.domain.usecase.LookupScannedProduct
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrade the real exported M2 schema, preserving posted cash sales and their retry identities. */
@RunWith(AndroidJUnit4::class)
class Migration3Test {
    private val name = "migration-v2-v3-${System.nanoTime()}.db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    @Suppress("DEPRECATION")
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), PosDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun upgradePreservesM2SalesInventorySelectionAndAllowsSeveralCodedCostLabels() = runBlocking {
        val legacyRequest = CheckoutRequest(
            "m2-existing-request", 1, listOf(CheckoutLine(10, 1, 20_000)), amountPaidPaise = 25_000,
        )
        val fingerprint = CheckoutMath.fingerprint(legacyRequest)
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO shops(id,code,name,address,createdAt,updatedAt) VALUES (1,'OP','Offer Point','Retained OP address',1,1)")
            execSQL("INSERT INTO shops(id,code,name,address,createdAt,updatedAt) VALUES (2,'FF','Fancy Footwears','Retained FF address',1,1)")
            execSQL("INSERT INTO app_settings(id,selectedShopId,demoSeeded) VALUES (1,2,0)")
            execSQL("""
                INSERT INTO products(id,sku,name,category,variant,isActive,createdAt,updatedAt)
                VALUES (10,'OP-000010','M2 perfume','Perfumes','Rose 50 ml',1,1,1)
            """.trimIndent())
            execSQL("INSERT INTO product_barcodes(id,productId,value,kind,createdAt) VALUES (11,10,'OP-000010','INTERNAL_CODE128',1)")
            execSQL("INSERT INTO product_barcodes(id,productId,value,kind,createdAt) VALUES (12,10,'0000123456789','MANUFACTURER',1)")
            execSQL("""
                INSERT INTO shop_products(shopId,productId,purchaseCostPaise,sellingPricePaise,isActive,createdAt,updatedAt)
                VALUES (1,10,12550,20000,1,1,1)
            """.trimIndent())
            execSQL("""
                INSERT INTO shop_products(shopId,productId,purchaseCostPaise,sellingPricePaise,isActive,createdAt,updatedAt)
                VALUES (2,10,9000,15000,1,1,1)
            """.trimIndent())
            execSQL("INSERT INTO inventory(shopId,productId,quantity,updatedAt) VALUES (1,10,7,2)")
            execSQL("INSERT INTO inventory(shopId,productId,quantity,updatedAt) VALUES (2,10,3,1)")
            execSQL("INSERT INTO sku_sequences(prefix,nextValue) VALUES ('OP',11)")
            execSQL("INSERT INTO sales_sequences(shopId,nextValue) VALUES (1,6)")
            execSQL("""
                INSERT INTO sales(id,shopId,receiptNumber,status,subtotalPaise,discountPaise,totalPaise,amountPaidPaise,
                    changePaise,paymentMethod,createdAt,completedAt,updatedAt,checkoutRequestId,requestFingerprint,orderDiscountPaise)
                VALUES (100,1,'OP-S-000005','COMPLETED',20000,0,20000,25000,5000,'CASH',2,2,2,?,?,0)
            """.trimIndent(), arrayOf(legacyRequest.requestId, fingerprint))
            execSQL("""
                INSERT INTO sale_items(id,saleId,shopId,productId,skuSnapshot,nameSnapshot,variantSnapshot,barcodeSnapshot,
                    quantity,unitPricePaise,unitCostPaise,discountPaise,lineTotalPaise,createdAt)
                VALUES (200,100,1,10,'OP-000010','M2 perfume at sale','Rose 50 ml','0000123456789',1,20000,12550,0,20000,2)
            """.trimIndent())
            execSQL("""
                INSERT INTO stock_movements(id,shopId,productId,delta,quantityAfter,type,reason,createdAt,saleItemId,returnItemId)
                VALUES (20,1,10,8,8,'OPENING','Opening stock',1,NULL,NULL)
            """.trimIndent())
            execSQL("""
                INSERT INTO stock_movements(id,shopId,productId,delta,quantityAfter,type,reason,createdAt,saleItemId,returnItemId)
                VALUES (21,1,10,-1,7,'SALE','Sale OP-S-000005',2,200,NULL)
            """.trimIndent())
            execSQL("""
                INSERT INTO stock_movements(id,shopId,productId,delta,quantityAfter,type,reason,createdAt,saleItemId,returnItemId)
                VALUES (22,2,10,3,3,'OPENING','Opening stock',1,NULL,NULL)
            """.trimIndent())
            execSQL("""
                INSERT INTO audit_events(id,entityType,entityId,shopId,action,beforeValue,afterValue,createdAt)
                VALUES (30,'SALE',100,1,'COMPLETE_CASH_SALE',NULL,'{"totalPaise":20000}',2)
            """.trimIndent())
            execSQL("""
                INSERT INTO expenses(id,shopId,amountPaise,category,description,incurredAt,createdAt,updatedAt)
                VALUES (300,2,500,'Utilities','M2 expense',2,2,2)
            """.trimIndent())
            close()
        }

        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).use { migrated ->
            migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            migrated.query("SELECT checkoutRequestId,requestFingerprint,orderDiscountPaise FROM sales WHERE id=100").use {
                assertTrue(it.moveToFirst())
                assertEquals(legacyRequest.requestId, it.getString(0))
                assertEquals(fingerprint, it.getString(1))
                assertEquals(0L, it.getLong(2))
            }
            migrated.query("PRAGMA index_list(product_barcodes)").use { cursor ->
                val uniqueness = mutableMapOf<String, Int>()
                while (cursor.moveToNext()) uniqueness[cursor.getString(cursor.getColumnIndexOrThrow("name"))] = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                assertEquals(0, uniqueness["index_product_barcodes_productId_kind"])
                assertEquals(1, uniqueness["index_product_barcodes_value"])
                assertEquals(1, uniqueness["index_product_barcodes_id_productId"])
            }
        }

        val room = Room.databaseBuilder(context, PosDatabase::class.java, name).addMigrations(MIGRATION_2_3).build()
        try {
            val shops = RoomShopRepository(room, false)
            val products = RoomProductRepository(room)
            val sales = RoomSalesRepository(room)
            val labels = RoomBarcodeLabelRepository(room)
            shops.initialize()
            assertEquals(2L, shops.observeSelectedShop().first()?.id)
            assertEquals("Retained OP address", shops.observeShops().first().single { it.id == 1L }.address)
            assertEquals(7L, products.getProduct(1, 10)?.quantity)
            assertEquals(3L, products.getProduct(2, 10)?.quantity)
            assertEquals(11L, room.posDao().getSequence("OP")?.nextValue)
            assertEquals(6L, room.posDao().getSaleSequence(1)?.nextValue)
            assertEquals(1, room.posDao().getAudits().size)
            val original = requireNotNull(sales.getSale(1, 100))
            assertEquals("M2 perfume at sale", original.items.single().name)
            assertEquals(12_550L, original.items.single().unitCostPaise)
            assertEquals(5_000L, original.changePaise)
            assertEquals(original, sales.completeSale(legacyRequest))
            assertEquals(1, room.posDao().countSales())
            assertEquals(7L, products.getProduct(1, 10)?.quantity)
            assertEquals(6L, room.posDao().getSaleSequence(1)?.nextValue)
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM expenses WHERE id=300 AND amountPaise=500"))

            labels.updateCostCodeAlphabet(1, "BLACKHORSE")
            val first = labels.createLabel(1, 10)
            labels.updateCostCodeAlphabet(1, "ABCDEFGHIJ")
            val second = labels.createLabel(1, 10)
            assertNotEquals(first.barcodeId, second.barcodeId)
            assertEquals(12_550L, CostCode.decode(first.costCode, first.alphabetSnapshot))
            assertEquals(12_550L, CostCode.decode(second.costCode, second.alphabetSnapshot))
            assertEquals(first, labels.findLabelByValue(first.barcodeValue))
            assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM product_labels"))
            assertEquals("0000123456789", products.getProduct(1, 10)?.manufacturerBarcode)
            assertEquals(10L, LookupScannedProduct(products, labels)(1, first.barcodeValue, "CODE_128").product.id)
            assertNull(labels.getLabel(2, first.barcodeId))
            assertEquals(7L, products.getProduct(1, 10)?.quantity)
            assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM audit_events WHERE id=30 AND entityType='SALE' AND entityId=100"))

            val newer = sales.completeSale(legacyRequest.copy(requestId = "first-m3-sale", amountPaidPaise = 20_000))
            assertEquals("OP-S-000006", newer.summary.receiptNumber)
            assertEquals(7L, room.posDao().getSaleSequence(1)?.nextValue)
            assertEquals(6L, products.getProduct(1, 10)?.quantity)
            assertEquals(2, room.posDao().countSales())
            assertEquals(original, sales.completeSale(legacyRequest))
            assertEquals(2, room.posDao().countSales())

            val added = products.createProduct(1, ProductDraft("New M3 item", "Cosmetics", purchaseCostPaise = 1, sellingPricePaise = 2))
            assertEquals("OP-000011", products.getProduct(1, added)?.sku)
        } finally { room.close() }
    }

    private fun scalar(database: PosDatabase, sql: String): Long = database.openHelper.readableDatabase.query(sql).use {
        check(it.moveToFirst())
        it.getLong(0)
    }
}
