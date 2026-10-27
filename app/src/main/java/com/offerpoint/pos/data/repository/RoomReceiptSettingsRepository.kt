package com.offerpoint.pos.data.repository

import com.offerpoint.pos.data.local.database.withWriteTransaction
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.local.entity.AuditEventEntity
import com.offerpoint.pos.data.local.entity.ReceiptSettingsEntity
import com.offerpoint.pos.domain.model.ReceiptPaper
import com.offerpoint.pos.domain.model.ReceiptSettings
import com.offerpoint.pos.domain.repository.ReceiptSettingsRepository
import com.offerpoint.pos.domain.usecase.ReceiptValidation
import org.json.JSONObject

class RoomReceiptSettingsRepository(private val database: PosDatabase, private val now: () -> Long = System::currentTimeMillis) : ReceiptSettingsRepository {
    private val dao = database.posDao()
    override suspend fun getSettings(shopId: Long): ReceiptSettings = database.withWriteTransaction {
        require(dao.findShop(shopId) != null) { "This shop does not exist." }
        dao.getReceiptSettings(shopId)?.let { ReceiptSettings(it.shopId, ReceiptPaper.valueOf(it.paper), it.footer) }
            ?: ReceiptSettings(shopId)
    }

    override suspend fun saveSettings(settings: ReceiptSettings) {
        val valid = ReceiptValidation.settings(settings)
        database.withWriteTransaction {
            require(dao.findShop(valid.shopId) != null) { "This shop does not exist." }
            val previous = dao.getReceiptSettings(valid.shopId)
            if (previous?.paper == valid.paper.name && previous.footer == valid.footer) return@withWriteTransaction
            val next = ReceiptSettingsEntity(valid.shopId, valid.paper.name, valid.footer, now())
            dao.upsertReceiptSettings(next)
            dao.insertAudit(AuditEventEntity(entityType = "RECEIPT_SETTINGS", entityId = valid.shopId, shopId = valid.shopId,
                action = "SAVE_RECEIPT_SETTINGS", beforeValue = previous?.snapshot(), afterValue = next.snapshot(), createdAt = next.updatedAt))
        }
    }

    private fun ReceiptSettingsEntity.snapshot(): String = JSONObject().put("paper", paper).put("footer", footer).toString()
}
