package com.offerpoint.pos.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Amounts throughout the database are integer Pakistani paise; quantities are whole units. */
@Entity(tableName = "shops", indices = [Index(value = ["code"], unique = true)])
data class ShopEntity(
    @PrimaryKey val id: Long,
    val code: String,
    val name: String,
    val address: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "products", indices = [Index(value = ["sku"], unique = true), Index("name")])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sku: String,
    val name: String,
    val category: String,
    val variant: String = "",
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "product_barcodes",
    foreignKeys = [ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("productId"), Index(value = ["value"], unique = true), Index(value = ["productId", "kind"], unique = true)],
)
data class ProductBarcodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val value: String,
    val kind: String,
    val createdAt: Long,
)

@Entity(
    tableName = "shop_products",
    primaryKeys = ["shopId", "productId"],
    foreignKeys = [
        ForeignKey(entity = ShopEntity::class, parentColumns = ["id"], childColumns = ["shopId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("productId")],
)
data class ShopProductEntity(
    val shopId: Long,
    val productId: Long,
    val purchaseCostPaise: Long,
    val sellingPricePaise: Long,
    val isActive: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "inventory",
    primaryKeys = ["shopId", "productId"],
    foreignKeys = [ForeignKey(entity = ShopProductEntity::class, parentColumns = ["shopId", "productId"], childColumns = ["shopId", "productId"], onDelete = ForeignKey.RESTRICT)],
)
data class InventoryEntity(val shopId: Long, val productId: Long, val quantity: Long, val updatedAt: Long)

@Entity(
    tableName = "sales",
    foreignKeys = [ForeignKey(entity = ShopEntity::class, parentColumns = ["id"], childColumns = ["shopId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["id", "shopId"], unique = true), Index(value = ["shopId", "receiptNumber"], unique = true), Index(value = ["shopId", "createdAt"])],
)
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shopId: Long,
    val receiptNumber: String,
    val status: String,
    val subtotalPaise: Long,
    val discountPaise: Long,
    val totalPaise: Long,
    val amountPaidPaise: Long,
    val changePaise: Long,
    val paymentMethod: String = "CASH",
    val createdAt: Long,
    val completedAt: Long?,
    val updatedAt: Long,
)

@Entity(
    tableName = "sale_items",
    foreignKeys = [
        ForeignKey(entity = SaleEntity::class, parentColumns = ["id", "shopId"], childColumns = ["saleId", "shopId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ShopProductEntity::class, parentColumns = ["shopId", "productId"], childColumns = ["shopId", "productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["saleId", "shopId"]), Index(value = ["shopId", "productId"]),
        Index(value = ["id", "saleId", "shopId", "productId"], unique = true),
        Index(value = ["id", "shopId", "productId"], unique = true),
    ],
)
data class SaleItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val shopId: Long,
    val productId: Long,
    val skuSnapshot: String,
    val nameSnapshot: String,
    val variantSnapshot: String,
    val barcodeSnapshot: String?,
    val quantity: Long,
    val unitPricePaise: Long,
    val unitCostPaise: Long,
    val discountPaise: Long,
    val lineTotalPaise: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "returns",
    foreignKeys = [ForeignKey(entity = SaleEntity::class, parentColumns = ["id", "shopId"], childColumns = ["saleId", "shopId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["saleId", "shopId"]), Index(value = ["id", "saleId", "shopId"], unique = true), Index(value = ["shopId", "createdAt"])],
)
data class ReturnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val shopId: Long,
    val reason: String,
    val refundPaise: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "return_items",
    foreignKeys = [
        ForeignKey(entity = ReturnEntity::class, parentColumns = ["id", "saleId", "shopId"], childColumns = ["returnId", "saleId", "shopId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = SaleItemEntity::class, parentColumns = ["id", "saleId", "shopId", "productId"], childColumns = ["saleItemId", "saleId", "shopId", "productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["returnId", "saleId", "shopId"]), Index(value = ["saleItemId", "saleId", "shopId", "productId"]), Index(value = ["id", "shopId", "productId"], unique = true)],
)
data class ReturnItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val returnId: Long,
    val saleId: Long,
    val shopId: Long,
    val saleItemId: Long,
    val productId: Long,
    val quantity: Long,
    val restockQuantity: Long,
    val unitRefundPaise: Long,
    val refundPaise: Long,
    val createdAt: Long,
)

/** Append-only stock ledger. Each committed quantity change has exactly one matching movement. */
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(entity = ShopProductEntity::class, parentColumns = ["shopId", "productId"], childColumns = ["shopId", "productId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = SaleItemEntity::class, parentColumns = ["id", "shopId", "productId"], childColumns = ["saleItemId", "shopId", "productId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReturnItemEntity::class, parentColumns = ["id", "shopId", "productId"], childColumns = ["returnItemId", "shopId", "productId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["shopId", "productId", "createdAt"]), Index(value = ["saleItemId", "shopId", "productId"]), Index(value = ["returnItemId", "shopId", "productId"])],
)
data class StockMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shopId: Long,
    val productId: Long,
    val delta: Long,
    val quantityAfter: Long,
    val type: String,
    val reason: String,
    val createdAt: Long,
    val saleItemId: Long? = null,
    val returnItemId: Long? = null,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [ForeignKey(entity = ShopEntity::class, parentColumns = ["id"], childColumns = ["shopId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["shopId", "incurredAt"])],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shopId: Long,
    val amountPaise: Long,
    val category: String,
    val description: String,
    val incurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Structured textual snapshots retain the before/after values of important catalog edits. */
@Entity(
    tableName = "audit_events",
    foreignKeys = [ForeignKey(entity = ShopEntity::class, parentColumns = ["id"], childColumns = ["shopId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("shopId"), Index(value = ["entityType", "entityId", "createdAt"])],
)
data class AuditEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String,
    val entityId: Long,
    val shopId: Long?,
    val action: String,
    val beforeValue: String?,
    val afterValue: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "app_settings",
    foreignKeys = [ForeignKey(entity = ShopEntity::class, parentColumns = ["id"], childColumns = ["selectedShopId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("selectedShopId")],
)
data class AppSettingsEntity(@PrimaryKey val id: Int = 1, val selectedShopId: Long? = null, val demoSeeded: Boolean = false)

@Entity(tableName = "sku_sequences")
data class SkuSequenceEntity(@PrimaryKey val prefix: String, val nextValue: Long)
