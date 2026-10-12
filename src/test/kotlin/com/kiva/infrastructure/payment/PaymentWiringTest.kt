package com.kiva.infrastructure.payment

import com.kiva.application.port.PaymentGateways
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode.MELLAT
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode.SAMAN
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode.ZARINPAL
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.common.valueobject.Money
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.AuthConfig
import com.kiva.infrastructure.config.DatasourceConfig
import com.kiva.infrastructure.di.infrastructureModule
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which gateways can take a payment, and what the app is given to take them with. */
class PaymentWiringTest {

    /** Zarinpal, Saman and Mellat in that order; only [active] switched on, and [default] the shop's default. */
    private fun settings(vararg active: PaymentGatewayCode, default: PaymentGatewayCode = ZARINPAL): ShopSettings = ShopSettings.constructFromPersisted(
        shippingMethods = listOf(ShippingMethod.constructFromPersisted(ShippingMethodCode.POST, "پست معمولی", Money.constructFromPersisted(75_000), DeliveryDays.constructFromPersisted(3, 5), "box", true, 1)),
        defaultShippingMethod = ShippingMethodCode.POST,
        freeShippingThreshold = Money.constructFromPersisted(3_000_000),
        freeShippingMethods = setOf(ShippingMethodCode.POST),
        nonShippingWeekdays = setOf(DayOfWeek.FRIDAY),
        paymentPendingMinutes = 15,
        reservationEnabled = false,
        reservationHoldDays = 4,
        returnWindowDays = 7,
        paymentGateways = listOf(ZARINPAL, SAMAN, MELLAT).mapIndexed { index, code ->
            PaymentGatewayOption.constructFromPersisted(code, code.name, "#F2C230", "ز", null, isActive = code in active, isDefault = code == default, sortOrder = index)
        },
    )

    private val gateways = PaymentGateways(listOf(FakePaymentGateway(PaymentConfig.DEFAULT)))

    @Test
    fun `a gateway is available when the settings switch it on and the app has an adapter for it`() {
        assertTrue(gateways.isAvailable(ZARINPAL, settings(ZARINPAL)))
        assertFalse(gateways.isAvailable(ZARINPAL, settings()))
        // Switched on in the panel, but no Saman adapter exists.
        assertFalse(gateways.isAvailable(SAMAN, settings(ZARINPAL, SAMAN)))
        assertEquals(null, gateways.of(MELLAT))
    }

    @Test
    fun `checkout lists every gateway in order, and starts on the shop's default while it is available`() {
        val options = gateways.checkoutOptions(settings(ZARINPAL))

        assertEquals(listOf(ZARINPAL, SAMAN, MELLAT), options.map { it.gateway.code })
        assertEquals(listOf(true, false, false), options.map { it.available })
        assertEquals(listOf(true, false, false), options.map { it.isDefault })
    }

    @Test
    fun `a default that can't take the payment gives way to the first available gateway, and with none available there is no default`() {
        // Saman is the shop's default and switched on, but has no adapter.
        val samanDefault = gateways.checkoutOptions(settings(ZARINPAL, SAMAN, default = SAMAN))
        assertEquals(listOf(true, false, false), samanDefault.map { it.isDefault })
        assertEquals(listOf(true, false, false), samanDefault.map { it.available })

        val noneOn = gateways.checkoutOptions(settings())
        assertEquals(listOf(false, false, false), noneOn.map { it.available })
        assertEquals(listOf(false, false, false), noneOn.map { it.isDefault })
    }

    @Test
    fun `two adapters for one gateway are a wiring bug`() {
        assertFailsWith<IllegalArgumentException> { PaymentGateways(listOf(FakePaymentGateway(PaymentConfig.DEFAULT), FakePaymentGateway(PaymentConfig.DEFAULT))) }
    }

    @Test
    fun `asking for Zarinpal itself before its adapter exists stops the boot with a clear message`() {
        val config = AppConfig(
            DatasourceConfig("jdbc:postgresql://localhost:5432/kiva_test", "unused", "unused", 1),
            auth = AuthConfig(otpPepper = ByteArray(32) { 7 }, jwtSecret = ByteArray(32) { 8 }, maxSmsPerHour = 300),
            payment = PaymentConfig(
                PaymentProvider.ZARINPAL, "https://api.kiva.ir", "https://kiva.ir/checkout/result",
                ZarinpalConfig("1344b5d4-0048-11e8-94db-005056a205be", sandbox = false),
            ),
        )

        val error = assertFailsWith<IllegalStateException> { infrastructureModule(config, TestDatabase.database) }
        assertTrue("step 18" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `payment ids are pay_ and 12 random capital letters and digits, each its own`() {
        val ids = RandomPaymentIds()

        val made = List(1_000) { ids.nextPaymentPublicId().value }

        assertTrue(made.all { Regex("pay_[0-9A-Z]{12}").matches(it) })
        assertEquals(1_000, made.toSet().size)
    }
}
