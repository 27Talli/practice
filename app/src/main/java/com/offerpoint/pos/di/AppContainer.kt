package com.offerpoint.pos.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.offerpoint.pos.BuildConfig
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.database.MIGRATION_1_2
import com.offerpoint.pos.data.repository.RoomInventoryRepository
import com.offerpoint.pos.data.repository.RoomProductRepository
import com.offerpoint.pos.data.repository.RoomShopRepository
import com.offerpoint.pos.data.repository.RoomSalesRepository
import com.offerpoint.pos.domain.repository.InventoryRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.ShopRepository
import com.offerpoint.pos.domain.repository.SalesRepository

/** A single application-scoped graph. Constructor injection keeps repositories testable. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        PosDatabase::class.java,
        if (BuildConfig.DEMO_MODE) "offer_point_demo.db" else "offer_point_pos.db",
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(MIGRATION_1_2)
        // Deliberately no destructive migration fallback.
        .build()

    val shopRepository: ShopRepository = RoomShopRepository(database, BuildConfig.DEMO_MODE)
    val productRepository: ProductRepository = RoomProductRepository(database)
    val inventoryRepository: InventoryRepository = RoomInventoryRepository(database)
    val salesRepository: SalesRepository = RoomSalesRepository(database)
}
