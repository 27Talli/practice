package com.offerpoint.pos

import com.offerpoint.pos.domain.model.*
import com.offerpoint.pos.domain.usecase.BuildReceipt

object ReceiptAndroidFixtures {
    val shop = Shop(1, "OP", "Offer Point", "Shop No. 8, Yahya Khan Market, Neher Chowk, Mardan, Pakistan")
    fun receipt(count: Int = 30): ReceiptDocument {
        val total = count * 1_000L
        val sale = SaleDetails(SaleSummary(1, 1, "OP-S-000001", total, 1), total, 0, 0, total + 500, 500, "CASH",
            (1..count).map { SoldItem(it.toLong(), "OP-${it.toString().padStart(6, '0')}",
                "Perfume with a long product description $it " + "Rose ".repeat(9), "50 ml", 2, 500, 9_876_543, 0, 1_000) })
        return BuildReceipt.create(shop, sale, ReceiptSettings(1), true)
    }
}
