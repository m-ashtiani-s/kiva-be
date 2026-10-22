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
 * domain never reads it. Products in the shop are the ACTIVE ones; drafts and archived products are never counted,
 * except by the panel's own reads (findForAdmin, findAdminDetail, countAll*).
 *
 * The one write a use case may ask for is [refreshSearchText]: categories, materials and colors are not event-sourced,
 * so no event tells the projectors that one of their names changed.
 */
interface ProductQmRepository {

    /**
     * One page of the products in the shop that pass [filter], in the order of [pageable]'s sort (one of
     * ProductSorts), or in the order of [ProductFilter.ids] when those are given.
     */
    fun find(filter: ProductFilter, pageable: Pageable): Page<ProductCardQm>

    /**
     * The cards of those of [ids] that are in the shop now, in the order of [ids] (a draft or an archived one, or one
     * nobody has, is left out): any number, unlike [ProductFilter.ids]. A wishlist's products.
     */
    fun findCards(ids: List<ProductId>): List<ProductCardQm>

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

    /**
     * One page of the panel's product list: products of every status that pass [filter], in the order of [pageable]'s
     * sort (one of AdminProductSorts, ties broken by the larger id first). Rows, their count and the first media of
     * each one's default color: a fixed number of queries, never one per product.
     */
    fun findForAdmin(filter: AdminProductFilter, pageable: Pageable): Page<AdminProductQm>

    /** One product for the panel's page, whatever its status, or null when nobody has it. */
    fun findAdminDetail(productId: ProductId): AdminProductDetailQm?

    /** One color of any product, whatever its status, or null when no product has it. */
    fun findVariant(variantId: VariantId): VariantQm?

    /** Several colors at once (any status): «اشتراک‌های من» and the panel's queue. */
    fun findVariants(variantIds: Collection<VariantId>): Map<VariantId, VariantQm>

    /** Drafts whose scheduled publication is due ([now] or before), earliest first, at most [limit]. */
    fun findDueScheduled(now: Instant, limit: Int): List<ProductId>

    /** Whether a product, whatever its status, already has this slug. */
    fun isSlugTaken(slug: Slug): Boolean

    /** Those of [skus] that a product or a color of one, whatever its status, already has. */
    fun findTakenSkus(skus: Collection<Sku>): Set<Sku>

    /** The products of [ids] there are, in any status, each with its default color's first photo: a page of reviews' products in one read. */
    fun findBriefs(ids: Collection<ProductId>): Map<ProductId, ProductBriefQm>

    /** Those of [ids] that are a product, whatever its status (a discount code may name a draft). */
    fun findExistingIds(ids: Collection<ProductId>): Set<ProductId>

    /** How many products in the shop are in each category, by slug. A category with none is missing. */
    fun countActiveByCategory(): Map<Slug, Int>

    /** How many products in the shop are made of each material, by slug. A material with none is missing. */
    fun countActiveByMaterial(): Map<Slug, Int>

    /** How many products in the shop offer each color among their active colors. A color with none is missing. */
    fun countActiveByColor(): Map<ColorKey, Int>

    /**
     * How many products, whatever their status (drafts and archived ones too), are in each category, by slug: the panel's
     * count, and what keeps a category from being removed. A category with none is missing.
     */
    fun countAllByCategory(): Map<Slug, Int>

    /** How many products, whatever their status, are made of each material, by slug. A material with none is missing. */
    fun countAllByMaterial(): Map<Slug, Int>

    /** How many products, whatever their status, have [key] among their colors, active or not. */
    fun countAllWithColor(key: ColorKey): Int

    /**
     * Writes the search text again for every product, whatever its status, that shows this category, material or
     * color, so a search for its new name finds them. The use case that renames one calls it in the same transaction,
     * after the rename. It goes through the projectors' own code, so the rules of the search text stay in one place.
     *
     * @return how many products it wrote.
     */
    fun refreshSearchText(of: NamedInSearch): Int

    /** What a product's search text copies the name of, besides the product's own. */
    sealed interface NamedInSearch {
        data class Category(val slug: Slug) : NamedInSearch
        data class Material(val slug: Slug) : NamedInSearch
        data class Color(val key: ColorKey) : NamedInSearch
    }
}
