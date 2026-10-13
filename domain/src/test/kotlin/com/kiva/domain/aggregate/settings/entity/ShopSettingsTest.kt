package com.kiva.domain.aggregate.settings.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ShopSettingsTest {

    private fun method(code: ShippingMethodCode, name: String = "تیپاکس", isActive: Boolean = true) =
        ShippingMethod.constructFromPersisted(
            code, name, Money.constructFromPersisted(145_000), DeliveryDays.constructFromPersisted(1, 2), "truck", isActive, sortOrder = 1,
        )

    private fun gateway(code: PaymentGatewayCode, isActive: Boolean = true) =
        PaymentGatewayOption.constructFromPersisted(code, "زرین‌پال", "#F2C230", "ز", null, isActive, isDefault = false, sortOrder = 1)

    private fun settings(
        paymentPendingMinutes: Int = 15,
        reservationHoldDays: Int = 4,
        returnWindowDays: Int = 7,
        nonShippingWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY),
    ) = ShopSettings.constructFromPersisted(
        shippingMethods = listOf(method(ShippingMethodCode.TIPAX)),
        defaultShippingMethod = ShippingMethodCode.TIPAX,
        freeShippingThreshold = Money.constructFromPersisted(3_000_000),
        freeShippingMethods = setOf(ShippingMethodCode.POST),
        nonShippingWeekdays = nonShippingWeekdays,
        paymentPendingMinutes = paymentPendingMinutes,
        reservationEnabled = false,
        reservationHoldDays = reservationHoldDays,
        returnWindowDays = returnWindowDays,
        paymentGateways = listOf(gateway(PaymentGatewayCode.ZARINPAL)),
    )

    @Test
    fun `constructFromPersisted keeps every value`() {
        val settings = settings()

        assertEquals(listOf(ShippingMethodCode.TIPAX), settings.shippingMethods.map { it.code })
        assertEquals(ShippingMethodCode.TIPAX, settings.defaultShippingMethod)
        assertEquals(Money.constructFromPersisted(3_000_000), settings.freeShippingThreshold)
        assertEquals(setOf(ShippingMethodCode.POST), settings.freeShippingMethods)
        assertEquals(setOf(DayOfWeek.FRIDAY), settings.nonShippingWeekdays)
        assertEquals(15, settings.paymentPendingMinutes)
        assertEquals(false, settings.reservationEnabled)
        assertEquals(4, settings.reservationHoldDays)
        assertEquals(7, settings.returnWindowDays)
        assertEquals(listOf(PaymentGatewayCode.ZARINPAL), settings.paymentGateways.map { it.code })
    }

    @Test
    fun `numbers outside the database's ranges are a bug, so they throw`() {
        assertFailsWith<IllegalArgumentException> { settings(paymentPendingMinutes = 4) }
        assertFailsWith<IllegalArgumentException> { settings(paymentPendingMinutes = 61) }
        assertFailsWith<IllegalArgumentException> { settings(reservationHoldDays = 0) }
        assertFailsWith<IllegalArgumentException> { settings(returnWindowDays = 61) }
        assertFailsWith<IllegalArgumentException> { settings(nonShippingWeekdays = DayOfWeek.entries.toSet()) }
    }

    @Test
    fun `a shipping method is known by its code, and a gateway too, whatever else changed`() {
        assertEquals(method(ShippingMethodCode.POST, name = "پست"), method(ShippingMethodCode.POST, name = "پست معمولی", isActive = false))
        assertNotEquals(method(ShippingMethodCode.POST), method(ShippingMethodCode.TIPAX))
        assertEquals(gateway(PaymentGatewayCode.SAMAN), gateway(PaymentGatewayCode.SAMAN, isActive = false))
        assertNotEquals(gateway(PaymentGatewayCode.SAMAN), gateway(PaymentGatewayCode.MELLAT))
    }

    @Test
    fun `a method without a name or icon, or a gateway without a name or initial, is a bug`() {
        assertFailsWith<IllegalArgumentException> { method(ShippingMethodCode.POST, name = " ") }
        assertFailsWith<IllegalArgumentException> {
            PaymentGatewayOption.constructFromPersisted(PaymentGatewayCode.SAMAN, "بانک سامان", "#1D5FA8", "", null, false, false, 2)
        }
    }
}
