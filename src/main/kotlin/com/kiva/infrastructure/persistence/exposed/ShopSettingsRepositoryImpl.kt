package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.Satisfaction
import com.kiva.domain.aggregate.settings.model.valueobject.SatisfactionMetric
import com.kiva.domain.aggregate.settings.model.valueobject.SatisfactionMetricKey
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShopFeatures
import com.kiva.domain.aggregate.settings.model.valueobject.SiteStats
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import com.kiva.domain.aggregate.settings.model.valueobject.SocialLink
import com.kiva.domain.aggregate.settings.model.valueobject.SupportHours
import com.kiva.domain.aggregate.settings.model.valueobject.SupportPhone
import com.kiva.domain.aggregate.settings.model.valueobject.TrackingUrlTemplate
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.model.valueobject.Email
import com.kiva.infrastructure.persistence.exposed.tbl.FeaturesRow
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentGatewayTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ShippingMethodTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ShopSettingsTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SiteStatsRow
import com.kiva.infrastructure.persistence.exposed.tbl.SocialLinkRow
import com.kiva.infrastructure.persistence.exposed.tbl.SupportHoursRow
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Runs inside the use case's transaction; it never opens one itself. Three small reads: the row and its two lists.
 * A save writes the row first, and only over the version it was read in, so a second save at the same moment waits for
 * the first one's row lock, then finds the version gone and writes nothing (ConcurrentChangeException).
 */
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
            lowStockThreshold = row[ShopSettingsTbl.lowStockThreshold],
            preShipmentFeedbackHours = row[ShopSettingsTbl.preShipmentFeedbackHours],
            paymentGateways = PaymentGatewayTbl.selectAll()
                .orderBy(PaymentGatewayTbl.sortOrder to SortOrder.ASC, PaymentGatewayTbl.code to SortOrder.ASC)
                .map { it.toPaymentGateway() },
            supportPhone = row[ShopSettingsTbl.supportPhone]?.let(SupportPhone::constructFromPersisted),
            supportEmail = row[ShopSettingsTbl.supportEmail]?.let(Email::constructFromPersisted),
            supportHours = row[ShopSettingsTbl.supportHours].map { it.toSupportHours() },
            social = row[ShopSettingsTbl.social].map { it.toSocialLink() },
            siteStats = row[ShopSettingsTbl.siteStats].toSiteStats(),
            features = row[ShopSettingsTbl.features].let { ShopFeatures.constructFromPersisted(it.wishlistShare, it.reviewMedia, it.giftWrap) },
            version = row[ShopSettingsTbl.version],
        )
    }

    override fun save(settings: ShopSettings) {
        val updated = ShopSettingsTbl.update({ ShopSettingsTbl.version eq settings.version }) {
            it[freeShippingThreshold] = settings.freeShippingThreshold.toman
            it[freeShippingMethods] = settings.freeShippingMethods.sortedBy { method -> method.ordinal }.map { method -> method.name }
            it[defaultShippingMethod] = settings.defaultShippingMethod
            it[nonShippingWeekdays] = settings.nonShippingWeekdays.sorted().map { day -> day.name }
            it[paymentPendingMinutes] = settings.paymentPendingMinutes
            it[reservationEnabled] = settings.reservationEnabled
            it[reservationHoldDays] = settings.reservationHoldDays
            it[returnWindowDays] = settings.returnWindowDays
            it[lowStockThreshold] = settings.lowStockThreshold
            it[preShipmentFeedbackHours] = settings.preShipmentFeedbackHours
            it[supportPhone] = settings.supportPhone?.value
            it[supportEmail] = settings.supportEmail?.value
            it[supportHours] = settings.supportHours.map { hours -> hours.toRow() }
            it[social] = settings.social.map { link -> SocialLinkRow(link.channel.name, link.name, link.url, link.handle) }
            it[siteStats] = settings.siteStats.toRow()
            it[features] = settings.features.let { f -> FeaturesRow(f.wishlistShare, f.reviewMedia, f.giftWrap) }
            it[updatedOn] = CurrentTimestampWithTimeZone
            it[version] = settings.version + 1
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, AGGREGATE_ID)

        settings.shippingMethods.forEach { method ->
            ShippingMethodTbl.update({ ShippingMethodTbl.code eq method.code }) {
                it[name] = method.name
                it[baseCost] = method.baseCost.toman
                it[minDays] = method.deliveryDays.min
                it[maxDays] = method.deliveryDays.max
                it[icon] = method.icon
                it[isActive] = method.isActive
                it[sortOrder] = method.sortOrder
                it[trackingUrlTemplate] = method.trackingUrlTemplate?.value
            }
        }
        // At most one default at any moment (paymentgateway_one_default_idx): none first, then the one.
        PaymentGatewayTbl.update { it[isDefault] = false }
        settings.paymentGateways.forEach { gateway ->
            PaymentGatewayTbl.update({ PaymentGatewayTbl.code eq gateway.code }) {
                it[isActive] = gateway.isActive
                it[isDefault] = gateway.isDefault
                it[sortOrder] = gateway.sortOrder
            }
        }
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
            trackingUrlTemplate = this[ShippingMethodTbl.trackingUrlTemplate]?.let(TrackingUrlTemplate::constructFromPersisted),
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

    private fun SupportHoursRow.toSupportHours(): SupportHours = SupportHours.constructFromPersisted(
        label, weekdays.map(DayOfWeek::valueOf), from?.let(LocalTime::parse), to?.let(LocalTime::parse), messengerOnly,
    )

    private fun SupportHours.toRow() = SupportHoursRow(label, weekdays.map { it.name }, from?.toString(), to?.toString(), messengerOnly)

    private fun SocialLinkRow.toSocialLink(): SocialLink = SocialLink.constructFromPersisted(SocialChannel.valueOf(channel), name, url, handle)

    private fun SiteStatsRow.toSiteStats(): SiteStats = SiteStats.constructFromPersisted(
        happyCustomers, preShipmentPhotos, photoMatchSatisfactionPercent, productModels,
        satisfaction?.let { s ->
            Satisfaction.constructFromPersisted(
                s.averageRatingTenths, s.totalCount, s.metrics.map { SatisfactionMetric(SatisfactionMetricKey.valueOf(it.key), it.percent) },
                s.sources.map(SocialChannel::valueOf),
            )
        },
    )

    private fun SiteStats.toRow() = SiteStatsRow(
        happyCustomers, preShipmentPhotos, photoMatchSatisfactionPercent, productModels,
        satisfaction?.let { s ->
            SiteStatsRow.SatisfactionRow(s.averageRatingTenths, s.totalCount, s.metrics.map { SiteStatsRow.MetricRow(it.key.name, it.percent) }, s.sources.map { it.name })
        },
    )

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("SHOP_SETTINGS")
        val AGGREGATE_ID = EventAggregateId("shop")
    }
}
