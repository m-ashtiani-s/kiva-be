package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentGatewayTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ShippingMethodTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ShopSettingsTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.DayOfWeek

/** Runs inside the use case's transaction; it never opens one itself. Three small reads: the row and its two lists. */
class ShopSettingsRepositoryImpl : ShopSettingsRepository {

    override fun get(): ShopSettings {
        // single(): the table's singleton column allows one row, and the migration writes it. None is a broken database.
        val row = ShopSettingsTbl.selectAll().single()
        return ShopSettings.constructFromPersisted(
            shippingMethods = ShippingMethodTbl.selectAll()
                .orderBy(ShippingMethodTbl.sortOrder to SortOrder.ASC, ShippingMethodTbl.code to SortOrder.ASC)
                .map { it.toShippingMethod() },
            defaultShippingMethod = row[ShopSettingsTbl.defaultShippingMethod],
            freeShippingThreshold = Money.constructFromPersisted(row[ShopSettingsTbl.freeShippingThreshold]),
            freeShippingMethods = row[ShopSettingsTbl.freeShippingMethods].map { ShippingMethodCode.valueOf(it) }.toSet(),
            nonShippingWeekdays = row[ShopSettingsTbl.nonShippingWeekdays].map { DayOfWeek.valueOf(it) }.toSet(),
            paymentPendingMinutes = row[ShopSettingsTbl.paymentPendingMinutes],
            reservationEnabled = row[ShopSettingsTbl.reservationEnabled],
            reservationHoldDays = row[ShopSettingsTbl.reservationHoldDays],
            returnWindowDays = row[ShopSettingsTbl.returnWindowDays],
            paymentGateways = PaymentGatewayTbl.selectAll()
                .orderBy(PaymentGatewayTbl.sortOrder to SortOrder.ASC, PaymentGatewayTbl.code to SortOrder.ASC)
                .map { it.toPaymentGateway() },
        )
    }

    private fun ResultRow.toShippingMethod(): ShippingMethod =
        ShippingMethod.constructFromPersisted(
            code = this[ShippingMethodTbl.code],
            name = this[ShippingMethodTbl.name],
            baseCost = Money.constructFromPersisted(this[ShippingMethodTbl.baseCost]),
            deliveryDays = DeliveryDays.constructFromPersisted(this[ShippingMethodTbl.minDays], this[ShippingMethodTbl.maxDays]),
            icon = this[ShippingMethodTbl.icon],
            isActive = this[ShippingMethodTbl.isActive],
            sortOrder = this[ShippingMethodTbl.sortOrder],
            trackingUrlTemplate = this[ShippingMethodTbl.trackingUrlTemplate],
        )

    private fun ResultRow.toPaymentGateway(): PaymentGatewayOption =
        PaymentGatewayOption.constructFromPersisted(
            code = this[PaymentGatewayTbl.code],
            name = this[PaymentGatewayTbl.name],
            brandColor = this[PaymentGatewayTbl.brandColor],
            initial = this[PaymentGatewayTbl.initial],
            logoUrl = this[PaymentGatewayTbl.logoUrl],
            isActive = this[PaymentGatewayTbl.isActive],
            isDefault = this[PaymentGatewayTbl.isDefault],
            sortOrder = this[PaymentGatewayTbl.sortOrder],
        )
}
