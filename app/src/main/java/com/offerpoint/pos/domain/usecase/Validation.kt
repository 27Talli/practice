package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.ProductDraft
import com.offerpoint.pos.domain.model.StockException
import com.offerpoint.pos.domain.model.ValidationException
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Money crosses the UI boundary as decimal text and is stored as integer paise. */
object Money {
    private val amountPattern = Regex("[0-9]+(?:\\.[0-9]{1,2})?")

    fun parse(text: String): Long {
        val value = text.trim()
        if (!amountPattern.matches(value)) {
            throw ValidationException("price", "Enter a non-negative price with at most two decimal places.")
        }
        return try {
            BigDecimal(value).movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            throw ValidationException("price", "This price is too large.")
        }
    }

    fun input(paise: Long): String = BigDecimal.valueOf(paise, 2).toPlainString()

    fun format(paise: Long): String {
        val number = NumberFormat.getNumberInstance(Locale("en", "PK")).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            isGroupingUsed = true
        }
        return "Rs. ${number.format(BigDecimal.valueOf(paise, 2))}"
    }
}

object Quantity {
    fun parse(text: String): Long {
        val value = text.trim()
        if (!Regex("[0-9]+").matches(value)) {
            throw ValidationException("quantity", "Enter a non-negative whole-item quantity.")
        }
        return value.toLongOrNull()
            ?: throw ValidationException("quantity", "This quantity is too large.")
    }
}

object ProductValidation {
    fun validate(draft: ProductDraft): ProductDraft {
        if (draft.name.isBlank()) throw ValidationException("name", "Product name is required.")
        if (draft.category.isBlank()) throw ValidationException("category", "Category is required.")
        if(draft.name.length>120 || draft.category.length>80 || draft.variant.length>120) throw ValidationException("name","Use up to 120 characters for a product name/variant and 80 for its category.")
        if (draft.purchaseCostPaise < 0) throw ValidationException("cost", "Purchase cost cannot be negative.")
        if (draft.sellingPricePaise < 0) throw ValidationException("price", "Selling price cannot be negative.")
        if (draft.reorderLevel < 0) throw ValidationException("reorderLevel", "Low-stock alert level cannot be negative.")
        if (draft.openingQuantity < 0) throw ValidationException("quantity", "Opening stock cannot be negative.")
        val barcode = draft.manufacturerBarcode?.trim()?.takeIf { it.isNotEmpty() }
        if (barcode != null && (barcode.length > 128 || barcode.any { it.isISOControl() })) {
            throw ValidationException("barcode", "Use a barcode of at most 128 characters without control characters.")
        }
        return draft.copy(name = draft.name.trim(), category = draft.category.trim(),
            variant = draft.variant.trim(), manufacturerBarcode = barcode)
    }
}

object StockValidation {
    fun reason(reason: String): String = reason.trim().takeIf { it.isNotEmpty() }
        ?: throw StockException("A stock adjustment reason is required.")

    fun resultingQuantity(current: Long, delta: Long): Long {
        if (delta == 0L) throw StockException("The stock adjustment must change the quantity.")
        val result = try { Math.addExact(current, delta) } catch (_: ArithmeticException) {
            throw StockException("The stock adjustment is too large.")
        }
        if (result < 0) throw StockException("This adjustment would make inventory negative.")
        return result
    }
}
