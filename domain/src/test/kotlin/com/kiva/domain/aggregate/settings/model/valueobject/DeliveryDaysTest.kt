package com.kiva.domain.aggregate.settings.model.valueobject

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeliveryDaysTest {

    @Test
    fun `a range from 0 up to 30 days is kept as it is, one day included`() {
        assertEquals(3 to 5, DeliveryDays.constructFromPersisted(3, 5).let { it.min to it.max })
        assertEquals(0 to 0, DeliveryDays.constructFromPersisted(0, 0).let { it.min to it.max })
        assertEquals(2 to 2, DeliveryDays.constructFromPersisted(2, 2).let { it.min to it.max })
        assertEquals(DeliveryDays.constructFromPersisted(1, 2), DeliveryDays.constructFromPersisted(1, 2))
    }

    @Test
    fun `a broken stored range is a bug, so it throws`() {
        assertFailsWith<IllegalArgumentException> { DeliveryDays.constructFromPersisted(5, 3) }
        assertFailsWith<IllegalArgumentException> { DeliveryDays.constructFromPersisted(-1, 2) }
        assertFailsWith<IllegalArgumentException> { DeliveryDays.constructFromPersisted(1, 31) }
    }
}
