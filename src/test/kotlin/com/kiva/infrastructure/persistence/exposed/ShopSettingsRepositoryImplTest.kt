package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reads the settings V12__shop_settings.sql writes into the freshly migrated kiva_test: the design's and contract's numbers. */
class ShopSettingsRepositoryImplTest {

    private val db = TestDatabase.database
    private val settings = transaction(db) { ShopSettingsRepositoryImpl().get() }

    @Test
    fun `TIPAX 145,000 in 1 to 2 days and POST 75,000 in 3 to 5, both active, with their icons, and no COURIER`() {
        val methods = settings.shippingMethods.map { listOf(it.code, it.name, it.baseCost, it.deliveryDays.min, it.deliveryDays.max, it.icon, it.isActive) }

        assertEquals(
            listOf(
                listOf(ShippingMethodCode.TIPAX, "تیپاکس", Money.constructFromPersisted(145_000), 1, 2, "truck", true),
                listOf(ShippingMethodCode.POST, "پست معمولی", Money.constructFromPersisted(75_000), 3, 5, "box", true),
            ),
            methods,
        )
    }

    @Test
    fun `free shipping from 3,000,000 and only for POST, which is also a new cart's method`() {
        assertEquals(Money.constructFromPersisted(3_000_000), settings.freeShippingThreshold)
        assertEquals(setOf(ShippingMethodCode.POST), settings.freeShippingMethods)
        assertEquals(ShippingMethodCode.POST, settings.defaultShippingMethod)
    }

    @Test
    fun `no shipping on Friday, 15 minutes to pay, reservation on (since V19) with 4 days, 7 days to return`() {
        assertEquals(setOf(DayOfWeek.FRIDAY), settings.nonShippingWeekdays)
        assertEquals(15, settings.paymentPendingMinutes)
        assertEquals(true, settings.reservationEnabled)
        assertEquals(4, settings.reservationHoldDays)
        assertEquals(7, settings.returnWindowDays)
    }

    @Test
    fun `Zarinpal is the only active gateway and the default, Saman and Mellat are there switched off`() {
        val gateways = settings.paymentGateways

        assertEquals(listOf(PaymentGatewayCode.ZARINPAL, PaymentGatewayCode.SAMAN, PaymentGatewayCode.MELLAT), gateways.map { it.code })
        assertEquals(listOf(PaymentGatewayCode.ZARINPAL), gateways.filter { it.isActive }.map { it.code })
        assertEquals(listOf(PaymentGatewayCode.ZARINPAL), gateways.filter { it.isDefault }.map { it.code })
        val zarinpal = gateways.first()
        assertEquals(listOf("زرین‌پال", "#F2C230", "ز"), listOf(zarinpal.name, zarinpal.brandColor, zarinpal.initial))
        assertNull(zarinpal.logoUrl)
        assertEquals(listOf("بانک سامان", "بانک ملت"), gateways.drop(1).map { it.name })
    }
}
