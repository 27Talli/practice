package com.offerpoint.pos.data.repository

import android.database.sqlite.SQLiteException
import com.offerpoint.pos.data.local.database.withWriteTransaction
import com.offerpoint.pos.data.local.dao.ProductRow
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.entity.AppSettingsEntity
import com.offerpoint.pos.data.local.entity.AuditEventEntity
import com.offerpoint.pos.data.local.entity.InventoryEntity
import com.offerpoint.pos.data.local.entity.ProductBarcodeEntity
import com.offerpoint.pos.data.local.entity.ProductEntity
import com.offerpoint.pos.data.local.entity.ShopEntity
import com.offerpoint.pos.data.local.entity.ShopProductEntity
import com.offerpoint.pos.data.local.entity.SkuSequenceEntity
import com.offerpoint.pos.data.local.entity.StockMovementEntity
import com.offerpoint.pos.domain.model.BarcodeConflictException
import com.offerpoint.pos.domain.model.Product
import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.RegisteredProduct
import com.offerpoint.pos.domain.model.Shop
import com.offerpoint.pos.domain.model.StockException
import com.offerpoint.pos.domain.model.ValidationException
import com.offerpoint.pos.domain.repository.InventoryRepository
import com.offerpoint.pos.domain.repository.ProductRepository
import com.offerpoint.pos.domain.repository.ShopRepository
import com.offerpoint.pos.domain.usecase.ProductValidation
import com.offerpoint.pos.domain.usecase.StockValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Room is the source of truth, including the persisted shop selection. */
class RoomShopRepository(
    private val database: PosDatabase,
    private val demoMode: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) : ShopRepository {
    private val dao = database.posDao()

    override suspend fun initialize() {
        database.withWriteTransaction {
            val timestamp = now()
            dao.insertShops(listOf(
                ShopEntity(1, "OP", "Offer Point", "Shop No. 8, Yahya Khan Market, Neher Chowk, Mardan, Pakistan", timestamp, timestamp),
                ShopEntity(2, "FF", "Fancy Footwears", "Shops No. 9 & 10, Yahya Khan Market, Neher Chowk, Mardan, Pakistan", timestamp, timestamp),
            ))
            val previousSettings = dao.getSettings()
            val settings = (previousSettings ?: AppSettingsEntity()).let {
                // Offer Point is the primary workspace. An explicitly selected other shop is preserved.
                if (it.selectedShopId == null) it.copy(selectedShopId = 1) else it
            }
            if (settings != previousSettings) dao.upsertSettings(settings)
            // DI supplies a separate database file/application ID for the demo build.
            // This durable marker prevents edited sample products reappearing on restart.
            if (demoMode && !settings.demoSeeded) {
                val products = RoomProductRepository(database, now)
                products.createProduct(1, ProductDraft("DEMO · Lipstick", "Cosmetics", "Rose", "DEMO-OP-001", 20_000, 35_000, 12))
                products.createProduct(1, ProductDraft("DEMO · Perfume", "Perfumes", "30 ml", "DEMO-OP-002", 60_000, 85_000, 8))
                products.createProduct(2, ProductDraft("DEMO · Sandal", "Footwear", "Black · Size 38", "DEMO-FF-001", 90_000, 130_000, 6))
                dao.upsertSettings(settings.copy(demoSeeded = true))
            }
        }
    }

    override fun observeShops(): Flow<List<Shop>> = dao.observeShops().map { rows -> rows.map { it.toDomain() } }

    override fun observeSelectedShop(): Flow<Shop?> = dao.observeSelectedShop().map { it?.toDomain() }

    override suspend fun selectShop(shopId: Long) {
        database.withWriteTransaction {
            if (dao.findShop(shopId) == null) throw ValidationException("shop", "This shop does not exist.")
            dao.upsertSettings((dao.getSettings() ?: AppSettingsEntity()).copy(selectedShopId = shopId))
        }
    }

    override suspend fun databaseStatus(): String = withContext(Dispatchers.IO) {
        try {
            val sqlite = database.openHelper.readableDatabase
            sqlite.query("PRAGMA quick_check").use { cursor ->
                if (cursor.moveToFirst() && cursor.getString(0) == "ok") "Healthy · Room v${sqlite.version}" else "Needs attention"
            }
        } catch (_: SQLiteException) {
            "Unavailable"
        }
    }
}

