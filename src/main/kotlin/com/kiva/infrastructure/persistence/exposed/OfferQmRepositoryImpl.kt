package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.OfferQm
import com.kiva.application.repository.OfferQmRepository
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Reads the offers of colors from the read model: one SELECT joining the color's row to its product, the product's
 * category and the palette. Runs inside the use case's transaction; it never opens one itself.
 */
class OfferQmRepositoryImpl : OfferQmRepository {

    override fun findOffers(variantIds: Collection<VariantId>): Map<VariantId, OfferQm> {
        if (variantIds.isEmpty()) return emptyMap()
        return ProductVariantQmTbl
            .join(ProductQmTbl, JoinType.INNER, ProductVariantQmTbl.productId, ProductQmTbl.productId)
            .join(CategoryTbl, JoinType.INNER, ProductQmTbl.categorySlug, CategoryTbl.slug)
            .join(ColorTbl, JoinType.INNER, ProductVariantQmTbl.colorKey, ColorTbl.colorKey)
            .selectAll()
            .where { ProductVariantQmTbl.variantId inList variantIds.map { it.value }.distinct() }
            .map { it.toOffer() }
            .associateBy { it.offer.variantId }
    }

    private fun ResultRow.toOffer(): OfferQm = OfferQm(
        offer = PurchaseOffer(
            variantId = VariantId.constructFromPersisted(this[ProductVariantQmTbl.variantId]),
            productId = ProductId.constructFromPersisted(this[ProductQmTbl.productId]),
            categoryId = CategoryId.constructFromPersisted(this[CategoryTbl.id].value),
            priceTag = PriceTag.constructFromPersisted(
                Money.constructFromPersisted(this[ProductVariantQmTbl.price]),
                this[ProductVariantQmTbl.compareAtPrice]?.let { Money.constructFromPersisted(it) },
            ),
            // On hand minus what orders hold; never negative (the read model's CHECK).
            available = this[ProductVariantQmTbl.onHand] - this[ProductVariantQmTbl.reserved],
            maxPerOrder = MaxPerOrder.constructFromPersisted(this[ProductQmTbl.maxPerOrder]),
            inShop = this[ProductQmTbl.status] == ProductStatus.ACTIVE && this[ProductVariantQmTbl.isActive],
            reservable = this[ProductQmTbl.reservable],
            returnable = this[ProductQmTbl.returnable],
        ),
        product = OfferQm.Product(this[ProductQmTbl.slug], this[ProductQmTbl.sku], this[ProductQmTbl.name]),
        category = OfferQm.Category(this[CategoryTbl.id].value, this[CategoryTbl.slug], this[CategoryTbl.name]),
        color = OfferQm.Color(
            this[ColorTbl.colorKey], this[ColorTbl.name], this[ColorTbl.hex], this[ColorTbl.isLight], this[ColorTbl.sortOrder], this[ProductVariantQmTbl.sku],
        ),
    )
}
