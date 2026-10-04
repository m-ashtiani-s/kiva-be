package com.kiva.common.railway

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.Money
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FieldFailureTest {

    @Test
    fun `names the field and keeps code and kind`() {
        val failure = Money.ofRials(-1).forField("price").failureOrNull()!!

        assertEquals("price", failure.field)
        assertEquals("MONEY_NEGATIVE", failure.code)
        assertEquals(FailureKind.INVALID, failure.kind)
    }

    @Test
    fun `replaces a field the value object set itself`() {
        // IranianMobileNumber says "mobile", but this form calls it "receiverMobile".
        val failure = IranianMobileNumber.of("123").forField("receiverMobile").failureOrNull()!!
        assertEquals("receiverMobile", failure.field)
        assertEquals("MOBILE_INVALID", failure.code)
    }

    @Test
    fun `keeps the inner causes`() {
        val inner = object : ResultFailure("INNER", FailureKind.INVALID) {}
        val outer = object : ResultFailure("OUTER", FailureKind.INVALID, causes = listOf(inner)) {}

        val failure = Result.failure(outer).forField("address").failureOrNull()!!
        assertEquals(listOf("INNER"), failure.causes.map { it.code })
    }

    @Test
    fun `leaves a success untouched`() {
        assertEquals(1000L, Money.ofRials(1000).forField("price").getOrNull()?.rials)
    }
}
