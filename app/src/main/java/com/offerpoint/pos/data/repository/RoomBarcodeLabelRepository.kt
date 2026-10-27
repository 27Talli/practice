package com.offerpoint.pos.data.repository

import com.offerpoint.pos.data.local.database.withWriteTransaction
import com.offerpoint.pos.data.local.dao.ProductLabelRow
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.entity.AuditEventEntity
import com.offerpoint.pos.data.local.entity.CostCodeSettingsEntity
import com.offerpoint.pos.data.local.entity.ProductBarcodeEntity
import com.offerpoint.pos.data.local.entity.ProductLabelEntity
import com.offerpoint.pos.domain.model.CostCodeSettings
import com.offerpoint.pos.domain.model.InternalBarcodeLabel
import com.offerpoint.pos.domain.model.ValidationException
import com.offerpoint.pos.domain.repository.BarcodeLabelRepository
import com.offerpoint.pos.domain.usecase.CostCode
import java.security.SecureRandom

/** Globally registered aliases and their cost snapshots commit together; old labels are never rewritten. */
class RoomBarcodeLabelRepository(
    private val database: PosDatabase,
    private val demoMode: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis,
) : BarcodeLabelRepository {
    private val dao = database.posDao()
    private val random = SecureRandom()

    override suspend fun getOrCreateCostCodeSettings(shopId: Long): CostCodeSettings = database.withWriteTransaction {
        val settings = ensureSettings(shopId)
        CostCodeSettings(settings.shopId, settings.alphabet)
    }

    override suspend fun updateCostCodeAlphabet(shopId: Long, alphabet: String) {
        val valid = CostCode.validateAlphabet(alphabet)
        database.withWriteTransaction {
            requireShop(shopId)
            val previous = dao.getCostCodeSettings(shopId)
            if (previous?.alphabet == valid) return@withWriteTransaction
            val timestamp = now()
            if (previous == null) {
                dao.insertCostCodeSettings(CostCodeSettingsEntity(shopId, valid, timestamp, timestamp))
            } else if (dao.updateCostCodeSettings(previous.copy(alphabet = valid, updatedAt = timestamp)) != 1) {
                throw ValidationException("costCode", "The purchase-cost code could not be saved.")
            }
            dao.insertAudit(AuditEventEntity(
                entityType = "COST_CODE_SETTINGS", entityId = shopId, shopId = shopId,
                action = if (previous == null) "CREATE_COST_CODE_KEY" else "CHANGE_COST_CODE_KEY",
                beforeValue = previous?.let { keySnapshot(it.alphabet) },
                afterValue = keySnapshot(valid), createdAt = timestamp,
            ))
        }
    }

    override suspend fun createLabel(shopId: Long, productId: Long): InternalBarcodeLabel = database.withWriteTransaction {
        requireShop(shopId)
        val product = dao.findProduct(productId)
            ?: throw ValidationException("product", "This product does not exist.")
        val association = dao.getAssociation(shopId, productId)
            ?: throw ValidationException("shop", "This product is not stocked by the selected shop.")
        if (!product.isActive || !association.isActive) {
            throw ValidationException("product", "Only active products can receive a new barcode label.")
        }
        if (association.purchaseCostPaise < 0) {
            throw ValidationException("cost", "Correct this product's purchase cost before creating a label.")
        }
        if (product.sku.any { it.code !in 32..126 }) {
            throw ValidationException("sku", "The internal SKU cannot be represented by Code 128.")
        }
        val settings = ensureSettings(shopId)
        val code = CostCode.encode(association.purchaseCostPaise, settings.alphabet)
        dao.findMatchingCostLabelId(shopId,productId,product.sku,product.name,product.variant,association.purchaseCostPaise,code,settings.alphabet,demoMode)?.let { id ->
            return@withWriteTransaction dao.getLabelRow(shopId,id)?.toDomain() ?: error("The registered label could not be read.")
        }
        // A physical Demo sticker must never resolve to a Live alias, even if both keys encode zero alike.
        val base = (if (demoMode) "DEMO~" else "") + "${product.sku}~$code"
        var value: String
        var attempts = 0
        while (true) {
            // Random revisions avoid recreating a lost printed alias after an older backup is restored.
            // Twenty decimal digits keep the 64-bit ID compact in Code 128's numeric encoding.
            val revision=java.lang.Long.toUnsignedString(random.nextLong()).padStart(20,'0')
            value = "$base~$revision"
            if (value.length > 80) throw ValidationException("barcode", "This internal barcode is too long for a Code 128 label.")
            val existing = dao.findBarcodeByValue(value)
            if (existing == null) break
            if (++attempts >= 32) throw ValidationException("barcode", "No unique internal label value is available. Try again.")
        }
        val timestamp = now()
        val barcodeId = dao.insertBarcode(ProductBarcodeEntity(
            productId = productId, value = value, kind = "COST_LABEL", createdAt = timestamp,
        ))
        dao.insertProductLabel(ProductLabelEntity(
            barcodeId = barcodeId, shopId = shopId, productId = productId,
            skuSnapshot = product.sku, nameSnapshot = product.name, variantSnapshot = product.variant,
            purchaseCostPaise = association.purchaseCostPaise, costCode = code,
            alphabetSnapshot = settings.alphabet, createdAt = timestamp,
        ))
        dao.insertAudit(AuditEventEntity(
            entityType = "PRODUCT_LABEL", entityId = barcodeId, shopId = shopId,
            action = "CREATE_CODED_COST_LABEL", beforeValue = null,
            afterValue = "{\"barcodeId\":$barcodeId,\"productId\":$productId,\"purchaseCostPaise\":${association.purchaseCostPaise}}",
            createdAt = timestamp,
        ))
        dao.getLabelRow(shopId, barcodeId)?.toDomain()
            ?: throw ValidationException("barcode", "The label could not be read back; no changes were saved.")
    }

    override suspend fun getLabel(shopId: Long, barcodeId: Long): InternalBarcodeLabel? =
        dao.getLabelRow(shopId, barcodeId)?.toDomain()

    override suspend fun findLabelByValue(value: String): InternalBarcodeLabel? =
        dao.findLabelRowByValue(value.trim())?.toDomain()

    private suspend fun requireShop(shopId: Long) {
        if (dao.findShop(shopId) == null) throw ValidationException("shop", "This shop does not exist.")
    }

    private suspend fun ensureSettings(shopId: Long): CostCodeSettingsEntity {
        requireShop(shopId)
        dao.getCostCodeSettings(shopId)?.let {
            CostCode.validateAlphabet(it.alphabet)
            return it
        }
        // A random key is generated per local shop, not embedded as a public APK-wide secret.
        val letters = "ABCDEFGHJKLMNPQRSTUVWXYZ".toMutableList()
        val random = SecureRandom()
        for (index in letters.lastIndex downTo 1) {
            val other = random.nextInt(index + 1)
            val character = letters[index]
            letters[index] = letters[other]
            letters[other] = character
        }
        val timestamp = now()
        val settings = CostCodeSettingsEntity(shopId, letters.take(10).joinToString(""), timestamp, timestamp)
        dao.insertCostCodeSettings(settings)
        dao.insertAudit(AuditEventEntity(
            entityType = "COST_CODE_SETTINGS", entityId = shopId, shopId = shopId,
            action = "CREATE_COST_CODE_KEY", beforeValue = null,
            afterValue = keySnapshot(settings.alphabet), createdAt = timestamp,
        ))
        return settings
    }

    private fun keySnapshot(alphabet: String): String = "{\"alphabet\":\"$alphabet\"}"
}

private fun ProductLabelRow.toDomain() = InternalBarcodeLabel(
    barcodeId, shopId, productId, sku, name, variant, barcodeValue,
    costCode, purchaseCostPaise, alphabetSnapshot, createdAt,
)
