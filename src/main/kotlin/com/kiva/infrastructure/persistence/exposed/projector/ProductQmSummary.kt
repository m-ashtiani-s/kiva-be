package com.kiva.infrastructure.persistence.exposed.projector

import com.kiva.common.text.normalizePersian
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.product.model.policy.PricePolicy
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MaterialTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/**
 * The columns of productqm_tbl worked out from several rows: the card's price tag, the active colors, video, stock
 * (inStock and availableTotal) and searchText. Both projectors change the rows these come from, then call [refresh], so the rules live in one place.
 *
 * Lock order, for every writer of the read model: [lock] the product's row first, then change its colors' rows, then
 * [refresh]. Two transactions on one product then take their locks in the same order, so they wait for each other
 * instead of deadlocking. And under READ COMMITTED each statement after the lock sees what the other one committed,
 * so [refresh] never works from colors that are being changed under it.
 *
 * Runs inside the caller's transaction; it never opens one itself.
 */
internal object ProductQmSummary {

    /** Locks the product's row until the transaction ends. A product missing from the read model is a bug. */
    fun lock(productId: Long) {
        ProductQmTbl.select(ProductQmTbl.id)
            .where { ProductQmTbl.productId eq productId }
            .forUpdate()
            .singleOrNull()
            ?: error("Product $productId is not in the read model")
    }

    /** The product's own price tag, which colors without their own follow. */
    fun basePriceTag(productId: Long): PriceTag {
        val row = ProductQmTbl.select(ProductQmTbl.basePrice, ProductQmTbl.baseCompareAtPrice)
            .where { ProductQmTbl.productId eq productId }
            .single()
        return priceTag(row[ProductQmTbl.basePrice], row[ProductQmTbl.baseCompareAtPrice])
    }

    /**
     * Works out the derived columns again from the product's row and its colors' rows, as they are now.
     * [updatedOn] is given for a change to the product itself, not for a change of its stock.
     */
    fun refresh(productId: Long, updatedOn: Instant? = null) {
        val product = ProductQmTbl.selectAll().where { ProductQmTbl.productId eq productId }.single()
        // Display order, as Product picks the next default: position, then id.
        val activeColors = ProductVariantQmTbl.selectAll()
            .where { (ProductVariantQmTbl.productId eq productId) and (ProductVariantQmTbl.isActive eq true) }
            .orderBy(ProductVariantQmTbl.sortOrder to SortOrder.ASC, ProductVariantQmTbl.variantId to SortOrder.ASC)
            .toList()

        // PricePolicy.cardPriceTag: the default color's, the product's own while it has no active color.
        val cardPriceTag = activeColors.firstOrNull { it[ProductVariantQmTbl.isDefault] }
            ?.let { priceTag(it[ProductVariantQmTbl.price], it[ProductVariantQmTbl.compareAtPrice]) }
            ?: priceTag(product[ProductQmTbl.basePrice], product[ProductQmTbl.baseCompareAtPrice])
        val colorKeys = activeColors.map { it[ProductVariantQmTbl.colorKey] }

        val updated = ProductQmTbl.update({ ProductQmTbl.productId eq productId }) {
            it[price] = cardPriceTag.price.toman
            it[compareAtPrice] = cardPriceTag.compareAtPrice?.toman
            it[discountPercent] = PricePolicy.discountPercent(cardPriceTag)
            it[ProductQmTbl.colorKeys] = colorKeys
            it[inStock] = activeColors.any { color ->
                color[ProductVariantQmTbl.onHand] - color[ProductVariantQmTbl.reserved] > 0
            }
            it[availableTotal] = activeColors.sumOf { color -> color[ProductVariantQmTbl.onHand] - color[ProductVariantQmTbl.reserved] }
            it[hasVideo] = activeColors.any { color ->
                color[ProductVariantQmTbl.media].any { media -> media.toDomain().type == MediaType.VIDEO }
            }
            it[searchText] = searchTextOf(product, colorKeys)
            if (updatedOn != null) it[ProductQmTbl.updatedOn] = updatedOn.atOffset(ZoneOffset.UTC)
        }
        check(updated == 1) { "Refreshing product $productId updated $updated rows" }
    }

    /**
     * Name, category, active colors and material, in Persian as the shopper types them, then the product's code (the
     * panel searches by it), all through normalizePersian. The search term goes through the same function, so «كيف» from
     * an Arabic keyboard finds «کیف». V24 appended the code to the rows written before it the same way.
     */
    private fun searchTextOf(product: ResultRow, colorKeys: List<String>): String {
        val category = CategoryTbl.select(CategoryTbl.name)
            .where { CategoryTbl.slug eq product[ProductQmTbl.categorySlug] }
            .single()[CategoryTbl.name]
        val material = MaterialTbl.select(MaterialTbl.name)
            .where { MaterialTbl.slug eq product[ProductQmTbl.materialSlug] }
            .single()[MaterialTbl.name]
        val colorNames = ColorTbl.select(ColorTbl.colorKey, ColorTbl.name)
            .where { ColorTbl.colorKey inList colorKeys }
            .associate { it[ColorTbl.colorKey] to it[ColorTbl.name] }

        val parts = listOf(product[ProductQmTbl.name], category) + colorKeys.map { colorNames.getValue(it) } + material + product[ProductQmTbl.sku]
        return parts.joinToString(" ").normalizePersian()
    }

    fun priceTag(price: Long, compareAtPrice: Long?): PriceTag =
        PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAtPrice?.let { Money.constructFromPersisted(it) })
}
