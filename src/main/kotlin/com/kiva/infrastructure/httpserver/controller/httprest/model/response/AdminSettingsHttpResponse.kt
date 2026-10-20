package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminSettingsDto
import com.kiva.domain.aggregate.settings.model.valueobject.Satisfaction
import com.kiva.domain.aggregate.settings.model.valueobject.SatisfactionMetricKey
import com.kiva.domain.aggregate.settings.model.valueobject.SiteStats
import kotlinx.serialization.Serializable
import java.time.DayOfWeek

/**
 * Contract schema `AdminSettings`: the whole set, every field (roadmap phase 4, decision 1), lists in their display
 * order. A method's `description` is built from its days, as everywhere else (CartTexts.delivery).
 */
@Serializable
data class AdminSettingsHttpResponse(
    val shippingMethods: List<ShippingMethodSettingHttpResponse>,
    val defaultShippingMethod: String,
    val freeShippingThreshold: Long,
    val freeShippingMethods: List<String>,
    val reservationEnabled: Boolean,
    val reservationHoldDays: Int,
    val returnWindowDays: Int,
    val lowStockThreshold: Int,
    val preShipmentFeedbackHours: Int,
    val paymentPendingMinutes: Int,
    val nonShippingWeekdays: List<String>,
    val supportPhone: String?,
    val supportEmail: String?,
    val supportHours: List<SupportHoursHttpResponse>,
    val social: List<SocialLinkHttpResponse>,
    val paymentGateways: List<PaymentGatewaySettingHttpResponse>,
    val siteStats: SiteStatsHttpResponse,
    val features: FeaturesHttpResponse,
) {
    companion object {
        fun from(dto: AdminSettingsDto): AdminSettingsHttpResponse {
            val settings = dto.settings
            return AdminSettingsHttpResponse(
                shippingMethods = settings.shippingMethods.map {
                    ShippingMethodSettingHttpResponse(
                        code = it.code.name,
                        name = it.name,
                        description = CartTexts.delivery(it.deliveryDays),
                        baseCost = it.baseCost.toman,
                        minDays = it.deliveryDays.min,
                        maxDays = it.deliveryDays.max,
                        icon = it.icon,
                        trackingUrlTemplate = it.trackingUrlTemplate?.value,
                        isActive = it.isActive,
                    )
                },
                defaultShippingMethod = settings.defaultShippingMethod.name,
                freeShippingThreshold = settings.freeShippingThreshold.toman,
                freeShippingMethods = settings.freeShippingMethods.sorted().map { it.name },
                reservationEnabled = settings.reservationEnabled,
                reservationHoldDays = settings.reservationHoldDays,
                returnWindowDays = settings.returnWindowDays,
                lowStockThreshold = settings.lowStockThreshold,
                preShipmentFeedbackHours = settings.preShipmentFeedbackHours,
                paymentPendingMinutes = settings.paymentPendingMinutes,
                nonShippingWeekdays = settings.nonShippingWeekdays.sortedBy(::weekOrder).map { it.name },
                supportPhone = settings.supportPhone?.value,
                supportEmail = settings.supportEmail?.value,
                supportHours = settings.supportHours.map {
                    SupportHoursHttpResponse(it.label, it.weekdays.map { day -> day.name }, it.from?.toString(), it.to?.toString(), it.messengerOnly)
                },
                social = settings.social.map { SocialLinkHttpResponse(it.channel.name, it.name, it.url, it.handle) },
                paymentGateways = settings.paymentGateways.map {
                    PaymentGatewaySettingHttpResponse(it.code.name, it.name, it.isActive, it.isDefault, available = it.code in dto.available)
                },
                siteStats = SiteStatsHttpResponse.from(settings.siteStats),
                features = settings.features.let { FeaturesHttpResponse(it.wishlistShare, it.reviewMedia, it.giftWrap) },
            )
        }

        /** The Iranian week: Saturday first. */
        private fun weekOrder(day: DayOfWeek): Int = (day.value + 1) % 7
    }
}

/** Contract schema `ShippingMethod`. */
@Serializable
data class ShippingMethodSettingHttpResponse(
    val code: String,
    val name: String,
    val description: String,
    val baseCost: Long,
    val minDays: Int,
    val maxDays: Int,
    val icon: String,
    val trackingUrlTemplate: String?,
    val isActive: Boolean,
)

/** Contract schema `SupportHours`. */
@Serializable
data class SupportHoursHttpResponse(val label: String, val weekdays: List<String>, val from: String?, val to: String?, val messengerOnly: Boolean)

/** Contract schema `SocialLink`. */
@Serializable
data class SocialLinkHttpResponse(val channel: String, val name: String, val url: String, val handle: String?)

/** Contract schema `AdminPaymentGatewaySetting`. */
@Serializable
data class PaymentGatewaySettingHttpResponse(val code: String, val name: String, val isActive: Boolean, val isDefault: Boolean, val available: Boolean)

/** Contract schema `SiteStats`. */
@Serializable
data class SiteStatsHttpResponse(
    val happyCustomers: Int,
    val preShipmentPhotos: Int,
    val photoMatchSatisfactionPercent: Int,
    val productModels: Int,
    val satisfaction: SatisfactionSummaryHttpResponse?,
) {
    companion object {
        fun from(stats: SiteStats) = SiteStatsHttpResponse(
            stats.happyCustomers, stats.preShipmentPhotos, stats.photoMatchSatisfactionPercent, stats.productModels,
            stats.satisfaction?.let(SatisfactionSummaryHttpResponse::from),
        )
    }
}

/** Contract schema `SatisfactionSummary`. [averageRating] has one decimal (4.9), written without a Double. */
@Serializable
data class SatisfactionSummaryHttpResponse(
    @Serializable(with = PerMilleAsPercentSerializer::class)
    val averageRating: Int,
    val scale: Int,
    val totalCount: Int,
    val metrics: List<SatisfactionMetricHttpResponse>,
    val sources: List<String>,
) {
    companion object {
        // The average is kept in tenths, the same digits as a per-mille shown as a percent: 49 is 4.9.
        fun from(satisfaction: Satisfaction) = SatisfactionSummaryHttpResponse(
            averageRating = satisfaction.averageRatingTenths,
            scale = Satisfaction.SCALE,
            totalCount = satisfaction.totalCount,
            metrics = satisfaction.metrics.map { SatisfactionMetricHttpResponse(it.key.name, SettingsTexts.metric(it.key), it.percent) },
            sources = satisfaction.sources.map { it.name },
        )
    }
}

@Serializable
data class SatisfactionMetricHttpResponse(val key: String, val label: String, val percent: Int)

/** Contract `AdminSettings.features`. */
@Serializable
data class FeaturesHttpResponse(val wishlistShare: Boolean, val reviewMedia: Boolean, val giftWrap: Boolean)

/** The settings' Persian words, made here and nowhere else (roadmap section 1, row 16). */
object SettingsTexts {
    fun metric(key: SatisfactionMetricKey): String = when (key) {
        SatisfactionMetricKey.PHOTO_MATCH -> "تطابق با عکس"
        SatisfactionMetricKey.QUALITY -> "کیفیت"
        SatisfactionMetricKey.SHIPPING_SPEED -> "سرعت ارسال"
        SatisfactionMetricKey.PACKAGING -> "بسته‌بندی"
        SatisfactionMetricKey.SUPPORT -> "پشتیبانی"
    }
}
