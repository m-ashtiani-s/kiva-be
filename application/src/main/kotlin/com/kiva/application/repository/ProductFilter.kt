package com.kiva.application.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * What the shop's list is narrowed to (`GET /products`), every part already checked. An empty list, null or false
 * means "not narrowed by this". The values of one filter are alternatives (shoulder or cross bags); different filters
 * all apply (shoulder bags that come in lilac). Whatever the filter, only products in the shop (ACTIVE) are listed.
 *
 * @property words the search text in words, through normalizePersian and lowercased; a product has every one of them
 *   in its name, category, active colors or material.
 * @property colors a product with at least one of them among its active colors.
 * @property price on the card's price, the default color's.
 * @property onSale only products whose card shows a discount.
 * @property inStock only products with at least one active color left to sell.
 * @property newAt only products still «جدید» at this moment.
 * @property ids only these products, in this order (recently viewed, compare); the requested sort is not used.
 */
data class ProductFilter(
    val words: List<String> = emptyList(),
    val categories: Set<Slug> = emptySet(),
    val colors: Set<ColorKey> = emptySet(),
    val price: PriceRange = PriceRange.ANY,
    val onSale: Boolean = false,
    val inStock: Boolean = false,
    val newAt: Instant? = null,
    val hasVideo: Boolean = false,
    val bagTypes: Set<BagType> = emptySet(),
    val materials: Set<Slug> = emptySet(),
    val occasions: Set<Occasion> = emptySet(),
    val ids: List<ProductId> = emptyList(),
)
