package com.kiva.domain.aggregate.settings.repository

import com.kiva.domain.aggregate.settings.entity.ShopSettings

/** Reads the shop's settings. There is always exactly one set (the migration writes it), so this never returns null. */
interface ShopSettingsRepository {
    fun get(): ShopSettings
}
