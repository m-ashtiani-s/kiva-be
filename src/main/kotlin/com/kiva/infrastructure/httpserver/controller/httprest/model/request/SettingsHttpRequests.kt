package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.application.usecase.settings.ShopSettingsFields
import com.kiva.common.patch.Patch
import com.kiva.domain.aggregate.settings.model.valueobject.Satisfaction
import com.kiva.infrastructure.common.http.PatchSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contract schema `AdminSettings` as `PUT /admin/settings` sends it. Every field nullable with a default, so one left out
 * is reported by the use case with every other problem (FIELD_REQUIRED). [supportPhone], [supportEmail] and
 * `siteStats.satisfaction` are required but nullable: a [Patch] tells «left out» from «sent as null». The contract's
 * read-only fields (a method's `description`, a gateway's `name` and `available`, `scale`, a metric's `label`) are not
 * here, so they are ignored.
 */
@Serializable
data class AdminSettingsHttpRequest(
    val shippingMethods: List<ShippingMethodSettingHttpRequest>? = null,
    val defaultShippingMethod: String? = null,
    val freeShippingThreshold: Long? = null,
    val freeShippingMethods: List<String>? = null,
    val reservationEnabled: Boolean? = null,
    val reservationHoldDays: Int? = null,
    val returnWindowDays: Int? = null,
    val lowStockThreshold: Int? = null,
    val preShipmentFeedbackHours: Int? = null,
    val paymentPendingMinutes: Int? = null,
    val nonShippingWeekdays: List<String>? = null,
    @Serializable(with = PatchSerializer::class) val supportPhone: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val supportEmail: Patch<String?> = Patch.Unchanged,
    val supportHours: List<SupportHoursHttpRequest>? = null,
    val social: List<SocialLinkHttpRequest>? = null,
    val paymentGateways: List<PaymentGatewaySettingHttpRequest>? = null,
    val siteStats: SiteStatsHttpRequest? = null,
    val features: Map<String, Boolean?>? = null,
) {
    fun toFields() = ShopSettingsFields(
        shippingMethods = shippingMethods?.map {
            ShopSettingsFields.ShippingMethod(it.code, it.name, it.baseCost, it.minDays, it.maxDays, it.icon, it.isActive, it.trackingUrlTemplate)
        },
        defaultShippingMethod = defaultShippingMethod,
        freeShippingThreshold = freeShippingThreshold,
        freeShippingMethods = freeShippingMethods,
        reservationEnabled = reservationEnabled,
        reservationHoldDays = reservationHoldDays,
        returnWindowDays = returnWindowDays,
        lowStockThreshold = lowStockThreshold,
        preShipmentFeedbackHours = preShipmentFeedbackHours,
        paymentPendingMinutes = paymentPendingMinutes,
        nonShippingWeekdays = nonShippingWeekdays,
        supportPhone = supportPhone,
        supportEmail = supportEmail,
        supportHours = supportHours?.map { ShopSettingsFields.Hours(it.label, it.weekdays, it.from, it.to, it.messengerOnly) },
        social = social?.map { ShopSettingsFields.Social(it.channel, it.name, it.url, it.handle) },
        paymentGateways = paymentGateways?.map { ShopSettingsFields.Gateway(it.code, it.isActive, it.isDefault) },
        siteStats = siteStats?.let { stats ->
            ShopSettingsFields.Stats(
                stats.happyCustomers, stats.preShipmentPhotos, stats.photoMatchSatisfactionPercent, stats.productModels,
                when (val summary = stats.satisfaction) {
                    Patch.Unchanged -> Patch.Unchanged
                    is Patch.Set -> Patch.Set(
                        summary.value?.let { s ->
                            ShopSettingsFields.SatisfactionSummary(
                                averageRating = s.averageRating?.content,
                                totalCount = s.totalCount,
                                metrics = s.metrics?.map { Satisfaction.MetricInput(it.key, it.percent) },
                                sources = s.sources,
                            )
                        },
                    )
                },
            )
        },
        features = features,
    )
}

/** Contract schema `ShippingMethod` in a `PUT`: `description` is read-only, so left out. */
@Serializable
data class ShippingMethodSettingHttpRequest(
    val code: String? = null,
    val name: String? = null,
    val baseCost: Long? = null,
    val minDays: Int? = null,
    val maxDays: Int? = null,
    val icon: String? = null,
    val trackingUrlTemplate: String? = null,
    val isActive: Boolean? = null,
)

/** Contract schema `SupportHours`. */
@Serializable
data class SupportHoursHttpRequest(
    val label: String? = null,
    val weekdays: List<String>? = null,
    val from: String? = null,
    val to: String? = null,
    val messengerOnly: Boolean? = null,
)

/** Contract schema `SocialLink`. */
@Serializable
data class SocialLinkHttpRequest(val channel: String? = null, val name: String? = null, val url: String? = null, val handle: String? = null)

/** Contract schema `AdminPaymentGatewaySetting` in a `PUT`: `name` and `available` are read-only, so left out. */
@Serializable
data class PaymentGatewaySettingHttpRequest(val code: String? = null, val isActive: Boolean? = null, val isDefault: Boolean? = null)

/** Contract schema `SiteStats` in a `PUT`; `satisfaction` is required but nullable. */
@Serializable
data class SiteStatsHttpRequest(
    val happyCustomers: Int? = null,
    val preShipmentPhotos: Int? = null,
    val photoMatchSatisfactionPercent: Int? = null,
    val productModels: Int? = null,
    @Serializable(with = PatchSerializer::class) val satisfaction: Patch<SatisfactionSummaryHttpRequest?> = Patch.Unchanged,
)

/**
 * Contract schema `SatisfactionSummary` in a `PUT`. [averageRating] is read as the JSON number's own text (`4.9`), so it
 * never goes through a Double; `scale` and a metric's `label` are read-only.
 */
@Serializable
data class SatisfactionSummaryHttpRequest(
    val averageRating: JsonPrimitive? = null,
    val totalCount: Int? = null,
    val metrics: List<SatisfactionMetricHttpRequest>? = null,
    val sources: List<String?>? = null,
)

@Serializable
data class SatisfactionMetricHttpRequest(val key: String? = null, val percent: Int? = null)
