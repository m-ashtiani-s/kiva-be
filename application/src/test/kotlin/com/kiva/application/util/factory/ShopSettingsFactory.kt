package com.kiva.application.util.factory

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.DayOfWeek

/** The shop's settings as V12__shop_settings.sql writes them; each test changes only what it is about. */
object ShopSettingsFactory {
    fun generate(
        freeShippingThreshold: Long = 3_000_000,
        reservationEnabled: Boolean = false,
    ): ShopSettings = ShopSettings.constructFromPersisted(
        shippingMethods = listOf(
            ShippingMethod.constructFromPersisted(
                ShippingMethodCode.TIPAX, "تیپاکس", Money.constructFromPersisted(145_000), DeliveryDays.constructFromPersisted(1, 2),
                "truck", isActive = true, sortOrder = 1,
            ),
            ShippingMethod.constructFromPersisted(
                ShippingMethodCode.POST, "پست معمولی", Money.constructFromPersisted(75_000), DeliveryDays.constructFromPersisted(3, 5),
                "box", isActive = true, sortOrder = 2,
            ),
        ),
        defaultShippingMethod = ShippingMethodCode.POST,
        freeShippingThreshold = Money.constructFromPersisted(freeShippingThreshold),
        freeShippingMethods = setOf(ShippingMethodCode.POST),
        nonShippingWeekdays = setOf(DayOfWeek.FRIDAY),
        paymentPendingMinutes = 15,
        reservationEnabled = reservationEnabled,
        reservationHoldDays = 4,
        returnWindowDays = 7,
        paymentGateways = listOf(
            PaymentGatewayOption.constructFromPersisted(
                PaymentGatewayCode.ZARINPAL, "زرین‌پال", "#F2C230", "ز", null, isActive = true, isDefault = true, sortOrder = 1,
            ),
        ),
    )

    /** A repository that always answers [settings]. */
    fun repositoryOf(settings: ShopSettings = generate()): ShopSettingsRepository = object : ShopSettingsRepository {
        override fun get(): ShopSettings = settings
    }
}
