package com.kiva.domain.aggregate.settings.entity

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.SettingsFixtures
import com.kiva.domain.aggregate.settings.SettingsFixtures.pathsOf
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopSettingsChangeTest {

    private val stored = SettingsFixtures.stored()
    private val same = SettingsFixtures.sameAs(stored)

    private fun ShopSettings.Change.method(code: ShippingMethodCode, change: (ShopSettings.ShippingMethodDraft) -> ShopSettings.ShippingMethodDraft) =
        copy(shippingMethods = shippingMethods.map { if (it.code == code) change(it) else it })

    private fun ShopSettings.Change.gateway(code: PaymentGatewayCode, isActive: Boolean? = null, isDefault: Boolean? = null) =
        copy(paymentGateways = paymentGateways.map { if (it.code == code) it.copy(isActive = isActive ?: it.isActive, isDefault = isDefault ?: it.isDefault) else it })

    @Test
    fun `the same values again change nothing, the version included`() {
        val after = stored.change(same).getOrNull()!!

        assertTrue(after.sameValuesAs(stored))
        assertEquals(stored.version, after.version)
    }

    @Test
    fun `a change is kept, the lists in their new order, the gateways keeping their names`() {
        val change = same.copy(
            shippingMethods = same.shippingMethods.reversed().map { if (it.code == ShippingMethodCode.TIPAX) it.copy(baseCost = Money.constructFromPersisted(160_000)) else it },
            paymentGateways = same.paymentGateways.reversed(),
            lowStockThreshold = 3,
            preShipmentFeedbackHours = 48,
        )

        val after = stored.change(change).getOrNull()!!

        assertFalse(after.sameValuesAs(stored))
        assertEquals(listOf(ShippingMethodCode.POST to 1, ShippingMethodCode.TIPAX to 2), after.shippingMethods.map { it.code to it.sortOrder })
        assertEquals(Money.constructFromPersisted(160_000), after.shippingMethods.single { it.code == ShippingMethodCode.TIPAX }.baseCost)
        assertEquals(listOf(PaymentGatewayCode.MELLAT, PaymentGatewayCode.SAMAN, PaymentGatewayCode.ZARINPAL), after.paymentGateways.map { it.code })
        assertEquals(listOf(1, 2, 3), after.paymentGateways.map { it.sortOrder })
        assertEquals("بانک ملت", after.paymentGateways.first().name)
        assertEquals(3, after.lowStockThreshold)
        assertEquals(48, after.preShipmentFeedbackHours)
    }

    @Test
    fun `a method the shop has no row for is SHIPPING_METHOD_NOT_SUPPORTED on its code`() {
        val courier = same.shippingMethods.first().copy(code = ShippingMethodCode.COURIER)

        val failure = stored.change(same.copy(shippingMethods = same.shippingMethods + courier)).failureOrNull()

        assertEquals(listOf("shippingMethods.2.code SHIPPING_METHOD_NOT_SUPPORTED"), pathsOf(failure))
    }

    @Test
    fun `a method left out or sent twice is SHIPPING_METHOD_LIST_INVALID`() {
        assertEquals(
            listOf("shippingMethods SHIPPING_METHOD_LIST_INVALID", "defaultShippingMethod DEFAULT_SHIPPING_METHOD_INVALID"),
            pathsOf(stored.change(same.copy(shippingMethods = same.shippingMethods.take(1))).failureOrNull()),
        )
        assertEquals(
            listOf("shippingMethods SHIPPING_METHOD_LIST_INVALID"),
            pathsOf(stored.change(same.copy(shippingMethods = same.shippingMethods + same.shippingMethods.last())).failureOrNull()),
        )
    }

    @Test
    fun `no active method is NO_ACTIVE_SHIPPING_METHOD, and the default then has none to be`() {
        val change = same.method(ShippingMethodCode.TIPAX) { it.copy(isActive = false) }.method(ShippingMethodCode.POST) { it.copy(isActive = false) }

        assertEquals(
            listOf("shippingMethods NO_ACTIVE_SHIPPING_METHOD", "defaultShippingMethod DEFAULT_SHIPPING_METHOD_INVALID"),
            pathsOf(stored.change(change).failureOrNull()),
        )
    }

    @Test
    fun `switching the default method off is DEFAULT_SHIPPING_METHOD_INVALID, another one may be default`() {
        val off = same.method(ShippingMethodCode.POST) { it.copy(isActive = false) }

        assertEquals(listOf("defaultShippingMethod DEFAULT_SHIPPING_METHOD_INVALID"), pathsOf(stored.change(off).failureOrNull()))
        assertEquals(ShippingMethodCode.TIPAX, stored.change(off.copy(defaultShippingMethod = ShippingMethodCode.TIPAX)).getOrNull()?.defaultShippingMethod)
    }

    @Test
    fun `free shipping only for methods the shop has`() {
        val failure = stored.change(same.copy(freeShippingMethods = listOf(ShippingMethodCode.POST, ShippingMethodCode.COURIER))).failureOrNull()

        assertEquals(listOf("freeShippingMethods.1 SHIPPING_METHOD_NOT_SUPPORTED"), pathsOf(failure))
        assertEquals(emptySet(), stored.change(same.copy(freeShippingMethods = emptyList())).getOrNull()?.freeShippingMethods)
    }

    @Test
    fun `every gateway once, or PAYMENT_GATEWAY_LIST_INVALID`() {
        assertEquals(
            listOf("paymentGateways PAYMENT_GATEWAY_LIST_INVALID"),
            pathsOf(stored.change(same.copy(paymentGateways = same.paymentGateways.take(2))).failureOrNull()),
        )
        assertEquals(
            listOf("paymentGateways PAYMENT_GATEWAY_LIST_INVALID"),
            pathsOf(stored.change(same.copy(paymentGateways = same.paymentGateways + same.paymentGateways.first())).failureOrNull()),
        )
    }

    @Test
    fun `no active gateway is NO_ACTIVE_GATEWAY, and its default can't be active either`() {
        val failure = stored.change(same.gateway(PaymentGatewayCode.ZARINPAL, isActive = false)).failureOrNull()

        assertEquals(listOf("paymentGateways NO_ACTIVE_GATEWAY", "paymentGateways DEFAULT_GATEWAY_INVALID"), pathsOf(failure))
    }

    @Test
    fun `exactly one default gateway, and an active one`() {
        val none = same.gateway(PaymentGatewayCode.ZARINPAL, isDefault = false)
        val two = same.gateway(PaymentGatewayCode.SAMAN, isActive = true, isDefault = true)
        val inactive = same.gateway(PaymentGatewayCode.ZARINPAL, isDefault = false).gateway(PaymentGatewayCode.MELLAT, isDefault = true)
        val moved = same.gateway(PaymentGatewayCode.ZARINPAL, isDefault = false).gateway(PaymentGatewayCode.MELLAT, isActive = true, isDefault = true)

        listOf(none, two, inactive).forEach { assertEquals(listOf("paymentGateways DEFAULT_GATEWAY_INVALID"), pathsOf(stored.change(it).failureOrNull())) }
        assertEquals(PaymentGatewayCode.MELLAT, stored.change(moved).getOrNull()?.paymentGateways?.single { it.isDefault }?.code)
    }

    @Test
    fun `every rule broken at once is said at once`() {
        val change = same.copy(
            shippingMethods = same.shippingMethods.take(1).map { it.copy(isActive = false) },
            freeShippingMethods = listOf(ShippingMethodCode.COURIER),
            paymentGateways = same.paymentGateways.map { it.copy(isActive = false) },
        )

        assertEquals(
            listOf(
                "shippingMethods SHIPPING_METHOD_LIST_INVALID", "shippingMethods NO_ACTIVE_SHIPPING_METHOD", "defaultShippingMethod DEFAULT_SHIPPING_METHOD_INVALID",
                "freeShippingMethods.0 SHIPPING_METHOD_NOT_SUPPORTED", "paymentGateways NO_ACTIVE_GATEWAY", "paymentGateways DEFAULT_GATEWAY_INVALID",
            ),
            pathsOf(stored.change(change).failureOrNull()),
        )
    }

    @Test
    fun `a method's own values count as a change, its name too`() {
        val renamed = same.method(ShippingMethodCode.POST) { it.copy(name = ShippingMethodName.constructFromPersisted("پست پیشتاز")) }

        assertFalse(stored.change(renamed).getOrNull()!!.sameValuesAs(stored))
        assertEquals("پست پیشتاز", stored.change(renamed).getOrNull()!!.shippingMethods.single { it.code == ShippingMethodCode.POST }.name)
    }
}
