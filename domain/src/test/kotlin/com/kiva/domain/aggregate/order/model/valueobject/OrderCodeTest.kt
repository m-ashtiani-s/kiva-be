package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrderCodeTest {

    @ParameterizedTest
    @ValueSource(strings = ["KV-218340", " kv-218340 ", "KV-۲۱۸۳۴۰", "Kv-٢١٨٣٤٠"])
    fun `a code typed in any case, with spaces or Persian digits, becomes KV-218340`(raw: String) {
        assertEquals("KV-218340", OrderCode.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["KV-1234", "KV-12345678"])
    fun `4 to 8 digits, as in the contract`(raw: String) {
        assertEquals(raw, OrderCode.makeNew(raw).getOrNull()?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "218340", "KV218340", "KV-123", "KV-123456789", "KV-1001-LILAC", "AB-218340"])
    fun `anything else is ORDER_CODE_INVALID on the orderCode field`(raw: String) {
        assertEquals(Result.failure(OrderCode.Failure.Invalid), OrderCode.makeNew(raw))
        assertEquals("orderCode", OrderCode.Failure.Invalid.field)
    }

    @Test
    fun `a broken stored code is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { OrderCode.constructFromPersisted("kv-218340") }
    }
}
