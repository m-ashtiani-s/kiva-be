package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.settings.entity.ShopSettings
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The shop's settings on kiva_test, for a test that changes them: [read] them before, [restore] them after. The other
 * tests count on the migrations' values (free shipping from 3,000,000, 5 for low stock, …).
 */
object StoredSettings {

    fun read(db: Database): ShopSettings = transaction(db) { ShopSettingsRepositoryImpl().get() }

    /** Writes [original]'s values back over whatever version is there now. */
    fun restore(db: Database, original: ShopSettings) = transaction(db) {
        val repository = ShopSettingsRepositoryImpl()
        repository.save(original.atVersion(repository.get().version))
    }

    /** [settings] with every value kept and [version] as the one it was read in. */
    fun ShopSettings.atVersion(version: Long): ShopSettings = ShopSettings.constructFromPersisted(
        shippingMethods, defaultShippingMethod, freeShippingThreshold, freeShippingMethods, nonShippingWeekdays, paymentPendingMinutes,
        reservationEnabled, reservationHoldDays, returnWindowDays, lowStockThreshold, preShipmentFeedbackHours, paymentGateways, supportPhone,
        supportEmail, supportHours, social, siteStats, features, version,
    )
}
