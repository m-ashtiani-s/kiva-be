package com.kiva.application.usecase.product.usecase.query

/**
 * The orders the panel's product list offers (contract `sort` of `GET /admin/products`). What each means in the
 * database is the repository's job; ties always go to the larger id first. Unlike the shop, sold-out products are not
 * put last: the panel looks at them too.
 */
object AdminProductSorts {
    const val UPDATED = "updated"
    const val NEWEST = "newest"
    const val NAME = "name"
    const val PRICE_ASC = "price_asc"
    const val PRICE_DESC = "price_desc"
    const val BESTSELLING = "bestselling"
    const val STOCK_ASC = "stock_asc"

    const val DEFAULT = UPDATED

    val names: Set<String> = setOf(UPDATED, NEWEST, NAME, PRICE_ASC, PRICE_DESC, BESTSELLING, STOCK_ASC)
}
