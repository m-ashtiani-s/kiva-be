package com.kiva.domain.aggregate.inventory.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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

    @Test
    fun `the five names of the contract are read as they are`() {
        AdjustmentReason.entries.forEach { assertEquals(Result.success(it), AdjustmentReason.makeNew(it.name)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "restock", "STOLEN", " RESTOCK"])
    fun `anything else is ADJUSTMENT_REASON_INVALID on the reason field`(raw: String) {
        assertEquals(Result.failure(AdjustmentReason.Failure.Invalid), AdjustmentReason.makeNew(raw))
        assertEquals("reason", AdjustmentReason.Failure.Invalid.field)
    }
}
