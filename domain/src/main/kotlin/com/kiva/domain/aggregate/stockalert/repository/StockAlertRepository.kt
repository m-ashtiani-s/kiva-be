package com.kiva.domain.aggregate.stockalert.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.entity.StockAlert
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import java.time.Instant

/**
 * «موجود شد خبرم کن» subscriptions, a row each. At most one ACTIVE per user, product and color-or-any:
 * StockAlertDomainService checks first, the partial unique index `stockalert_one_active_idx` is the wall ([add] then
 * throws UniqueTakenException). A change takes the row's lock ([lock]).
 */
interface StockAlertRepository {

    /** Stores a new subscription. Throws UniqueTakenException when another ACTIVE one of its key won the race. */
    fun add(alert: StockAlert)

    fun find(id: StockAlertId): StockAlert?

    /** Locks the row and reads it; null when there is none. */
    fun lock(id: StockAlertId): StockAlert?

    /** Whether [userId] has an ACTIVE one of [productId] and [variantId] (null: «any color»). */
    fun activeExists(userId: UserId, productId: ProductId, variantId: VariantId?): Boolean

    fun countActive(userId: UserId): Int

    /** When each subscription [userId] made after [since] was made: the hourly cap's count. */
    fun createdSince(userId: UserId, since: Instant): List<Instant>

    /** «اشتراک‌های من»: ACTIVE ones and those NOTIFIED after [notifiedSince], newest made first, at most [limit]. */
    fun listMine(userId: UserId, notifiedSince: Instant, limit: Int): List<StockAlert>

    /** [userId]'s ACTIVE ones of [productId]: the product page's flags. */
    fun activeOf(userId: UserId, productId: ProductId): List<StockAlert>

    /**
     * The job's candidates: ACTIVE ones whose product is in the shop and whose color (or, for «any color», one active
     * color) can be bought now, oldest made first, at most [limit].
     */
    fun findDue(limit: Int): List<StockAlertId>

    /** How many ACTIVE ones wait for each of [variantIds] (not «any color»): the panel's colors. */
    fun subscribersOf(variantIds: Collection<VariantId>): Map<VariantId, Int>

    /** Deletes the NOTIFIED and CANCELLED ones that ended before [before]; how many went. */
    fun deleteEndedBefore(before: Instant): Int

    /** Writes the subscription's change (its status and when). */
    fun save(alert: StockAlert)
}

/** Hands out the ids of new subscriptions from the `stockalertid_seq` database sequence. */
interface StockAlertIdGenerator {
    fun nextStockAlertId(): StockAlertId
}
