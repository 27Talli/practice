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
    val reorderLevel: Long,
)

/** The immutable printed label, joined only to its globally registered barcode value. */
data class ProductLabelRow(
    val barcodeId: Long,
    val shopId: Long,
    val productId: Long,
    val sku: String,
    val name: String,
    val variant: String,
    val barcodeValue: String,
    val costCode: String,
    val purchaseCostPaise: Long,
    val alphabetSnapshot: String,
    val createdAt: Long,
)

@Dao
interface PosDao {
    @Query("SELECT COALESCE((SELECT dataGeneration FROM app_settings WHERE id = 1), '')")
    fun observeDataGeneration(): Flow<String>

    @Query("SELECT * FROM receipt_settings WHERE shopId = :shopId")
    suspend fun getReceiptSettings(shopId: Long): ReceiptSettingsEntity?

    @Upsert
    suspend fun upsertReceiptSettings(settings: ReceiptSettingsEntity)

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

    @Query("SELECT * FROM product_barcodes WHERE value = :value LIMIT 1")
    suspend fun findBarcodeByValue(value: String): ProductBarcodeEntity?

    @Query("""
        SELECT p.id, sp.shopId, p.sku, p.name, p.category, p.variant,
            (SELECT b.value FROM product_barcodes b WHERE b.productId = p.id AND b.kind = 'MANUFACTURER' ORDER BY b.id LIMIT 1) AS manufacturerBarcode,
            sp.purchaseCostPaise, sp.sellingPricePaise, i.quantity, sp.reorderLevel
        FROM product_barcodes scanned
        JOIN products p ON p.id = scanned.productId
        JOIN shop_products sp ON sp.productId = p.id
        JOIN inventory i ON i.shopId = sp.shopId AND i.productId = sp.productId
        WHERE scanned.value = :value AND sp.shopId = :shopId AND p.isActive = 1 AND sp.isActive = 1
        LIMIT 1
    """)
    suspend fun findProductForBarcode(shopId: Long, value: String): ProductRow?

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

    @Query("UPDATE shop_products SET reorderLevel=:level, updatedAt=:timestamp WHERE shopId=:shopId AND productId=:productId")
    suspend fun setReorderLevel(shopId:Long,productId:Long,level:Long,timestamp:Long):Int

