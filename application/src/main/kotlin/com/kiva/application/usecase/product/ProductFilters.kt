package com.kiva.application.usecase.product

import com.kiva.application.repository.PriceRange
import com.kiva.application.repository.ProductFilter
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.bind
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.zip
import com.kiva.common.railway.zipAll
import com.kiva.common.text.normalizeDigits
import com.kiva.common.text.normalizePersian
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.policy.SearchQueryPolicy
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.domain.aggregate.product.model.valueobject.Occasion
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import java.time.Instant

/**
 * The shop's list filters as they arrived (the contract's query parameters, list parameters already split at their
 * commas). The list (`GET /products`) and its facets (`GET /products/facets`) take the same ones.
 *
 * @property minPrice / maxPrice as typed, Persian digits included.
 * @property ids as typed: product ids, at most [ProductFilters.MAX_IDS].
 */
data class ProductFilterQuery(
    val q: String? = null,
    val categories: List<String> = emptyList(),
    val colors: List<String> = emptyList(),
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val onSale: Boolean = false,
    val inStock: Boolean = false,
    val isNew: Boolean = false,
    val hasVideo: Boolean = false,
    val bagTypes: List<String> = emptyList(),
    val materials: List<String> = emptyList(),
    val occasions: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
    val campaign: String? = null,
)

/**
 * Turns [ProductFilterQuery] into a checked [ProductFilter], every mistake at once, each under its parameter
 * (`maxPrice`, `color`, `ids`). A well-formed value that names nothing (a category nobody has) is not a mistake: it
 * simply matches no product. An empty parameter (`minPrice=`) is the same as a missing one.
 */
object ProductFilters {

    /** From the contract's `q`. */
    const val MAX_QUERY_LENGTH = 100

    /** From the contract's `ids`. */
    const val MAX_IDS = 60

    /** @param now the request's moment, for `isNew`. */
    fun parse(query: ProductFilterQuery, now: Instant): Result<ProductFilter, Invalid> = Result.zip(
        Result.zip(wordsOf(query.q), idsOf(query.ids), campaignOf(query.campaign), ::InputProblems) { words, ids, campaign -> Triple(words, ids, campaign) },
        Result.zip(
            each(query.categories, "category") { Slug.makeNew(it) },
            each(query.colors, "color") { ColorKey.makeNew(it) },
            each(query.materials, "material") { Slug.makeNew(it) },
            ::InputProblems,
        ) { categories, colors, materials -> Triple(categories, colors, materials) },
        Result.zip(
            each(query.bagTypes, "bagType") { BagType.makeNew(it) },
            each(query.occasions, "occasion") { Occasion.makeNew(it) },
            ::InputProblems,
        ) { bagTypes, occasions -> bagTypes to occasions },
        priceRangeOf(query.minPrice, query.maxPrice),
        { problems -> Invalid(problems.flattenGroups()) },
    ) { (words, ids, campaign), (categories, colors, materials), (bagTypes, occasions), price ->
        ProductFilter(
            words = words,
            categories = categories.toSet(),
            colors = colors.toSet(),
            price = price,
            onSale = query.onSale,
            inStock = query.inStock,
            newAt = now.takeIf { query.isNew },
            hasVideo = query.hasVideo,
            bagTypes = bagTypes.toSet(),
            materials = materials.toSet(),
            occasions = occasions.toSet(),
            ids = ids,
            campaign = campaign,
        )
    }

    /** `?campaign=` (decision 5 of phase 11): a slug, or SLUG_INVALID; empty is none. */
    private fun campaignOf(raw: String?): Result<Slug?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return Slug.makeNew(text).forField("campaign")
    }

    /**
     * The search text in words, read as the live search reads it (SearchQueryPolicy): «كيف» typed on an Arabic keyboard
     * finds «کیف», a half-space splits words, at most 6 of them, and each must be in the product (phase 12, decision 2).
     */
    private fun wordsOf(q: String?): Result<List<String>, ResultFailure> {
        val text = SearchQueryPolicy.normalize(q.orEmpty())
        if (text.length > MAX_QUERY_LENGTH) return Result.failure(Problem.SearchTooLong)
        return Result.success(SearchQueryPolicy.wordsOf(text))
    }

    private fun idsOf(raws: List<String>): Result<List<ProductId>, ResultFailure> {
        if (raws.size > MAX_IDS) return Result.failure(Problem.TooManyIds)
        return each(raws, "ids") { raw -> numberOf(raw)?.let { ProductId.makeNew(it) } ?: Result.failure(Problem.NotANumber) }
    }

    /** Both ends first, then the rule between them. */
    private fun priceRangeOf(minPrice: String?, maxPrice: String?): Result<PriceRange, ResultFailure> {
        val ends: Result<Pair<Money?, Money?>, ResultFailure> =
            Result.zip(moneyOf(minPrice, "minPrice"), moneyOf(maxPrice, "maxPrice"), ::InputProblems) { min, max -> min to max }
        return ends.bind { (min, max) -> PriceRange.makeNew(min, max) }
    }

    private fun moneyOf(raw: String?, parameter: String): Result<Money?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        val amount = numberOf(text) ?: return Result.failure(Problem.NotANumber).forField(parameter)
        return Money.makeNew(amount).forField(parameter)
    }

    /** Every value of a list parameter, each problem under the parameter's name. */
    private fun <T> each(raws: List<String>, parameter: String, parse: (String) -> Result<T, ResultFailure>): Result<List<T>, ResultFailure> =
        Result.zipAll(raws.map { parse(it).forField(parameter) }, ::InputProblems)

    private fun numberOf(raw: String): Long? = raw.normalizeDigits().trim().toLongOrNull()

    /** One or more parameters are wrong; each problem names its parameter. Each use case passes these on as its own failure. */
    data class Invalid(val problems: List<ResultFailure>) : ResultFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

    sealed class Problem(code: String, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object SearchTooLong : Problem("SEARCH_QUERY_TOO_LONG", "q", mapOf("maxLength" to MAX_QUERY_LENGTH))

        data object TooManyIds : Problem("IDS_TOO_MANY", "ids", mapOf("maxItems" to MAX_IDS))

        /** Not a whole number. No field here: the caller names the parameter. */
        data object NotANumber : Problem("NUMBER_INVALID")
    }
}
