package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.CostCodeSettings
import com.offerpoint.pos.domain.model.InternalBarcodeLabel

interface BarcodeLabelRepository {
    /** A random per-shop code is created once, stored locally, and never printed on labels. */
    suspend fun getOrCreateCostCodeSettings(shopId: Long): CostCodeSettings
    suspend fun updateCostCodeAlphabet(shopId: Long, alphabet: String)
    /** Registers the generated Code 128 value globally before returning an immutable label snapshot. */
    suspend fun createLabel(shopId: Long, productId: Long): InternalBarcodeLabel
    suspend fun getLabel(shopId: Long, barcodeId: Long): InternalBarcodeLabel?
    suspend fun findLabelByValue(value: String): InternalBarcodeLabel?
}
