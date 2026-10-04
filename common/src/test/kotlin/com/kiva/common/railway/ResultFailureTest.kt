package com.kiva.common.railway

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ResultFailureTest {

    // A value object failure (inner) and a use case failure that wraps it (outer).
    private sealed class QuantityFailure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "quantity") {
        data object NotPositive : QuantityFailure("QUANTITY_NOT_POSITIVE")
    }

    private sealed class AddToCartFailure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, causes = causes) {
        data class InvalidQuantity(val cause: QuantityFailure) :
            AddToCartFailure("INVALID_QUANTITY", FailureKind.INVALID, listOf(cause))

        data object ProductNotFound : AddToCartFailure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }

    private class AnyFailure(code: String) : ResultFailure(code, FailureKind.INVALID)

    @Nested
    inner class Code {
        @ParameterizedTest
        @ValueSource(strings = ["SKU_BLANK", "NOT_FOUND", "A", "ORDER_2_PAID"])
        fun `accepts UPPER_SNAKE_CASE`(code: String) {
            assertEquals(code, AnyFailure(code).code)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "skuBlank", "SkuBlank", "sku_blank", "SKU BLANK", "SKU__BLANK", "_SKU", "SKU_", "2_SKU"])
        fun `rejects anything else`(code: String) {
            assertFailsWith<IllegalArgumentException> { AnyFailure(code) }
        }
    }

    @Test
    fun `carries kind and field`() {
        val failure = QuantityFailure.NotPositive
        assertEquals(FailureKind.INVALID, failure.kind)
        assertEquals("quantity", failure.field)
    }

    @Test
    fun `defaults to no field and no causes`() {
        val failure = AddToCartFailure.ProductNotFound
        assertNull(failure.field)
        assertEquals(emptyList(), failure.causes)
        assertEquals(FailureKind.NOT_FOUND, failure.kind)
    }

    @Test
    fun `a wrapping failure exposes the inner failure through causes`() {
        val outer = AddToCartFailure.InvalidQuantity(QuantityFailure.NotPositive)

        assertEquals("INVALID_QUANTITY", outer.code)
        assertEquals(listOf("QUANTITY_NOT_POSITIVE"), outer.causes.map { it.code })
        assertEquals("quantity", outer.causes.single().field)
    }

    @Test
    fun `wrapping with mapOnFailure keeps the inner code reachable`() {
        val quantity: Result<Int, QuantityFailure> = Result.failure(QuantityFailure.NotPositive)

        val result = quantity.mapOnFailure { AddToCartFailure.InvalidQuantity(it) }

        assertEquals(listOf("QUANTITY_NOT_POSITIVE"), result.failureOrNull()?.causes?.map { it.code })
    }

    @Test
    fun `a Result that can never fail uses Nothing as its failure type`() {
        // No NoFailure object needed: Nothing already means "this side never happens".
        val neverFails: Result<Int, Nothing> = Result.success(3)

        // ...and it still fits where a failing Result is expected, thanks to `out F`.
        val chained: Result<Int, QuantityFailure> = neverFails.bind {
            if (it > 0) Result.success(it) else Result.failure(QuantityFailure.NotPositive)
        }

        assertEquals(Result.success(3), chained)
    }
}
