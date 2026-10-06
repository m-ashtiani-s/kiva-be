package com.kiva.application.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import java.time.Instant

/**
 * The product read model: what use cases read about products, instead of replaying their events. Only the projectors
 * write it, in the same transaction as the events, so inside a use case's transaction it shows every saved change.
 *
 * A port of the application layer, not of the domain: the read model serves queries and the use cases' checks; the
 * domain never reads it. Products in the shop are the ACTIVE ones; drafts and archived products are never counted.
 */
interface ProductQmRepository {

    /**
     * One page of the products in the shop that pass [filter], in the order of [pageable]'s sort (one of
     * ProductSorts), or in the order of [ProductFilter.ids] when those are given.
     */
    fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm>

    /** One product's page, if it is in the shop: a draft or an archived product is null, like one nobody has. */
    fun findDetail(ref: ProductRef): ProductDetailQm?

    /**
     * The counts behind the list's filters for [filter], each filter's own value left out of its own count (see
     * ProductFacetsQm). [now] decides which products are «جدید».
     */
    fun facets(filter: ProductFilter, now: Instant): ProductFacetsQm

    /**
     * Up to [limit] other products in the shop to show beside [productId]: those of its category first, then the best
     * selling, those that can be bought before those that can't. Null when [productId] itself is not in the shop.
     */
    fun findRelated(productId: ProductId, limit: Int): List<ProductCardQm>?

    /** One color of any product, whatever its status, or null when no product has it. */
    fun findVariant(variantId: VariantId): VariantQm?

    /** Whether a product, whatever its status, already has this slug. */
    fun isSlugTaken(slug: Slug): Boolean

    /** Those of [skus] that a product or a color of one, whatever its status, already has. */
    fun findTakenSkus(skus: Collection<Sku>): Set<Sku>

    /** How many products in the shop are in each category, by slug. A category with none is missing. */
    fun countActiveByCategory(): Map<Slug, Int>

    /** How many products in the shop are made of each material, by slug. A material with none is missing. */
    fun countActiveByMaterial(): Map<Slug, Int>

    /** How many products in the shop offer each color among their active colors. A color with none is missing. */
    fun countActiveByColor(): Map<ColorKey, Int>
}
