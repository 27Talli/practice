package com.offerpoint.pos.domain.usecase

import com.offerpoint.pos.domain.model.ValidationException
import java.util.Locale

/** A staff letter code, not encryption. The ten-letter key stays in the private shop database. */
object CostCode {
    fun validateAlphabet(value: String): String {
        val alphabet = value.trim().uppercase(Locale.ROOT)
        if (alphabet.length != 10 || alphabet.any { it !in 'A'..'Z' } || alphabet.toSet().size != 10) {
            throw ValidationException("costCode", "Choose exactly ten different letters, one for each digit from 0 to 9.")
        }
        return alphabet
    }

    /** Encode integer paise; Rs. 125.50 is the digit sequence 12550. */
    fun encode(paise: Long, alphabet: String): String {
        if (paise < 0) throw ValidationException("cost", "Purchase cost cannot be negative.")
        val key = validateAlphabet(alphabet)
        return paise.toString().map { key[it - '0'] }.joinToString("")
    }

    fun decode(code: String, alphabet: String): Long {
        val key = validateAlphabet(alphabet)
        if (code.isEmpty() || code.length > 19 || code.any { it !in key }) {
            throw ValidationException("costCode", "This is not a valid purchase-cost code for that key.")
        }
        return code.map { key.indexOf(it).digitToChar() }.joinToString("").toLongOrNull()
            ?: throw ValidationException("costCode", "This purchase-cost code is too large.")
    }
}
