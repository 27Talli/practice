package com.offerpoint.pos.domain.usecase

import java.util.Locale

/** QR codes contain only the printed reference, never a URL, price, or an instruction to post a refund. */
object ReceiptReference {
    fun originalSale(value: String, shopCode: String): String {
        val reference = value.trim().uppercase(Locale.ROOT)
        require(reference.length <= 64 && reference.none(Char::isISOControl)) { "Scan or enter a valid original sale receipt number." }
        require(!reference.startsWith("$shopCode-R-")) { "This is a refund receipt. Use the original sale number printed on it to return more items." }
        require(reference.startsWith("$shopCode-S-")) { "Use an original sale receipt from the selected shop ($shopCode)." }
        val digits = reference.removePrefix("$shopCode-S-")
        require(digits.length in 6..19 && digits.all { it in '0'..'9' } && (digits.toLongOrNull() ?: 0) > 0) {
            "Scan or enter a valid original sale receipt number, such as $shopCode-S-000001."
        }
        return reference
    }
}
