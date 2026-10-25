package com.kiva.common.railway

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LimitsTest {

    @Test
    fun `a number in its range is itself, the edges included`() {
        assertEquals(1, 1.requiredIn(1..14).getOrNull())
        assertEquals(14, 14.requiredIn(1..14).getOrNull())
        assertEquals(0L, 0L.requiredIn(0L..100L).getOrNull())
    }

    @Test
    fun `a number outside its range is NUMBER_OUT_OF_RANGE with the range in meta`() {
        val failure = 15.requiredIn(1..14).failureOrNull()

        assertEquals(NumberOutOfRange(1, 14), failure)
        assertEquals("NUMBER_OUT_OF_RANGE", failure?.code)
        assertEquals(mapOf<String, Any>("min" to 1L, "max" to 14L), failure?.meta)
        assertEquals(NumberOutOfRange(0, 100), (-1L).requiredIn(0L..100L).failureOrNull())
    }

    @Test
    fun `a number that didn't come is FIELD_REQUIRED`() {
        assertEquals(FieldRequired, (null as Int?).requiredIn(1..14).failureOrNull())
        assertEquals(FieldRequired, (null as Long?).requiredIn(0L..1L).failureOrNull())
    }

    @Test
    fun `the length and count limits carry their maximum`() {
        assertEquals(mapOf<String, Any>("maxLength" to 50), TextTooLong(50).meta)
        assertEquals(mapOf<String, Any>("maxItems" to 7), TooManyItems(7).meta)
        assertEquals("LIST_ITEM_DUPLICATE", ListItemDuplicate.code)
    }
}
