package com.kiva.application.usecase.wishlist

import com.kiva.application.dto.ProductCardDto
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository

/**
 * The heart on the cards of uncached answers (roadmap section 14.2, decision 15): for a customer, true on the products
 * they saved and false on the others, read in one statement for the whole page; for a guest it stays null. The lists,
 * the product's page, the related products and the cart use it; a cached answer never does. Inside the caller's
 * transaction.
 */
fun interface WishlistHearts {

    /** Which of [productIds] [userId] saved. */
    fun savedAmong(userId: UserId, productIds: Collection<ProductId>): Set<ProductId>

    /** [cards] with their hearts for [viewer]; a guest's (null) are left as they are. */
    fun mark(cards: List<ProductCardDto>, viewer: UserId?): List<ProductCardDto> {
        if (viewer == null || cards.isEmpty()) return cards
        val saved = savedAmong(viewer, cards.map { ProductId.constructFromPersisted(it.id) }).map { it.value }.toSet()
        return cards.map { it.copy(isWishlisted = it.id in saved) }
    }

    companion object {
        /** For what reads no wishlist: nobody saved anything. */
        val NONE = WishlistHearts { _, _ -> emptySet() }

        fun of(wishlistRepository: WishlistRepository) = WishlistHearts(wishlistRepository::idsAmong)
    }
}
