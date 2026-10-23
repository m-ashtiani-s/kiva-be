package com.kiva.application.dto

import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.color.entity.Color

/**
 * The live search's answer (contract SearchSuggestResponse, before its HTML): the query as it came and normalized, the
 * first products with where their names match, the categories and colours for quick links, and how many products match.
 */
data class SearchSuggestDto(
    val query: String,
    val normalizedQuery: String,
    val products: List<Product>,
    val categories: List<Category>,
    val colors: List<Color>,
    val totalProducts: Int,
) {
    /**
     * @property matchedColorKey the colour the query names, when this product has it active; its picture and link use it.
     * @property nameRanges where the query's words are in the card's name, merged (MatchRanges).
     */
    data class Product(val card: ProductCardDto, val matchedColorKey: String?, val nameRanges: List<IntRange>)
}
