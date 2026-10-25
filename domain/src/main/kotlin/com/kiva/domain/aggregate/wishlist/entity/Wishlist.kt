package com.kiva.domain.aggregate.wishlist.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import java.time.Instant

/**
 * One customer's whole wishlist (roadmap section 14.3): the aggregate is the list, because its rule is on the list, at
 * most [WishlistPolicy.MAX_ITEMS] products, counted over every row, hidden drafts too (decision 6). With 200 rows at
 * most reading it all is cheap, and one lock on it (lock 13) keeps the limit under concurrent adds.
 *
 * Kept newest added first, then by product id (decision 7).
 */
class Wishlist private constructor(val userId: UserId, entries: List<WishlistEntry>) {

    private val rows: MutableList<WishlistEntry> = entries.sortedWith(NEWEST_FIRST).toMutableList()

    // Only this class writes the rows, so a broken list is a bug.
    init {
        require(rows.size <= WishlistPolicy.MAX_ITEMS) { "Wishlist of user ${userId.value} has ${rows.size} rows" }
        require(rows.map { it.productId }.toSet().size == rows.size) { "Wishlist of user ${userId.value} has a product twice" }
    }

    /** Newest added first. */
    val entries: List<WishlistEntry> get() = rows.toList()

    fun entryOf(productId: ProductId): WishlistEntry? = rows.firstOrNull { it.productId == productId }

    /**
     * Saves [productId] with the color the customer was looking at ([colorKey], if any) and its price now ([price]).
     * Already saved: only a new color replaces the old one (none keeps it); its price and when it was added stay, so a
     * price drop is counted from the first save (decision 3). A new product in a full list is [LimitReached].
     */
    fun add(productId: ProductId, colorKey: ColorKey?, price: Money, now: Instant): Result<Added, LimitReached> {
        val index = rows.indexOfFirst { it.productId == productId }
        if (index >= 0) {
            if (colorKey != null && colorKey != rows[index].preferredColorKey) rows[index] = rows[index].copy(preferredColorKey = colorKey)
            return Result.success(Added.ALREADY_THERE)
        }
        if (rows.size >= WishlistPolicy.MAX_ITEMS) return Result.failure(LimitReached)
        rows += WishlistEntry(productId, colorKey, price, now)
        rows.sortWith(NEWEST_FIRST)
        return Result.success(Added.ADDED)
    }

    /** Takes [productId] out; false when it wasn't in. */
    fun remove(productId: ProductId): Boolean = rows.removeIf { it.productId == productId }

    /** Takes every product out; false when there was none. */
    fun clear(): Boolean {
        if (rows.isEmpty()) return false
        rows.clear()
        return true
    }

    override fun toString(): String = "Wishlist(userId=${userId.value}, rows=${rows.size})"

    enum class Added { ADDED, ALREADY_THERE }

    /** The list already holds [WishlistPolicy.MAX_ITEMS] products (422, `meta.limit`). */
    data object LimitReached : ResultFailure("WISHLIST_LIMIT_REACHED", FailureKind.BUSINESS_RULE, meta = mapOf("limit" to WishlistPolicy.MAX_ITEMS))

    companion object {
        private val NEWEST_FIRST = compareByDescending<WishlistEntry> { it.addedOn }.thenBy { it.productId.value }

        /** A customer who saved nothing has an empty list: there is nothing to create. */
        fun empty(userId: UserId): Wishlist = Wishlist(userId, emptyList())

        fun constructFromPersisted(userId: UserId, entries: List<WishlistEntry>): Wishlist = Wishlist(userId, entries)
    }
}

/**
 * One saved product.
 *
 * @property preferredColorKey the color the customer was looking at when they saved it, if they said.
 * @property priceAtAdd the sale price of that color then (no color: the card's), never the struck-out one (decision 4).
 */
data class WishlistEntry(val productId: ProductId, val preferredColorKey: ColorKey?, val priceAtAdd: Money, val addedOn: Instant)
