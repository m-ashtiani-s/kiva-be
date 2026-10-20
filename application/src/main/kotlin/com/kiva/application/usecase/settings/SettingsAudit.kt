package com.kiva.application.usecase.settings

import com.kiva.application.port.AuditChange
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.Satisfaction
import com.kiva.domain.aggregate.settings.model.valueobject.SupportHours

/**
 * The `changes` of a SETTINGS_UPDATED row: each field of the set that changed, one by one, so the log says «the TIPAX
 * cost went from 145000 to 160000» rather than «the methods changed». A method's or a gateway's own values are under its
 * code (`shippingMethods.TIPAX.baseCost`), the lists' order on its own (`shippingMethods.order`). The support phone and
 * email are the shop's own public contact, published on the site, so they are logged as they are.
 */
internal object SettingsAudit {

    fun changes(before: ShopSettings, after: ShopSettings): List<AuditChange> = AuditDiff.build {
        val methodsBefore = before.shippingMethods.associateBy { it.code }
        after.shippingMethods.forEach { method ->
            val old = methodsBefore[method.code]
            val at = "shippingMethods.${method.code.name}"
            field("$at.name", old?.name, method.name)
            field("$at.baseCost", old?.baseCost, method.baseCost)
            field("$at.minDays", old?.deliveryDays?.min, method.deliveryDays.min)
            field("$at.maxDays", old?.deliveryDays?.max, method.deliveryDays.max)
            field("$at.icon", old?.icon, method.icon)
            field("$at.isActive", old?.isActive, method.isActive)
            field("$at.trackingUrlTemplate", old?.trackingUrlTemplate?.value, method.trackingUrlTemplate?.value)
        }
        list("shippingMethods.order", before.shippingMethods.map { it.code.name }, after.shippingMethods.map { it.code.name })
        field("defaultShippingMethod", before.defaultShippingMethod, after.defaultShippingMethod)
        field("freeShippingThreshold", before.freeShippingThreshold, after.freeShippingThreshold)
        list("freeShippingMethods", before.freeShippingMethods.sorted().map { it.name }, after.freeShippingMethods.sorted().map { it.name })
        field("reservationEnabled", before.reservationEnabled, after.reservationEnabled)
        field("reservationHoldDays", before.reservationHoldDays, after.reservationHoldDays)
        field("returnWindowDays", before.returnWindowDays, after.returnWindowDays)
        field("lowStockThreshold", before.lowStockThreshold, after.lowStockThreshold)
        field("preShipmentFeedbackHours", before.preShipmentFeedbackHours, after.preShipmentFeedbackHours)
        field("paymentPendingMinutes", before.paymentPendingMinutes, after.paymentPendingMinutes)
        list("nonShippingWeekdays", before.nonShippingWeekdays.sorted().map { it.name }, after.nonShippingWeekdays.sorted().map { it.name })
        field("supportPhone", before.supportPhone?.value, after.supportPhone?.value)
        field("supportEmail", before.supportEmail?.value, after.supportEmail?.value)
        list("supportHours", before.supportHours.map(::hoursOf), after.supportHours.map(::hoursOf))
        list(
            "social",
            before.social.map { mapOf("channel" to it.channel, "name" to it.name, "url" to it.url, "handle" to it.handle) },
            after.social.map { mapOf("channel" to it.channel, "name" to it.name, "url" to it.url, "handle" to it.handle) },
        )
        val gatewaysBefore = before.paymentGateways.associateBy { it.code }
        after.paymentGateways.forEach { gateway ->
            val old = gatewaysBefore[gateway.code]
            field("paymentGateways.${gateway.code.name}.isActive", old?.isActive, gateway.isActive)
            field("paymentGateways.${gateway.code.name}.isDefault", old?.isDefault, gateway.isDefault)
        }
        list("paymentGateways.order", before.paymentGateways.map { it.code.name }, after.paymentGateways.map { it.code.name })
        field("siteStats.happyCustomers", before.siteStats.happyCustomers, after.siteStats.happyCustomers)
        field("siteStats.preShipmentPhotos", before.siteStats.preShipmentPhotos, after.siteStats.preShipmentPhotos)
        field("siteStats.photoMatchSatisfactionPercent", before.siteStats.photoMatchSatisfactionPercent, after.siteStats.photoMatchSatisfactionPercent)
        field("siteStats.productModels", before.siteStats.productModels, after.siteStats.productModels)
        json("siteStats.satisfaction", before.siteStats.satisfaction?.let(::satisfactionOf), after.siteStats.satisfaction?.let(::satisfactionOf))
        val featuresAfter = after.features.byKey()
        before.features.byKey().forEach { (key, was) -> field("features.$key", was, featuresAfter.getValue(key)) }
    }

    private fun hoursOf(hours: SupportHours): Map<String, Any?> = mapOf(
        "label" to hours.label, "weekdays" to hours.weekdays.map { it.name }, "from" to hours.from?.toString(), "to" to hours.to?.toString(),
        "messengerOnly" to hours.messengerOnly,
    )

    private fun satisfactionOf(s: Satisfaction): Map<String, Any?> = mapOf(
        "averageRatingTenths" to s.averageRatingTenths, "totalCount" to s.totalCount,
        "metrics" to s.metrics.map { mapOf("key" to it.key, "percent" to it.percent) }, "sources" to s.sources,
    )
}
