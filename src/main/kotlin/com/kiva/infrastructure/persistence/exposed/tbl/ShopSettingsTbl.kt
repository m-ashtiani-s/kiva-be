package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** The shop's settings, one row. Created and filled by V12__shop_settings.sql (Exposed never creates tables); keep both in step. */
object ShopSettingsTbl : LongIdTable(name = "shopsettings_tbl", columnName = "pId") {
    val freeShippingThreshold = long("freeShippingThreshold")
    val freeShippingMethods = array<String>("freeShippingMethods")
    val defaultShippingMethod = enumerationByName<ShippingMethodCode>("defaultShippingMethod", 10)
    val nonShippingWeekdays = array<String>("nonShippingWeekdays")
    val paymentPendingMinutes = integer("paymentPendingMinutes")
    val reservationEnabled = bool("reservationEnabled")
    val reservationHoldDays = integer("reservationHoldDays")
    val returnWindowDays = integer("returnWindowDays")
    val updatedOn = timestampWithTimeZone("updatedOn")
    val version = long("version")
}

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
