package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offerpoint.pos.data.local.database.MIGRATION_3_4
import com.offerpoint.pos.data.local.database.MIGRATION_4_5
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.repository.*
import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.BuildReceipt
import com.offerpoint.pos.domain.usecase.CheckoutMath
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration4Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-v3-v4-${System.nanoTime()}.db"
    @get:Rule @Suppress("DEPRECATION")
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PosDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @After fun after() { context.deleteDatabase(name) }

    @Test fun actualM3DatabaseUpgradesWithSalesLabelsPrivateKeysAndInventoryIntact() = runBlocking {
        val request = CheckoutRequest("m3-retry", 1, listOf(CheckoutLine(10, 1, 2_000)), amountPaidPaise = 2_500)
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO shops(id,code,name,address,createdAt,updatedAt) VALUES (1,'OP','Offer Point','Mardan',1,1)")
            execSQL("INSERT INTO shops(id,code,name,address,createdAt,updatedAt) VALUES (2,'FF','Fancy Footwears','Mardan',1,1)")
            execSQL("INSERT INTO app_settings(id,selectedShopId,demoSeeded) VALUES (1,1,0)")
            execSQL("INSERT INTO products(id,sku,name,category,variant,isActive,createdAt,updatedAt) VALUES (10,'OP-000010','M3 lipstick','Cosmetics','Rose',1,1,1)")
            execSQL("INSERT INTO shop_products(shopId,productId,purchaseCostPaise,sellingPricePaise,isActive,createdAt,updatedAt) VALUES (1,10,1000,2000,1,1,1)")
            execSQL("INSERT INTO inventory(shopId,productId,quantity,updatedAt) VALUES (1,10,4,2)")
            execSQL("INSERT INTO product_barcodes(id,productId,value,kind,createdAt) VALUES (11,10,'OP-000010','INTERNAL_CODE128',1)")
            execSQL("INSERT INTO product_barcodes(id,productId,value,kind,createdAt) VALUES (12,10,'OP-000010~LBBB','COST_LABEL',1)")
            execSQL("INSERT INTO cost_code_settings(shopId,alphabet,createdAt,updatedAt) VALUES (1,'BLACKHORSE',1,1)")
            execSQL("INSERT INTO product_labels(barcodeId,shopId,productId,skuSnapshot,nameSnapshot,variantSnapshot,purchaseCostPaise,costCode,alphabetSnapshot,createdAt) VALUES (12,1,10,'OP-000010','M3 lipstick','Rose',1000,'LBBB','BLACKHORSE',1)")
            execSQL("""INSERT INTO sales(id,shopId,receiptNumber,status,subtotalPaise,discountPaise,totalPaise,amountPaidPaise,changePaise,paymentMethod,createdAt,completedAt,updatedAt,checkoutRequestId,requestFingerprint,orderDiscountPaise)
                VALUES (100,1,'OP-S-000001','COMPLETED',2000,0,2000,2500,500,'CASH',2,2,2,?,?,0)""", arrayOf(request.requestId, CheckoutMath.fingerprint(request)))
            execSQL("""INSERT INTO sale_items(id,saleId,shopId,productId,skuSnapshot,nameSnapshot,variantSnapshot,barcodeSnapshot,quantity,unitPricePaise,unitCostPaise,discountPaise,lineTotalPaise,createdAt)
                VALUES (200,100,1,10,'OP-000010','Name on old sale','Rose','OP-000010',1,2000,1000,0,2000,2)""")
            execSQL("INSERT INTO stock_movements(id,shopId,productId,delta,quantityAfter,type,reason,createdAt,saleItemId,returnItemId) VALUES (20,1,10,5,5,'OPENING','Opening',1,NULL,NULL)")
            execSQL("INSERT INTO stock_movements(id,shopId,productId,delta,quantityAfter,type,reason,createdAt,saleItemId,returnItemId) VALUES (21,1,10,-1,4,'SALE','Sale',2,200,NULL)")
            execSQL("INSERT INTO sales_sequences(shopId,nextValue) VALUES (1,2)")
            execSQL("INSERT INTO sku_sequences(prefix,nextValue) VALUES ('OP',11)")
            execSQL("INSERT INTO audit_events(id,entityType,entityId,shopId,action,beforeValue,afterValue,createdAt) VALUES (1,'SALE',100,1,'COMPLETE_CASH_SALE',NULL,'{}',2)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use {
            it.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        }
        val db = Room.databaseBuilder(context, PosDatabase::class.java, name).addMigrations(MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            val shops = RoomShopRepository(db, false); shops.initialize()
            val sales = RoomSalesRepository(db)
            val sale = requireNotNull(sales.getSale(1, 100))
            assertEquals(sale, sales.completeSale(request))
            val labels = RoomBarcodeLabelRepository(db)
            assertEquals("BLACKHORSE", labels.getOrCreateCostCodeSettings(1).alphabet)
            assertEquals(1_000L, labels.findLabelByValue("OP-000010~LBBB")?.purchaseCostPaise)
            assertEquals(4L, RoomProductRepository(db).getProduct(1, 10)?.quantity)
            assertEquals(1L, shops.observeSelectedShop().first()?.id)
            assertEquals(2L, db.posDao().getSaleSequence(1)?.nextValue)
            assertEquals(11L, db.posDao().getSequence("OP")?.nextValue)
            assertEquals(2, db.posDao().getMovements(1, 10).size)
            assertEquals(1, db.posDao().getAudits().size)
            val receiptSettings = RoomReceiptSettingsRepository(db)
            assertEquals(ReceiptSettings(1), receiptSettings.getSettings(1))
            receiptSettings.saveSettings(ReceiptSettings(1, ReceiptPaper.ROLL_58))
            val receipt = BuildReceipt.create(ReceiptAndroidFixtures.shop, sale, receiptSettings.getSettings(1), false)
            assertTrue(receipt.lines.any { it.text == "Name on old sale" })
            assertEquals("OP-S-000001", receipt.receiptNumber)
            assertEquals(1, db.posDao().countSales())
        } finally { db.close() }
    }
}
