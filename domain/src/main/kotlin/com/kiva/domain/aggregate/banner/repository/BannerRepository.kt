package com.kiva.domain.aggregate.banner.repository

import com.kiva.domain.aggregate.banner.entity.Banner
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import java.time.Instant

/** Banners, a row each. Nothing unique but the id. */
interface BannerRepository {

    fun nextId(): BannerId

    fun find(id: BannerId): Banner?

    /** Every banner, by placement, then `sortOrder`, then id (the panel's list; decision 10). */
    fun findAll(): List<Banner>

    /** Those of [placement] showing at [now], by `sortOrder` then id, at most [limit] (the shop's; decision 10). */
    fun findVisible(placement: BannerPlacement, now: Instant, limit: Int): List<Banner>

    fun add(banner: Banner)

    fun save(banner: Banner)

    /** Removes it for good; false when there was none. */
    fun delete(id: BannerId): Boolean
}
