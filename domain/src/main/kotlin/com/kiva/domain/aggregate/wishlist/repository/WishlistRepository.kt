package com.kiva.domain.aggregate.wishlist.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.wishlist.entity.Wishlist

/**
 * Wishlists, a row per saved product. A change takes the list's lock first ([lockFor]; roadmap section 4.11, lock 13):
 * an advisory lock keyed by the customer, which works for a list with no row yet. «Visible» is a product in the shop
 * now (ACTIVE): a draft's row stays but is not shown (decision 6).
 */
interface WishlistRepository {

    /** Locks [userId]'s list and reads it, every row (hidden ones too); an empty list when there is none. */
    fun lockFor(userId: UserId): Wishlist

    /** [userId]'s list for reading only, every row. */
    fun find(userId: UserId): Wishlist

    /** Writes the list's rows as they are now: rows gone are deleted, new ones inserted, changed ones updated. */
    fun save(wishlist: Wishlist)

    /** The visible products of [userId]'s list, newest added first. */
    fun visibleIdsOf(userId: UserId): List<ProductId>

    /** How many visible products [userId]'s list has: the header's badge. */
    fun countVisible(userId: UserId): Int

    /** Which of [productIds] [userId] has saved (visible or not): the heart on the cards. */
    fun idsAmong(userId: UserId, productIds: Collection<ProductId>): Set<ProductId>

    /** Takes [productId] out of every list: an archived product never comes back. */
    fun deleteProduct(productId: ProductId)
}
