package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The contract's enums of a product, read from the raw text a request carries. */
class ProductEnumsTest {

    @Test
    fun `every name of the contract is read as it is`() {
        BagType.entries.forEach { assertEquals(Result.success(it), BagType.makeNew(it.name)) }
        ClosureType.entries.forEach { assertEquals(Result.success(it), ClosureType.makeNew(it.name)) }
        Occasion.entries.forEach { assertEquals(Result.success(it), Occasion.makeNew(it.name)) }
        ProductStatus.entries.forEach { assertEquals(Result.success(it), ProductStatus.makeNew(it.name)) }
    }

    // Exactly as sent: a lowercase or padded name is a client bug worth seeing, not something to guess.
    @ParameterizedTest
    @ValueSource(strings = ["", "hobo", " HOBO", "SHOULDER"])
    fun `anything else is refused on the field it came in`(raw: String) {
        assertEquals(Result.failure(BagType.Failure.Invalid), BagType.makeNew(raw))
        assertEquals(Result.failure(ClosureType.Failure.Invalid), ClosureType.makeNew(raw))
        assertEquals(Result.failure(Occasion.Failure.Invalid), Occasion.makeNew(raw))
        assertEquals(Result.failure(ProductStatus.Failure.Invalid), ProductStatus.makeNew(raw))
    }

    @Test
    fun `each failure has its own code, and names its field unless it is one item of a list`() {
        assertEquals(
            listOf("BAG_TYPE_INVALID" to "bagType", "CLOSURE_TYPE_INVALID" to "closure", "PRODUCT_STATUS_INVALID" to "status"),
            listOf(BagType.Failure.Invalid, ClosureType.Failure.Invalid, ProductStatus.Failure.Invalid).map { it.code to it.field },
        )
        assertEquals("OCCASION_INVALID", Occasion.Failure.Invalid.code)
        assertNull(Occasion.Failure.Invalid.field)
    }
}
