package com.offerpoint.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.offerpoint.pos.data.local.entity.*
import kotlinx.coroutines.flow.Flow

/** A shop-scoped projection: registry metadata plus that shop's prices and current stock. */
data class ProductRow(
    val id: Long,
    val shopId: Long,
    val sku: String,
    val name: String,
    val category: String,
    val variant: String,
    val manufacturerBarcode: String?,
    val purchaseCostPaise: Long,
    val sellingPricePaise: Long,
    val quantity: Long,
)

@Dao
interface PosDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShops(shops: List<ShopEntity>)

    @Query("SELECT * FROM shops ORDER BY id")
    fun observeShops(): Flow<List<ShopEntity>>

    @Query("SELECT * FROM shops WHERE id = :id")
    suspend fun findShop(id: Long): ShopEntity?

    @Query("SELECT s.* FROM shops s JOIN app_settings a ON a.selectedShopId = s.id WHERE a.id = 1")
    fun observeSelectedShop(): Flow<ShopEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun getSettings(): AppSettingsEntity?

    @Upsert
    suspend fun upsertSettings(settings: AppSettingsEntity)

    @Query("SELECT COUNT(*) FROM shops")
    suspend fun countShops(): Int

    @Query("SELECT COUNT(*) FROM products")
    suspend fun countProducts(): Int

    @Query("SELECT * FROM sku_sequences WHERE prefix = :prefix")
    suspend fun getSequence(prefix: String): SkuSequenceEntity?

    @Insert
    suspend fun insertSequence(sequence: SkuSequenceEntity)

    @Update
    suspend fun updateSequence(sequence: SkuSequenceEntity): Int

    @Insert
    suspend fun insertProduct(product: ProductEntity): Long

    @Update
    suspend fun updateProduct(product: ProductEntity): Int

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun findProduct(id: Long): ProductEntity?

    @Insert
    suspend fun insertBarcode(barcode: ProductBarcodeEntity): Long

    @Query("SELECT p.* FROM products p JOIN product_barcodes b ON b.productId = p.id WHERE b.value = :value LIMIT 1")
    suspend fun findByBarcode(value: String): ProductEntity?

    @Query("SELECT * FROM product_barcodes WHERE productId = :productId AND kind = 'MANUFACTURER' ORDER BY id LIMIT 1")
    suspend fun barcodeForProduct(productId: Long): ProductBarcodeEntity?

    @Query("DELETE FROM product_barcodes WHERE productId = :productId AND kind = 'MANUFACTURER'")
    suspend fun deleteManufacturerBarcodes(productId: Long): Int

    @Insert
    suspend fun insertShopProduct(product: ShopProductEntity)

    @Query("SELECT * FROM shop_products WHERE shopId = :shopId AND productId = :productId")
    suspend fun getAssociation(shopId: Long, productId: Long): ShopProductEntity?

    @Query("UPDATE shop_products SET purchaseCostPaise = :purchaseCostPaise, sellingPricePaise = :sellingPricePaise, updatedAt = :updatedAt WHERE shopId = :shopId AND productId = :productId")
    suspend fun updateShopProductPrices(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, updatedAt: Long): Int

    // instr() treats search input literally, including '%' and '_'; barcode text retains leading zeros.
    @Query("""
        SELECT p.id, sp.shopId, p.sku, p.name, p.category, p.variant,
            (SELECT b.value FROM product_barcodes b WHERE b.productId = p.id AND b.kind = 'MANUFACTURER' ORDER BY b.id LIMIT 1) AS manufacturerBarcode,
            sp.purchaseCostPaise, sp.sellingPricePaise, i.quantity
        FROM products p
        JOIN shop_products sp ON sp.productId = p.id
        JOIN inventory i ON i.shopId = sp.shopId AND i.productId = sp.productId
        WHERE sp.shopId = :shopId AND p.isActive = 1 AND sp.isActive = 1
        AND (:query = '' OR instr(lower(p.name), lower(:query)) > 0
            OR instr(lower(p.category), lower(:query)) > 0
            OR instr(lower(p.variant), lower(:query)) > 0
            OR instr(lower(p.sku), lower(:query)) > 0
            OR EXISTS (SELECT 1 FROM product_barcodes b WHERE b.productId = p.id AND instr(lower(b.value), lower(:query)) > 0))
        ORDER BY p.name COLLATE NOCASE, p.id
    """)
    fun observeProducts(shopId: Long, query: String): Flow<List<ProductRow>>

    @Query("""
        SELECT p.id, sp.shopId, p.sku, p.name, p.category, p.variant,
            (SELECT b.value FROM product_barcodes b WHERE b.productId = p.id AND b.kind = 'MANUFACTURER' ORDER BY b.id LIMIT 1) AS manufacturerBarcode,
            sp.purchaseCostPaise, sp.sellingPricePaise, i.quantity
        FROM products p
        JOIN shop_products sp ON sp.productId = p.id
        JOIN inventory i ON i.shopId = sp.shopId AND i.productId = sp.productId
        WHERE sp.shopId = :shopId AND p.id = :productId
    """)
    suspend fun getShopProduct(shopId: Long, productId: Long): ProductRow?

    @Insert
    suspend fun insertInventory(inventory: InventoryEntity)

    @Query("SELECT * FROM inventory WHERE shopId = :shopId AND productId = :productId")
    suspend fun getInventory(shopId: Long, productId: Long): InventoryEntity?

    // Also reject negative quantities at the mutation statement, independently of UI validation.
    @Query("UPDATE inventory SET quantity = :quantity, updatedAt = :updatedAt WHERE shopId = :shopId AND productId = :productId AND :quantity >= 0")
    suspend fun updateInventoryQty(shopId: Long, productId: Long, quantity: Long, updatedAt: Long): Int

    @Insert
    suspend fun insertMovement(movement: StockMovementEntity): Long

    @Query("SELECT * FROM stock_movements WHERE shopId = :shopId AND productId = :productId ORDER BY id")
    suspend fun getMovements(shopId: Long, productId: Long): List<StockMovementEntity>

    @Insert
    suspend fun insertAudit(event: AuditEventEntity): Long

    @Query("SELECT * FROM audit_events ORDER BY id")
    suspend fun getAudits(): List<AuditEventEntity>

    @Query("SELECT * FROM audit_events WHERE entityType = :entityType AND entityId = :entityId ORDER BY id")
    suspend fun getAudits(entityType: String, entityId: Long): List<AuditEventEntity>

    // Future transactional use cases will combine these writes with inventory and ledger updates.
    @Insert
    suspend fun insertSale(sale: SaleEntity): Long

    @Insert
    suspend fun insertSaleItem(item: SaleItemEntity): Long

    // Request lookup is deliberately global: reusing a request in another shop must be rejected.
    @Query("SELECT * FROM sales WHERE checkoutRequestId = :requestId LIMIT 1")
    suspend fun findSaleByRequest(requestId: String): SaleEntity?

    @Query("SELECT * FROM sales WHERE shopId = :shopId AND id = :saleId AND status = 'COMPLETED' AND completedAt IS NOT NULL")
    suspend fun getSale(shopId: Long, saleId: Long): SaleEntity?

    @Query("SELECT * FROM sale_items WHERE shopId = :shopId AND saleId = :saleId ORDER BY id")
    suspend fun getSaleItems(shopId: Long, saleId: Long): List<SaleItemEntity>

    @Query("SELECT * FROM sales WHERE shopId = :shopId AND status = 'COMPLETED' AND completedAt IS NOT NULL ORDER BY completedAt DESC, id DESC")
    fun observeSales(shopId: Long): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales WHERE shopId = :shopId AND status = 'COMPLETED' AND completedAt >= :startInclusive AND completedAt < :endExclusive ORDER BY completedAt DESC, id DESC")
    fun observeSalesInRange(shopId: Long, startInclusive: Long, endExclusive: Long): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sales_sequences WHERE shopId = :shopId")
    suspend fun getSaleSequence(shopId: Long): SaleSequenceEntity?

    @Insert
    suspend fun insertSaleSequence(sequence: SaleSequenceEntity)

    @Update
    suspend fun updateSaleSequence(sequence: SaleSequenceEntity): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sales WHERE shopId = :shopId AND receiptNumber = :receiptNumber)")
    suspend fun receiptExists(shopId: Long, receiptNumber: String): Boolean

    @Query("SELECT COUNT(*) FROM sales")
    suspend fun countSales(): Int

    @Insert
    suspend fun insertReturn(productReturn: ReturnEntity): Long

    @Insert
    suspend fun insertReturnItem(item: ReturnItemEntity): Long

    @Insert
    suspend fun insertExpense(expense: ExpenseEntity): Long
}
