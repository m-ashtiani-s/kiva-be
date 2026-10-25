package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.SearchSuggestDto
import com.kiva.application.usecase.search.usecase.query.GetSearchHintsUc
import com.kiva.common.text.MatchRanges
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Contract schema `SearchHints`. */
@Serializable
data class SearchHintsHttpResponse(val categories: List<CategoryRefHttpResponse>, val colors: List<ColorHttpResponse>, val popularQueries: List<String>) {
    companion object {
        fun from(hints: GetSearchHintsUc.Hints) = SearchHintsHttpResponse(
            categories = hints.categories.map { CategoryRefHttpResponse(it.id.value, it.slug.value, it.name.value) },
            colors = hints.colors.map { ColorHttpResponse(it.key.value, it.name.value, it.hex.value, it.isLight, it.sortOrder) },
            popularQueries = hints.popularQueries,
        )
    }
}

/** Contract schema `SearchSuggestResponse`: every field always there. */
@Serializable
data class SearchSuggestHttpResponse(
    val query: String,
    val normalizedQuery: String,
    val products: List<SearchSuggestProductHttpResponse>,
    val categories: List<CategoryRefHttpResponse>,
    val colors: List<ColorHttpResponse>,
    val totalProducts: Int,
    val seeAllUrl: String,
) {
    override fun toString(): String = "SearchSuggestHttpResponse(products=${products.size}, total=$totalProducts)"

    companion object {
        fun from(dto: SearchSuggestDto, urls: MediaUrls) = SearchSuggestHttpResponse(
            query = dto.query,
            normalizedQuery = dto.normalizedQuery,
            products = dto.products.map { SearchSuggestProductHttpResponse.from(it, urls) },
            categories = dto.categories.map { CategoryRefHttpResponse(it.id.value, it.slug.value, it.name.value) },
            colors = dto.colors.map { ColorHttpResponse(it.key.value, it.name.value, it.hex.value, it.isLight, it.sortOrder) },
            totalProducts = dto.totalProducts,
            seeAllUrl = "/products?q=" + URLEncoder.encode(dto.normalizedQuery, StandardCharsets.UTF_8).replace("+", "%20"),
        )
    }
}

/**
 * Contract schema `SearchSuggestProduct`: the card's name with `<mark>` where the words are (and nothing else a tag), its
 * small picture and link in the matched colour when it has one.
 */
@Serializable
data class SearchSuggestProductHttpResponse(
    val id: Long,
    val slug: String,
    val name: String,
    val nameHighlighted: String,
    val categoryName: String,
    val price: PriceInfoHttpResponse,
    val matchedColorKey: String?,
    val imageUrl: String?,
    val url: String,
) {
    companion object {
        fun from(product: SearchSuggestDto.Product, urls: MediaUrls): SearchSuggestProductHttpResponse {
            val card = product.card
            val summary = ProductSummaryHttpResponse.from(card, filterColors = listOfNotNull(product.matchedColorKey), urls)
            return SearchSuggestProductHttpResponse(
                id = card.id,
                slug = card.slug,
                name = card.name,
                nameHighlighted = MatchRanges.mark(card.name, product.nameRanges),
                categoryName = card.category.name,
                price = summary.price,
                matchedColorKey = product.matchedColorKey,
                imageUrl = summary.image?.thumbnailUrl,
                url = "/product/${card.slug}" + (product.matchedColorKey?.let { "?color=$it" } ?: ""),
            )
        }
    }
}
