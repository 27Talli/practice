package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offerpoint.pos.data.local.database.*
import com.offerpoint.pos.data.repository.RoomReceiptSettingsRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.domain.model.ReceiptPaper
import com.offerpoint.pos.domain.model.ReceiptSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration5Test {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="migration-v4-v5-${System.nanoTime()}.db"
    @get:Rule @Suppress("DEPRECATION") val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),PosDatabase::class.java.canonicalName!!,FrameworkSQLiteOpenHelperFactory())
    @After fun after(){context.deleteDatabase(name)}
    @Test fun additiveNamespaceMigrationKeepsSelectionAndReceiptPreferences()=runBlocking {
        helper.createDatabase(name,4).apply{
            execSQL("INSERT INTO shops VALUES(1,'OP','Offer Point','Mardan',1,1)")
            execSQL("INSERT INTO shops VALUES(2,'FF','Fancy Footwears','Mardan',1,1)")
            execSQL("INSERT INTO app_settings VALUES(1,2,0)")
            execSQL("INSERT INTO receipt_settings VALUES(1,'ROLL_58','Existing footer',1)");close()
        }
        helper.runMigrationsAndValidate(name,5,true,MIGRATION_4_5).close()
        val db=Room.databaseBuilder(context,PosDatabase::class.java,name).addMigrations(MIGRATION_4_5, MIGRATION_5_6).build()
        try {
            val repo=RoomShopRepository(db,false);repo.initialize()
            assertEquals(2L,repo.observeSelectedShop().first()?.id);assertEquals("",db.posDao().observeDataGeneration().first())
            assertEquals(ReceiptSettings(1,ReceiptPaper.ROLL_58,"Existing footer"),RoomReceiptSettingsRepository(db).getSettings(1))
        }finally{db.close()}
    }
}
