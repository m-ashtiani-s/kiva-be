package com.kiva.application.dto

import com.kiva.common.pagination.Page
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import java.time.Instant

/**
 * One saved product as «علاقه‌مندی‌ها» shows it (contract WishlistItem): its card (`isWishlisted = true`), when it was
 * saved, the color then and its price then, and how much cheaper it is now, if at least 1% (decision 8).
 */
data class WishlistItemDto(
    val product: ProductCardDto,
    val addedAt: Instant,
    val preferredColorKey: String?,
    val priceAtAdd: Long,
    val priceDrop: WishlistPolicy.PriceDrop?,
)

/**
 * A customer's list (contract Wishlist): a page of the visible products, and over all of them [count] and
 * [inStockCount] (those with a color that can be bought: what «همه به سبد» takes). [share] is the live link, null
 * without one or with the flag off.
 */
data class WishlistDto(val page: Page<WishlistItemDto>, val count: Int, val inStockCount: Int, val share: WishlistShareDto?)

/** The public link of a list (contract WishlistShare): [url] is made by the HTTP layer from the shop's address. */
data class WishlistShareDto(val token: String, val createdAt: Instant)

/** The answer of saving or removing one product (contract WishlistToggleResponse, without its Persian message). */
data class WishlistToggleDto(val productId: Long, val wishlisted: Boolean, val count: Int)