    // instr() treats search input literally, including '%' and '_'; barcode text retains leading zeros.
    @Query("""
        SELECT p.id, sp.shopId, p.sku, p.name, p.category, p.variant,
            (SELECT b.value FROM product_barcodes b WHERE b.productId = p.id AND b.kind = 'MANUFACTURER' ORDER BY b.id LIMIT 1) AS manufacturerBarcode,
            sp.purchaseCostPaise, sp.sellingPricePaise, i.quantity, sp.reorderLevel
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
            sp.purchaseCostPaise, sp.sellingPricePaise, i.quantity, sp.reorderLevel
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

    @Query("SELECT * FROM cost_code_settings WHERE shopId = :shopId")
    suspend fun getCostCodeSettings(shopId: Long): CostCodeSettingsEntity?

    @Insert
    suspend fun insertCostCodeSettings(settings: CostCodeSettingsEntity)

    @Update
    suspend fun updateCostCodeSettings(settings: CostCodeSettingsEntity): Int

    @Insert
    suspend fun insertProductLabel(label: ProductLabelEntity)

    @Query("SELECT * FROM product_labels WHERE barcodeId = :barcodeId")
    suspend fun getProductLabel(barcodeId: Long): ProductLabelEntity?

    @Query("""
        SELECT l.barcodeId FROM product_labels l JOIN product_barcodes b ON b.id=l.barcodeId AND b.productId=l.productId
        WHERE l.shopId=:shopId AND l.productId=:productId AND l.skuSnapshot=:sku AND l.nameSnapshot=:name AND l.variantSnapshot=:variant
          AND l.purchaseCostPaise=:cost AND l.costCode=:code AND l.alphabetSnapshot=:alphabet AND b.kind='COST_LABEL'
          AND (b.value LIKE 'DEMO~%')=:demo
        ORDER BY l.barcodeId DESC LIMIT 1
    """)
    suspend fun findMatchingCostLabelId(shopId: Long, productId: Long, sku: String, name: String, variant: String, cost: Long, code: String, alphabet: String, demo: Boolean): Long?

    @Query("""
        SELECT l.barcodeId, l.shopId, l.productId, l.skuSnapshot AS sku,
            l.nameSnapshot AS name, l.variantSnapshot AS variant, b.value AS barcodeValue,
            l.costCode, l.purchaseCostPaise, l.alphabetSnapshot, l.createdAt
        FROM product_labels l
        JOIN product_barcodes b ON b.id = l.barcodeId AND b.productId = l.productId
        WHERE l.shopId = :shopId AND l.barcodeId = :barcodeId
        LIMIT 1
    """)
    suspend fun getLabelRow(shopId: Long, barcodeId: Long): ProductLabelRow?

    @Query("""
        SELECT l.barcodeId, l.shopId, l.productId, l.skuSnapshot AS sku,
            l.nameSnapshot AS name, l.variantSnapshot AS variant, b.value AS barcodeValue,
            l.costCode, l.purchaseCostPaise, l.alphabetSnapshot, l.createdAt
        FROM product_labels l
        JOIN product_barcodes b ON b.id = l.barcodeId AND b.productId = l.productId
        WHERE b.value = :value
        LIMIT 1
    """)
    suspend fun findLabelRowByValue(value: String): ProductLabelRow?

    @Insert
    suspend fun insertReturn(productReturn: ReturnEntity): Long

    @Insert
    suspend fun insertReturnItem(item: ReturnItemEntity): Long

    @Insert
    suspend fun insertExpense(expense: ExpenseEntity): Long

    @Query("SELECT * FROM returns WHERE requestId=:requestId LIMIT 1")
    suspend fun findRefundRequest(requestId:String):ReturnEntity?

    @Query("SELECT * FROM returns WHERE shopId=:shopId AND id=:id")
    suspend fun getRefund(shopId:Long,id:Long):ReturnEntity?

    @Query("SELECT * FROM returns WHERE shopId=:shopId ORDER BY createdAt DESC,id DESC")
    fun observeRefunds(shopId:Long):Flow<List<ReturnEntity>>

    @Query("SELECT * FROM return_items WHERE returnId=:id AND shopId=:shopId ORDER BY id")
    suspend fun getRefundItems(shopId:Long,id:Long):List<ReturnItemEntity>

    @Query("SELECT COALESCE(SUM(quantity),0) FROM return_items WHERE saleItemId=:itemId AND shopId=:shopId")
    suspend fun returnedQuantity(shopId:Long,itemId:Long):Long

    @Query("SELECT * FROM return_sequences WHERE shopId=:shopId")
    suspend fun getReturnSequence(shopId:Long):ReturnSequenceEntity?

    @Upsert
    suspend fun upsertReturnSequence(sequence:ReturnSequenceEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM returns WHERE shopId=:shopId AND receiptNumber=:reference)")
    suspend fun refundReferenceExists(shopId:Long,reference:String):Boolean

    @Query("SELECT * FROM expenses WHERE requestId=:requestId LIMIT 1")
    suspend fun findExpenseRequest(requestId:String):ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE shopId=:shopId AND id=:id")
    suspend fun getExpense(shopId:Long,id:Long):ExpenseEntity?

    @Update
    suspend fun updateExpense(expense:ExpenseEntity):Int

    @Query("SELECT * FROM expenses WHERE shopId=:shopId ORDER BY incurredAt DESC,id DESC")
    fun observeExpenses(shopId:Long):Flow<List<ExpenseEntity>>

    @Query("SELECT (SELECT COUNT(*) FROM sales WHERE shopId=:shopId)+(SELECT COUNT(*) FROM returns WHERE shopId=:shopId)+(SELECT COUNT(*) FROM expenses WHERE shopId=:shopId)+(SELECT COUNT(*) FROM inventory WHERE shopId=:shopId)+(SELECT COUNT(*) FROM shop_products WHERE shopId=:shopId)")
    fun observeFinanceChanges(shopId:Long):Flow<Long>

    @Query("SELECT * FROM sales WHERE shopId=:shopId AND status='COMPLETED' AND completedAt>=:start AND completedAt<:end ORDER BY id")
    suspend fun reportSales(shopId:Long,start:Long,end:Long):List<SaleEntity>

    @Query("SELECT * FROM returns WHERE shopId=:shopId AND createdAt>=:start AND createdAt<:end ORDER BY id")
    suspend fun reportRefunds(shopId:Long,start:Long,end:Long):List<ReturnEntity>

    @Query("SELECT * FROM expenses WHERE shopId=:shopId AND incurredAt>=:start AND incurredAt<:end AND isVoided=0")
    suspend fun reportExpenses(shopId:Long,start:Long,end:Long):List<ExpenseEntity>

    @Query("SELECT * FROM owner_security WHERE id=1")
    suspend fun getSecurity():OwnerSecurityEntity?

    @Query("SELECT * FROM owner_security WHERE id=1")
    fun observeSecurity():Flow<OwnerSecurityEntity?>

    @Upsert
    suspend fun upsertSecurity(settings:OwnerSecurityEntity)
}
