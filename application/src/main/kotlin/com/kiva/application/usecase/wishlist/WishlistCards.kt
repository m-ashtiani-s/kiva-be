package com.kiva.application.usecase.wishlist

import com.kiva.application.dto.ProductCardDto
import com.kiva.application.dto.WishlistItemDto
import com.kiva.application.repository.ProductCardQm
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductCards
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.wishlist.entity.Wishlist
import com.kiva.domain.aggregate.wishlist.entity.WishlistEntry
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import java.time.Instant

/**
 * The cards of a wishlist's visible products, made exactly as in the shop's list (ProductCards), for «علاقه‌مندی‌ها»
 * and the shared list alike (Rule 15). One read of the cards for the whole list, whatever its length: a draft's or an
 * archived product's row has no card and is left out (decision 6). Inside the caller's transaction.
 */
class WishlistCards(
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
) {

    /** One visible product of the list: its row, its read-model card, and the card the shop shows. */
    class Seen(val entry: WishlistEntry, val qm: ProductCardQm, val card: ProductCardDto) {
        /** It has a color that can be bought now: what «همه به سبد» can take. */
        val inStock: Boolean get() = qm.colors.any { it.available > 0 }

        /** What it costs now in the color it was saved in, if that one is still active, else the card's (decision 8). */
        val currentPrice: Money get() = entry.preferredColorKey?.let { key -> qm.colors.firstOrNull { it.colorKey == key }?.price } ?: qm.priceTag.price

        fun item(): WishlistItemDto = WishlistItemDto(
            product = card,
            addedAt = entry.addedOn,
            preferredColorKey = entry.preferredColorKey?.value,
            priceAtAdd = entry.priceAtAdd.toman,
            priceDrop = WishlistPolicy.priceDropOf(entry.priceAtAdd, currentPrice),
        )
    }

    /** The visible products of [wishlist], in its order, each card with [isWishlisted]. */
    fun of(wishlist: Wishlist, now: Instant, isWishlisted: Boolean?): List<Seen> {
        val entries = wishlist.entries
        if (entries.isEmpty()) return emptyList()
        val cards = productQmRepository.findCards(entries.map { it.productId }).associateBy { it.productId }
        if (cards.isEmpty()) return emptyList()
        val categories = categoryRepository.findAll().associateBy { it.slug }
        val palette = colorRepository.findAll().associateBy { it.key }
        val settings = shopSettingsRepository.get()
        return entries.mapNotNull { entry ->
            val qm = cards[entry.productId] ?: return@mapNotNull null
            val card = ProductCards.cardOf(qm, categories.getValue(qm.categorySlug), palette, now, settings.freeShippingThreshold, settings.lowStockThreshold)
            Seen(entry, qm, card.copy(isWishlisted = isWishlisted))
        }
    }

    /** The card of one product in the shop (with its colors' prices), or null: what saving it needs. */
    fun cardOf(productId: ProductId): ProductCardQm? = productQmRepository.findCards(listOf(productId)).singleOrNull()
}
