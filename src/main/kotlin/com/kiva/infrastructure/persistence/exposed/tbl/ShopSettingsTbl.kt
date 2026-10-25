package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The shop's settings, one row. Created and filled by V12__shop_settings.sql and V30__admin_settings.sql (Exposed never
 * creates tables); keep them in step. The lists and documents are JSONB in the shapes below, written whole.
 */
object ShopSettingsTbl : LongIdTable(name = "shopsettings_tbl", columnName = "pId") {
    val freeShippingThreshold = long("freeShippingThreshold")
    val freeShippingMethods = array<String>("freeShippingMethods")
    val defaultShippingMethod = enumerationByName<ShippingMethodCode>("defaultShippingMethod", 10)
    val nonShippingWeekdays = array<String>("nonShippingWeekdays")
    val paymentPendingMinutes = integer("paymentPendingMinutes")
    val reservationEnabled = bool("reservationEnabled")
    val reservationHoldDays = integer("reservationHoldDays")
    val returnWindowDays = integer("returnWindowDays")
    val lowStockThreshold = integer("lowStockThreshold")
    val preShipmentFeedbackHours = integer("preShipmentFeedbackHours")
    val supportPhone = varchar("supportPhone", 20).nullable()
    val supportEmail = varchar("supportEmail", 100).nullable()
    val supportHours = jsonb<List<SupportHoursRow>>("supportHours", Json)
    val social = jsonb<List<SocialLinkRow>>("social", Json)
    val siteStats = jsonb<SiteStatsRow>("siteStats", Json)
    val features = jsonb<FeaturesRow>("features", Json)
    val updatedOn = timestampWithTimeZone("updatedOn")
    val version = long("version")
}

/** One line of `supportHours`: [weekdays] as java.time.DayOfWeek names, [from] and [to] as `HH:mm`. */
@Serializable
data class SupportHoursRow(val label: String, val weekdays: List<String>, val from: String? = null, val to: String? = null, val messengerOnly: Boolean = false)

/** One link of `social`. */
@Serializable
data class SocialLinkRow(val channel: String, val name: String, val url: String, val handle: String? = null)

/** `siteStats`; the satisfaction's average in tenths (49 is 4.9), never a float. */
@Serializable
data class SiteStatsRow(
    val happyCustomers: Int,
    val preShipmentPhotos: Int,
    val photoMatchSatisfactionPercent: Int,
    val productModels: Int,
    val satisfaction: SatisfactionRow? = null,
) {
    @Serializable
    data class SatisfactionRow(val averageRatingTenths: Int, val totalCount: Int, val metrics: List<MetricRow>, val sources: List<String>)

    @Serializable
    data class MetricRow(val key: String, val percent: Int)
}

/** `features`: exactly the three switches (V30's CHECK). */
@Serializable
data class FeaturesRow(val wishlistShare: Boolean, val reviewMedia: Boolean, val giftWrap: Boolean)

/** The shipping methods of the settings. Created and filled by V12__shop_settings.sql; keep both in step. */
object ShippingMethodTbl : LongIdTable(name = "shippingmethod_tbl", columnName = "pId") {
    val code = enumerationByName<ShippingMethodCode>("code", 10).uniqueIndex()
    val name = varchar("name", 50)
    val baseCost = long("baseCost")
    val minDays = integer("minDays")
    val maxDays = integer("maxDays")
    val icon = varchar("icon", 30)
    val isActive = bool("isActive")
    val sortOrder = integer("sortOrder")
    val trackingUrlTemplate = varchar("trackingUrlTemplate", 300).nullable()
}

/** The payment gateways of the settings. Created and filled by V12__shop_settings.sql; keep both in step. */
object PaymentGatewayTbl : LongIdTable(name = "paymentgateway_tbl", columnName = "pId") {
    val code = enumerationByName<PaymentGatewayCode>("code", 10).uniqueIndex()
    val name = varchar("name", 50)
    val brandColor = varchar("brandColor", 7)
    val initial = varchar("initial", 1)
    val logoUrl = varchar("logoUrl", 500).nullable()
    val isActive = bool("isActive")
    val isDefault = bool("isDefault")
    val sortOrder = integer("sortOrder")
}