class RoomProductRepository(
    private val database: PosDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : ProductRepository {
    private val dao = database.posDao()

    override fun observeProducts(shopId: Long, query: String): Flow<List<Product>> =
        dao.observeProducts(shopId, query.trim()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getProduct(shopId: Long, productId: Long): Product? = dao.getShopProduct(shopId, productId)?.toDomain()

    override suspend fun findByBarcode(barcode: String): RegisteredProduct? =
        dao.findByBarcode(barcode.trim())?.let { RegisteredProduct(it.id, it.sku, it.name, it.category, it.variant) }

    override suspend fun findProductForBarcode(shopId: Long, barcode: String): Product? =
        dao.findProductForBarcode(shopId, barcode.trim())?.toDomain()

    override suspend fun createProduct(shopId: Long, draft: ProductDraft): Long {
        val valid = ProductValidation.validate(draft)
        return database.withWriteTransaction {
            val shop = dao.findShop(shopId) ?: throw ValidationException("shop", "This shop does not exist.")
            valid.manufacturerBarcode?.let { ensureBarcodeAvailable(it) }
            val timestamp = now()
            val sku = nextSku(shop.code, valid.manufacturerBarcode)
            val productId = dao.insertProduct(ProductEntity(sku = sku, name = valid.name, category = valid.category,
                variant = valid.variant, createdAt = timestamp, updatedAt = timestamp))
            dao.insertBarcode(ProductBarcodeEntity(productId = productId, value = sku, kind = "INTERNAL_CODE128", createdAt = timestamp))
            valid.manufacturerBarcode?.let {
                dao.insertBarcode(ProductBarcodeEntity(productId = productId, value = it, kind = "MANUFACTURER", createdAt = timestamp))
            }
            createAssociation(shopId, productId, valid.purchaseCostPaise, valid.sellingPricePaise, valid.openingQuantity, timestamp)
            dao.insertAudit(AuditEventEntity(entityType = "PRODUCT", entityId = productId, shopId = shopId,
                action = "CREATE", beforeValue = null, afterValue = snapshot(sku, valid), createdAt = timestamp))
            productId
        }
    }

    override suspend fun updateProduct(shopId: Long, productId: Long, draft: ProductDraft) {
        val valid = ProductValidation.validate(draft)
        database.withWriteTransaction {
            val existing = dao.findProduct(productId) ?: throw ValidationException("product", "This product does not exist.")
            val previous = dao.getShopProduct(shopId, productId) ?: throw ValidationException("shop", "This product is not associated with this shop.")
            valid.manufacturerBarcode?.let {
                if (it == existing.sku) throw ValidationException("barcode", "The existing barcode must differ from the internal SKU.")
                ensureBarcodeAvailable(it, productId)
            }
            val timestamp = now()
            dao.updateProduct(existing.copy(name = valid.name, category = valid.category, variant = valid.variant, updatedAt = timestamp))
            dao.updateShopProductPrices(shopId, productId, valid.purchaseCostPaise, valid.sellingPricePaise, timestamp)
            val oldBarcode = dao.barcodeForProduct(productId)?.value
            if (oldBarcode != valid.manufacturerBarcode) {
                dao.deleteManufacturerBarcodes(productId)
                valid.manufacturerBarcode?.let {
                    dao.insertBarcode(ProductBarcodeEntity(productId = productId, value = it, kind = "MANUFACTURER", createdAt = timestamp))
                }
            }
            val before = ProductDraft(previous.name, previous.category, previous.variant, oldBarcode,
                previous.purchaseCostPaise, previous.sellingPricePaise)
            // Opening stock is a creation field. Editing metadata never changes stock.
            dao.insertAudit(AuditEventEntity(entityType = "PRODUCT", entityId = productId, shopId = shopId,
                action = "EDIT_SHARED_METADATA_AND_SHOP_PRICES", beforeValue = snapshot(existing.sku, before),
                afterValue = snapshot(existing.sku, valid), createdAt = timestamp))
        }
    }

    override suspend fun associateProduct(shopId: Long, productId: Long, purchaseCostPaise: Long, sellingPricePaise: Long, openingQuantity: Long) {
        database.withWriteTransaction {
            if (dao.findShop(shopId) == null) throw ValidationException("shop", "This shop does not exist.")
            val product = dao.findProduct(productId) ?: throw ValidationException("product", "This product does not exist.")
            val valid = ProductValidation.validate(ProductDraft(product.name, product.category, product.variant,
                purchaseCostPaise = purchaseCostPaise, sellingPricePaise = sellingPricePaise, openingQuantity = openingQuantity))
            if (dao.getAssociation(shopId, productId) != null) throw ValidationException("product", "This product is already associated with this shop.")
            val timestamp = now()
            createAssociation(shopId, productId, valid.purchaseCostPaise, valid.sellingPricePaise, valid.openingQuantity, timestamp)
            dao.insertAudit(AuditEventEntity(entityType = "SHOP_PRODUCT", entityId = productId, shopId = shopId,
                action = "ASSOCIATE", beforeValue = null, afterValue = snapshot(product.sku, valid), createdAt = timestamp))
        }
    }

    private suspend fun ensureBarcodeAvailable(barcode: String, allowedProductId: Long? = null) {
        val registered = dao.findBarcodeByValue(barcode) ?: return
        if (registered.productId != allowedProductId) throw BarcodeConflictException(barcode)
        if (registered.kind != "MANUFACTURER") {
            throw ValidationException("barcode", "This code is already used by an internal label. Keep the manufacturer's barcode separate.")
        }
    }

    private suspend fun nextSku(prefix: String, pendingBarcode: String?): String {
        val sequence = dao.getSequence(prefix)
        var number = sequence?.nextValue ?: 1L
        var sku: String
        do {
            if (number <= 0 || number == Long.MAX_VALUE) throw ValidationException("sku", "The SKU sequence is exhausted.")
            sku = "$prefix-${number.toString().padStart(6, '0')}"
            number++
        } while (sku == pendingBarcode || dao.findByBarcode(sku) != null)
        val next = SkuSequenceEntity(prefix, number)
        if (sequence == null) dao.insertSequence(next) else dao.updateSequence(next)
        return sku
    }

    private suspend fun createAssociation(shopId: Long, productId: Long, cost: Long, price: Long, quantity: Long, timestamp: Long) {
        dao.insertShopProduct(ShopProductEntity(shopId, productId, cost, price, createdAt = timestamp, updatedAt = timestamp))
        dao.insertInventory(InventoryEntity(shopId, productId, quantity, timestamp))
        dao.insertMovement(StockMovementEntity(shopId = shopId, productId = productId, delta = quantity,
            quantityAfter = quantity, type = "OPENING", reason = "Opening stock", createdAt = timestamp))
    }
}

class RoomInventoryRepository(
    private val database: PosDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : InventoryRepository {
    private val dao = database.posDao()

    override suspend fun adjustStock(shopId: Long, productId: Long, delta: Long, reason: String) {
        val validReason = StockValidation.reason(reason)
        database.withWriteTransaction {
            val inventory = dao.getInventory(shopId, productId) ?: throw StockException("This product is not in the selected shop's inventory.")
            val result = StockValidation.resultingQuantity(inventory.quantity, delta)
            val timestamp = now()
            if (dao.updateInventoryQty(shopId, productId, result, timestamp) != 1) throw StockException("The stock could not be updated.")
            dao.insertMovement(StockMovementEntity(shopId = shopId, productId = productId, delta = delta,
                quantityAfter = result, type = "ADJUSTMENT", reason = validReason, createdAt = timestamp))
        }
    }
}

private fun ShopEntity.toDomain() = Shop(id, code, name, address)

private fun ProductRow.toDomain() = Product(id, shopId, sku, name, category, variant,
    manufacturerBarcode, purchaseCostPaise, sellingPricePaise, quantity)

/** Human-readable audit snapshots retain exact integer paise and escape user-entered text. */
private fun snapshot(sku: String, draft: ProductDraft): String =
    "{\"sku\":${sku.jsonString()},\"name\":${draft.name.jsonString()},\"category\":${draft.category.jsonString()}," +
        "\"variant\":${draft.variant.jsonString()},\"manufacturerBarcode\":${draft.manufacturerBarcode?.jsonString() ?: "null"}," +
        "\"purchaseCostPaise\":${draft.purchaseCostPaise},\"sellingPricePaise\":${draft.sellingPricePaise}}"

private fun String.jsonString(): String = buildString {
    append('"')
    for (character in this@jsonString) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 32) append("\\u${character.code.toString(16).padStart(4, '0')}") else append(character)
        }
    }
    append('"')
}
