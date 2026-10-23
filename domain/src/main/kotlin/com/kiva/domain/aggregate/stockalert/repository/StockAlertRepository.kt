package com.kiva.domain.aggregate.stockalert.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
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

    /**
     * The panel's queue: the ACTIVE ones grouped by product and color (or «any color»), most waiting first, then the
     * longest waiting; only [productId]'s when given; with [inStock], only the groups that can (true) or can't (false)
     * be bought now. One page of [pageable].
     */
    fun waitingGroups(productId: ProductId?, inStock: Boolean?, pageable: Pageable): Page<WaitingGroup>

    /** Deletes the NOTIFIED and CANCELLED ones that ended before [before]; how many went. */
    fun deleteEndedBefore(before: Instant): Int

    /** Writes the subscription's change (its status and when). */
    fun save(alert: StockAlert)
}

/**
 * The ACTIVE subscriptions of one product and color, as the panel counts them.
 *
 * @property variantId null: «any color».
 * @property available what can be sold now (in stock minus reserved) of that color, or of all the product's active
 *   colors for «any color»; 0 for a color taken out of the shop.
 */
data class WaitingGroup(val productId: ProductId, val variantId: VariantId?, val waitingCount: Int, val oldestOn: Instant, val available: Int)

/** Hands out the ids of new subscriptions from the `stockalertid_seq` database sequence. */
interface StockAlertIdGenerator {
    fun nextStockAlertId(): StockAlertId
}
