package com.kiva.application.util.factory

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShopFeatures
import com.kiva.domain.aggregate.settings.model.valueobject.SiteStats
import com.kiva.domain.aggregate.settings.model.valueobject.TrackingUrlTemplate
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
        lowStockThreshold = 5,
        preShipmentFeedbackHours = 24,
        supportPhone = null,
        supportEmail = null,
        supportHours = emptyList(),
        social = emptyList(),
        siteStats = SiteStats.NONE,
        features = ShopFeatures.NONE,
        version = 0,
        paymentGateways = listOf(
            PaymentGatewayOption.constructFromPersisted(
                PaymentGatewayCode.ZARINPAL, "زرین‌پال", "#F2C230", "ز", null, isActive = true, isDefault = true, sortOrder = 1,
            ),
        ),
    )

    /** The whole set as the database holds it after V30: both methods with their tracking pages, all three gateways. */
    fun stored(version: Long = 0): ShopSettings = generate(reservationEnabled = true).let { base ->
        ShopSettings.constructFromPersisted(
            shippingMethods = base.shippingMethods.map {
                ShippingMethod.constructFromPersisted(
                    it.code, it.name, it.baseCost, it.deliveryDays, it.icon, it.isActive, it.sortOrder,
                    TrackingUrlTemplate.constructFromPersisted(if (it.code == ShippingMethodCode.TIPAX) "https://tipaxco.com/tracking?code={code}" else "https://tracking.post.ir/?id={code}"),
                )
            },
            defaultShippingMethod = base.defaultShippingMethod,
            freeShippingThreshold = base.freeShippingThreshold,
            freeShippingMethods = base.freeShippingMethods,
            nonShippingWeekdays = base.nonShippingWeekdays,
            paymentPendingMinutes = base.paymentPendingMinutes,
            reservationEnabled = base.reservationEnabled,
            reservationHoldDays = base.reservationHoldDays,
            returnWindowDays = base.returnWindowDays,
            lowStockThreshold = base.lowStockThreshold,
            preShipmentFeedbackHours = base.preShipmentFeedbackHours,
            paymentGateways = listOf(
                base.paymentGateways.single(),
                PaymentGatewayOption.constructFromPersisted(PaymentGatewayCode.SAMAN, "بانک سامان", "#1D5FA8", "س", null, isActive = false, isDefault = false, sortOrder = 2),
                PaymentGatewayOption.constructFromPersisted(PaymentGatewayCode.MELLAT, "بانک ملت", "#C8102E", "م", null, isActive = false, isDefault = false, sortOrder = 3),
            ),
            supportPhone = null,
            supportEmail = null,
            supportHours = emptyList(),
            social = emptyList(),
            siteStats = SiteStats.NONE,
            features = ShopFeatures.NONE,
            version = version,
        )
    }

    /** A repository that holds [settings] in memory and saves the way the real one does: only over the version read. */
    class InMemory(var settings: ShopSettings = stored()) : ShopSettingsRepository {
        var saves = 0
            private set

        override fun get(): ShopSettings = settings

        override fun save(settings: ShopSettings) {
            if (settings.version != this.settings.version) throw ConcurrentChangeException(EventAggregateName("SHOP_SETTINGS"), EventAggregateId("shop"))
            saves++
            this.settings = settings.let {
                ShopSettings.constructFromPersisted(
                    it.shippingMethods, it.defaultShippingMethod, it.freeShippingThreshold, it.freeShippingMethods, it.nonShippingWeekdays,
                    it.paymentPendingMinutes, it.reservationEnabled, it.reservationHoldDays, it.returnWindowDays, it.lowStockThreshold,
                    it.preShipmentFeedbackHours, it.paymentGateways, it.supportPhone, it.supportEmail, it.supportHours, it.social, it.siteStats,
                    it.features, it.version + 1,
                )
            }
        }
    }

    /** A repository that always answers [settings]. */
    fun repositoryOf(settings: ShopSettings = generate()): ShopSettingsRepository = object : ShopSettingsRepository {
        override fun get(): ShopSettings = settings

        override fun save(settings: ShopSettings) = error("This repository only reads")
    }
}
