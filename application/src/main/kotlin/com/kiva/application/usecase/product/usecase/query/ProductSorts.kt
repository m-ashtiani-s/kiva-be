package com.kiva.application.usecase.product.usecase.query

/**
 * The orders the shop's list offers (contract `sort`). What each one means in the database is the repository's job;
 * which ones put sold-out products last is the shop's rule, so it is here.
 */
object ProductSorts {
    const val NEWEST = "newest"
    const val BESTSELLING = "bestselling"
    const val PRICE_ASC = "price_asc"
    const val PRICE_DESC = "price_desc"
    const val DISCOUNT_DESC = "discount_desc"
    const val RATING_DESC = "rating_desc"

    const val DEFAULT = NEWEST

    val names: Set<String> = setOf(NEWEST, BESTSELLING, PRICE_ASC, PRICE_DESC, DISCOUNT_DESC, RATING_DESC)

    /**
     * Products that can't be bought go to the end of the list, except when sorting by price: there the shopper asked
     * for prices in order, and a cheap sold-out bag belongs where its price puts it.
     */
    fun putsSoldOutLast(sort: String): Boolean = sort != PRICE_ASC && sort != PRICE_DESC
}
