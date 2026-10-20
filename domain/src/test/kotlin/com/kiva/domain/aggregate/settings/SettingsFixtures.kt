package com.kiva.domain.aggregate.settings

import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodName
import com.kiva.domain.aggregate.settings.model.valueobject.ShopFeatures
import com.kiva.domain.aggregate.settings.model.valueobject.SiteStats
import com.kiva.domain.aggregate.settings.model.valueobject.TrackingUrlTemplate
import java.time.DayOfWeek

/** The shop's settings as V12 and V30 write them, and a change that keeps every value; each test changes what it is about. */
object SettingsFixtures {

    fun stored(version: Long = 3): ShopSettings = ShopSettings.constructFromPersisted(
        shippingMethods = listOf(
            ShippingMethod.constructFromPersisted(
                ShippingMethodCode.TIPAX, "تیپاکس", Money.constructFromPersisted(145_000), DeliveryDays.constructFromPersisted(1, 2), "truck",
                isActive = true, sortOrder = 1, trackingUrlTemplate = TrackingUrlTemplate.constructFromPersisted("https://tipaxco.com/tracking?code={code}"),
            ),
            ShippingMethod.constructFromPersisted(
                ShippingMethodCode.POST, "پست معمولی", Money.constructFromPersisted(75_000), DeliveryDays.constructFromPersisted(3, 5), "box",
                isActive = true, sortOrder = 2, trackingUrlTemplate = TrackingUrlTemplate.constructFromPersisted("https://tracking.post.ir/?id={code}"),
            ),
        ),
        defaultShippingMethod = ShippingMethodCode.POST,
        freeShippingThreshold = Money.constructFromPersisted(3_000_000),
        freeShippingMethods = setOf(ShippingMethodCode.POST),
        nonShippingWeekdays = setOf(DayOfWeek.FRIDAY),
        paymentPendingMinutes = 15,
        reservationEnabled = true,
        reservationHoldDays = 4,
        returnWindowDays = 7,
        lowStockThreshold = 5,
        preShipmentFeedbackHours = 24,
        paymentGateways = listOf(
            PaymentGatewayOption.constructFromPersisted(PaymentGatewayCode.ZARINPAL, "زرین‌پال", "#F2C230", "ز", null, isActive = true, isDefault = true, sortOrder = 1),
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

    /** A change that sends back exactly what [settings] holds. */
    fun sameAs(settings: ShopSettings): ShopSettings.Change = ShopSettings.Change(
        shippingMethods = settings.shippingMethods.map {
            ShopSettings.ShippingMethodDraft(
                it.code, ShippingMethodName.constructFromPersisted(it.name), it.baseCost, it.deliveryDays, IconName.constructFromPersisted(it.icon), it.isActive,
                it.trackingUrlTemplate,
            )
        },
        defaultShippingMethod = settings.defaultShippingMethod,
        freeShippingThreshold = settings.freeShippingThreshold,
        freeShippingMethods = settings.freeShippingMethods.toList(),
        reservationEnabled = settings.reservationEnabled,
        reservationHoldDays = settings.reservationHoldDays,
        returnWindowDays = settings.returnWindowDays,
        lowStockThreshold = settings.lowStockThreshold,
        preShipmentFeedbackHours = settings.preShipmentFeedbackHours,
        paymentPendingMinutes = settings.paymentPendingMinutes,
        nonShippingWeekdays = settings.nonShippingWeekdays,
        supportPhone = settings.supportPhone,
        supportEmail = settings.supportEmail,
        supportHours = settings.supportHours,
        social = settings.social,
        paymentGateways = settings.paymentGateways.map { ShopSettings.GatewayDraft(it.code, it.isActive, it.isDefault) },
        siteStats = settings.siteStats,
        features = settings.features,
    )

    /** Every leaf of [failure] as `path CODE`, the way the HTTP layer builds `errors[].field`. */
    fun pathsOf(failure: ResultFailure?, parent: String? = null): List<String> {
        if (failure == null) return emptyList()
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { pathsOf(it, path) }
        return listOf(listOfNotNull(path, failure.code).joinToString(" "))
    }
}
