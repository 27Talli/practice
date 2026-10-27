package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offerpoint.pos.data.local.database.*
import com.offerpoint.pos.data.repository.*
import com.offerpoint.pos.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration6Test {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="migration-v5-v6-${System.nanoTime()}.db"
    @get:Rule @Suppress("DEPRECATION") val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),PosDatabase::class.java.canonicalName!!,FrameworkSQLiteOpenHelperFactory())
    @After fun after(){context.deleteDatabase(name)}
    @Test fun additiveMigrationPreservesCatalogStockPreferencesSalesExpensesAndSelection()=runBlocking {
        helper.createDatabase(name,5).apply{
            execSQL("INSERT INTO shops VALUES(1,'OP','Offer Point','Mardan',1,1)")
            execSQL("INSERT INTO shops VALUES(2,'FF','Fancy Footwears','Mardan',1,1)")
            execSQL("INSERT INTO app_settings VALUES(1,1,0,'old-generation')")
            execSQL("INSERT INTO products VALUES(1,'OP-000001','Lipstick','Cosmetics','Rose',1,1,1)")
            execSQL("INSERT INTO shop_products VALUES(1,1,12345,20000,1,1,1)")
            execSQL("INSERT INTO inventory VALUES(1,1,8,1)")
            execSQL("INSERT INTO receipt_settings VALUES(1,'ROLL_58','Existing footer',1)")
            execSQL("INSERT INTO sales(id,shopId,receiptNumber,status,subtotalPaise,discountPaise,totalPaise,amountPaidPaise,changePaise,paymentMethod,createdAt,completedAt,updatedAt,orderDiscountPaise) VALUES(1,1,'OP-S-000001','COMPLETED',20000,0,20000,20000,0,'CASH',1,1,1,0)")
            execSQL("INSERT INTO sale_items VALUES(1,1,1,1,'OP-000001','Original lipstick','Rose',NULL,1,20000,12345,0,20000,1)")
            execSQL("INSERT INTO expenses VALUES(1,1,5000,'Rent','Existing expense',1,1,1)")
            close()
        }
        helper.runMigrationsAndValidate(name,6,true,MIGRATION_5_6).close()
        val db=Room.databaseBuilder(context,PosDatabase::class.java,name).addMigrations(MIGRATION_5_6).build()
        try {
            assertEquals(1L,RoomShopRepository(db,false).observeSelectedShop().first()?.id)
            val product=requireNotNull(RoomProductRepository(db).getProduct(1,1));assertEquals(8L,product.quantity);assertEquals(12345L,product.purchaseCostPaise);assertEquals(5L,product.reorderLevel)
            assertEquals("Original lipstick",RoomSalesRepository(db).getSale(1,1)?.items?.single()?.name)
            assertEquals("",RoomSalesRepository(db).getSale(1,1)?.items?.single()?.category)
            assertFalse(RoomExpenseRepository(db).observe(1).first().single().isVoided)
            assertEquals(ReceiptSettings(1,ReceiptPaper.ROLL_58,"Existing footer"),RoomReceiptSettingsRepository(db).getSettings(1))
            assertFalse(RoomSecurityRepository(db).observe().first().enabled);assertEquals("old-generation",db.posDao().observeDataGeneration().first())
            assertNull(db.posDao().getReturnSequence(1))
        }finally{db.close()}
    }
}
