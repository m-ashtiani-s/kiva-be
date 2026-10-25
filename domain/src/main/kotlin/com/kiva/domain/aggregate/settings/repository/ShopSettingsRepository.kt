package com.kiva.domain.aggregate.settings.repository

import com.kiva.domain.aggregate.settings.entity.ShopSettings

/** Reads and saves the shop's settings. There is always exactly one set (the migration writes it), so [get] never returns null. */
interface ShopSettingsRepository {
    fun get(): ShopSettings

    /**
     * Writes the whole set, its lists included, only over the version it was read in, and counts the version up: a save
     * that another one beat is a ConcurrentChangeException (409 CHANGED_BY_SOMEONE_ELSE), and nothing of it is written.
     */
    fun save(settings: ShopSettings)
}
