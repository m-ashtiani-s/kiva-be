package com.kiva.common.event.valueobject

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/** The small value objects every event carries. None of them is user input, so a bad value always throws. */
class EventValueObjectsTest {

    @Nested
    inner class EventIdTests {
        @Test
        fun `generate gives a new lowercase UUID every time`() {
            val a = EventId.generate()
            val b = EventId.generate()
            assertNotEquals(a, b)
            assertEquals(a.value.lowercase(), a.value)
        }

        @Test
        fun `a stored UUID is read back as it is`() {
            assertEquals("3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90", EventId.constructFromPersisted("3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90").value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["", "not-a-uuid", "1-2-3-4-5", "3F2A9C1E-5B7D-4E8A-9C21-7D4E5F6A8B90"])
        fun `anything but a UUID in its usual form is refused`(stored: String) {
            assertFailsWith<IllegalArgumentException> { EventId.constructFromPersisted(stored) }
        }
    }

    @Nested
    inner class EventSequenceTests {
        @Test
        fun `the first event is 1 and each next one is one more`() {
            assertEquals(1L, EventSequence.FIRST.value)
            assertEquals(2L, EventSequence.FIRST.next().value)
            assertEquals(EventSequence.constructFromPersisted(8), EventSequence.constructFromPersisted(7).next())
        }

        @ParameterizedTest
        @ValueSource(longs = [0, -1])
        fun `there is no event 0 or below`(stored: Long) {
            assertFailsWith<IllegalArgumentException> { EventSequence.constructFromPersisted(stored) }
        }
    }

    @Nested
    inner class NameTests {
        @Test
        fun `event types are PascalCase`() {
            assertEquals("ProductCreated", EventType("ProductCreated").value)
            listOf("", "productCreated", "Product Created", "Product_Created").forEach {
                assertFailsWith<IllegalArgumentException>(it) { EventType(it) }
            }
        }

        @Test
        fun `aggregate names are UPPER_SNAKE_CASE`() {
            assertEquals("STOCK_ALERT", EventAggregateName("STOCK_ALERT").value)
            listOf("", "product", "Product", "STOCK__ALERT").forEach {
                assertFailsWith<IllegalArgumentException>(it) { EventAggregateName(it) }
            }
        }

        @Test
        fun `aggregate ids are text without spaces around, at most 255 characters`() {
            assertEquals("12", EventAggregateId.of(12).value)
            assertEquals(255, EventAggregateId("a".repeat(255)).value.length)
            listOf("", "   ", " 12", "a".repeat(256)).forEach {
                assertFailsWith<IllegalArgumentException>(it) { EventAggregateId(it) }
            }
        }

        @Test
        fun `versions start at 1`() {
            assertEquals(1, EventVersion.FIRST.value)
            assertFailsWith<IllegalArgumentException> { EventVersion(0) }
        }
    }
}
