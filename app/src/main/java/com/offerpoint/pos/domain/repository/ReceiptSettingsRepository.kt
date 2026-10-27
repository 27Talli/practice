package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.ReceiptSettings

interface ReceiptSettingsRepository {
    suspend fun getSettings(shopId: Long): ReceiptSettings
    suspend fun saveSettings(settings: ReceiptSettings)
}
