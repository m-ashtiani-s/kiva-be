package com.kiva.application.usecase.review.usecase.query

/**
 * The orders of a product's public reviews (contract `sort` of `GET /products/{productId}/reviews`; roadmap section
 * 13.2, decision 18): newest written first; the most stars first, then the newest; the fewest first, then the newest;
 * the most found helpful first, then the newest. The review's id, newest first, breaks every tie.
 */
object ReviewSorts {
    const val NEWEST = "newest"
    const val HIGHEST = "highest"
    const val LOWEST = "lowest"
    const val HELPFUL = "helpful"

    const val DEFAULT = NEWEST

    val names: Set<String> = setOf(NEWEST, HIGHEST, LOWEST, HELPFUL)
}
