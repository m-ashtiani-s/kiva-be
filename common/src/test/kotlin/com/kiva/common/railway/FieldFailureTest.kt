package com.kiva.common.railway

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.Money
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FieldFailureTest {

    @Test
    fun `names the field and keeps code and kind`() {
        val failure = Money.makeNew(toman = -1).forField("price").failureOrNull()!!

        assertEquals("price", failure.field)
        assertEquals("MONEY_NEGATIVE", failure.code)
        assertEquals(FailureKind.INVALID, failure.kind)
    }

    @Test
    fun `replaces a field the value object set itself`() {
        // IranianMobileNumber says "phone", but this form calls it "recipientPhone".
        val failure = IranianMobileNumber.makeNew("123").forField("recipientPhone").failureOrNull()!!
        assertEquals("recipientPhone", failure.field)
        assertEquals("PHONE_INVALID", failure.code)
    }

    @Test
    fun `keeps the inner causes`() {
        val inner = object : ResultFailure("INNER", FailureKind.INVALID) {}
        val outer = object : ResultFailure("OUTER", FailureKind.INVALID, causes = listOf(inner)) {}

        val failure = Result.failure(outer).forField("address").failureOrNull()!!
        assertEquals(listOf("INNER"), failure.causes.map { it.code })
    }

    @Test
    fun `keeps the meta`() {
        val withMeta = object : ResultFailure("OUT_OF_STOCK", FailureKind.BUSINESS_RULE, meta = mapOf("availableQuantity" to 0)) {}
        assertEquals(mapOf<String, Any>("availableQuantity" to 0), Result.failure(withMeta).forField("quantity").failureOrNull()?.meta)
    }

    @Test
    fun `leaves a success untouched`() {
        assertEquals(1000L, Money.makeNew(toman = 1000).forField("price").getOrNull()?.toman)
    }
}
