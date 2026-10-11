package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SettingsCodesTest {

    @Test
    fun `the shipping methods and gateways are exactly the contract's`() {
        assertEquals(listOf("TIPAX", "POST", "COURIER"), ShippingMethodCode.entries.map { it.name })
        assertEquals(listOf("ZARINPAL", "SAMAN", "MELLAT"), PaymentGatewayCode.entries.map { it.name })
    }

    @Test
    fun `a contract value is read as it is`() {
        assertEquals(Result.success(ShippingMethodCode.POST), ShippingMethodCode.makeNew("POST"))
        assertEquals(Result.success(PaymentGatewayCode.ZARINPAL), PaymentGatewayCode.makeNew("ZARINPAL"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["BIKE", "post", " POST", ""])
    fun `anything else is SHIPPING_METHOD_INVALID, without a field`(raw: String) {
        assertEquals(Result.failure(ShippingMethodCode.Failure.Invalid), ShippingMethodCode.makeNew(raw))
        assertEquals("SHIPPING_METHOD_INVALID", ShippingMethodCode.Failure.Invalid.code)
        assertNull(ShippingMethodCode.Failure.Invalid.field)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PAYPAL", "zarinpal", ""])
    fun `anything else is PAYMENT_GATEWAY_INVALID, without a field`(raw: String) {
        assertEquals(Result.failure(PaymentGatewayCode.Failure.Invalid), PaymentGatewayCode.makeNew(raw))
        assertEquals("PAYMENT_GATEWAY_INVALID", PaymentGatewayCode.Failure.Invalid.code)
        assertNull(PaymentGatewayCode.Failure.Invalid.field)
    }
}
