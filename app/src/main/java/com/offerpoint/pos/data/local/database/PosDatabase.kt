package com.offerpoint.pos.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.offerpoint.pos.data.local.dao.PosDao
import com.offerpoint.pos.data.local.entity.*

/** Schema export is configured. Commit generated schemas after building; upgrades require tested migrations. */
@Database(
    entities = [
        ShopEntity::class, ProductEntity::class, ProductBarcodeEntity::class,
        ShopProductEntity::class, InventoryEntity::class, StockMovementEntity::class,
        SaleEntity::class, SaleItemEntity::class, ReturnEntity::class, ReturnItemEntity::class,
        ExpenseEntity::class, AuditEventEntity::class, AppSettingsEntity::class, SkuSequenceEntity::class,
        SaleSequenceEntity::class,
        CostCodeSettingsEntity::class, ProductLabelEntity::class,
        ReceiptSettingsEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class PosDatabase : RoomDatabase() {
    abstract fun posDao(): PosDao
}
