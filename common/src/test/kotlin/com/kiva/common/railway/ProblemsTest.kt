package com.kiva.common.railway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemsTest {

    private data object NameTooLong : ResultFailure("TEXT_TOO_LONG", FailureKind.INVALID, field = "name")
    private data object DaysOutOfRange : ResultFailure("NUMBER_OUT_OF_RANGE", FailureKind.INVALID, field = "maxDays")
    private data object Shared : ResultFailure("FIELD_REQUIRED", FailureKind.INVALID)

    /** Every leaf as `path code`, the way the HTTP layer builds `errors[].field`. */
    private fun paths(failure: ResultFailure, parent: String? = null): List<String> {
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { paths(it, path) }
        return listOf("$path ${failure.code}")
    }

    @Test
    fun `parts that all succeed give their values and no problem`() {
        val problems = Problems()
        val a = problems.take(Result.success(1))
        val b = problems.take(Result.success("x"))

        assertFalse(problems.any())
        assertEquals(1, a.value)
        assertEquals("x", b.value)
    }

    @Test
    fun `every failed part is kept, in order`() {
        val problems = Problems()
        problems.take(Result.failure(NameTooLong))
        problems.take(Result.success(1))
        problems.add(DaysOutOfRange)

        assertTrue(problems.any())
        assertEquals(listOf<ResultFailure>(NameTooLong, DaysOutOfRange), problems.all().causes)
    }

    @Test
    fun `under keeps the failure's own field as the next step of the path`() {
        val nested = listOf(NameTooLong.under("1"), DaysOutOfRange.under("0")).under("shippingMethods")

        assertEquals(listOf("shippingMethods.1.name TEXT_TOO_LONG", "shippingMethods.0.maxDays NUMBER_OUT_OF_RANGE"), paths(nested))
    }

    @Test
    fun `a failure without a field of its own ends at the level it was put under`() {
        assertEquals(listOf("social.2 FIELD_REQUIRED"), paths(Shared.under("2").under("social")))
    }
}
