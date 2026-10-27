package com.offerpoint.pos

import com.offerpoint.pos.domain.model.ValidationException
import com.offerpoint.pos.domain.usecase.CostCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class CostCodeTest {
    private val key = "BLACKHORSE"

    @Test fun purchasePaiseEncodeAsLettersWithoutExposingTheNumber() {
        val code = CostCode.encode(12550, key)
        assertEquals("LAHHB", code)
        assertEquals(12550L, CostCode.decode(code, key))
        assertFalse(code.any(Char::isDigit))
        assertFalse(code.contains("125.50"))
    }

    @Test fun zeroAndEveryDigitRoundTrip() {
        assertEquals("B", CostCode.encode(0, key))
        assertEquals(0L, CostCode.decode("B", key))
        assertEquals(1023456789L, CostCode.decode(CostCode.encode(1023456789, key), key))
    }

    @Test fun customKeyNormalizesOnlyItsCaseAndOuterWhitespace() {
        assertEquals(key, CostCode.validateAlphabet(" blackhorse "))
        assertEquals(CostCode.encode(25000, key), CostCode.encode(25000, "blackhorse"))
    }

    @Test fun keysMustHaveExactlyTenDistinctLetters() {
        listOf("", "AAAAAAAAAA", "ABCDEFGHI", "ABCDEFGHIJK", "ABCDEFGHI0", "ABCD EFGHI").forEach {
            assertThrows(ValidationException::class.java) { CostCode.validateAlphabet(it) }
        }
    }

    @Test fun negativeCostsUnknownLettersAndOverflowAreRejected() {
        assertThrows(ValidationException::class.java) { CostCode.encode(-1, key) }
        assertThrows(ValidationException::class.java) { CostCode.decode("", key) }
        assertThrows(ValidationException::class.java) { CostCode.decode("XYZ", key) }
        assertThrows(ValidationException::class.java) { CostCode.decode("9", key) }
        val overflow = "9223372036854775808".map { key[it - '0'] }.joinToString("")
        assertThrows(ValidationException::class.java) { CostCode.decode(overflow, key) }
    }

    @Test fun maximumPaiseAndOldKeySnapshotsRemainExact() {
        assertEquals(Long.MAX_VALUE, CostCode.decode(CostCode.encode(Long.MAX_VALUE, key), key))
        val oldCode = CostCode.encode(12550, key)
        val replacement = "ABCDEFGHIJ"
        assertEquals(12550L, CostCode.decode(oldCode, key))
        assertEquals(12550L, CostCode.decode(CostCode.encode(12550, replacement), replacement))
    }
}
