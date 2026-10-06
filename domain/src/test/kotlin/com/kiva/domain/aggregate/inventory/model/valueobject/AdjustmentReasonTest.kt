package com.kiva.domain.aggregate.inventory.model.valueobject

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class AdjustmentReasonTest {

    /** The contract's five reasons and the direction each one makes sense in. */
    @ParameterizedTest
    @CsvSource(
        "RESTOCK,           true,  false",
        "STOCKTAKE,         true,  true",
        "DAMAGED,           false, true",
        "RETURNED,          true,  false",
        "MANUAL_CORRECTION, true,  true",
    )
    fun `each reason allows only the directions it makes sense in`(reason: AdjustmentReason, adds: Boolean, takesAway: Boolean) {
        assertEquals(adds, reason.allows(StockDelta.constructFromPersisted(1)))
        assertEquals(takesAway, reason.allows(StockDelta.constructFromPersisted(-1)))
    }
}
