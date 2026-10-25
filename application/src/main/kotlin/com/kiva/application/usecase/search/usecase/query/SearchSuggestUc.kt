package com.kiva.application.usecase.search.usecase.query

import com.kiva.application.dto.SearchSuggestDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.ProductCards
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.MatchRanges
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.product.model.policy.SearchQueryPolicy
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * The header's live search (`GET /search/suggest`; a login optional and of no effect; roadmap section 17.6): the query
 * normalized before its length is checked (400 SEARCH_QUERY_EMPTY / SEARCH_QUERY_TOO_LONG on `q`, LIMIT_INVALID on
 * `limit`), its first 6 words, then in one transaction the products (SearchQueryPolicy's rule, ranked by the read
 * model), the colour the query names, and the menu categories and colours whose names have a word. Each product's name
 * ranges are worked out here; the HTML is the HTTP layer's (MatchRanges.mark).
 */
class SearchSuggestUc(
    private val transactionManager: TransactionManager,
    private val productQmRepository: ProductQmRepository,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<SearchSuggestUc.Qry, SearchSuggestDto, SearchSuggestUc.Failure> {

    private val logger = getLogger()

    /** `q` and `limit` as they came. */
    data class Qry(val q: String?, val limit: String? = null)

    override suspend fun execute(query: Qry): Result<SearchSuggestDto, Failure> {
        logger.info("SearchSuggestUc.execute limit=${query.limit}")
        val problems = mutableListOf<ResultFailure>()
        val normalized = SearchQueryPolicy.normalize(query.q.orEmpty())
        when {
            normalized.isEmpty() -> problems += FieldFailure("q", Problem.QueryEmpty)
            normalized.length > SearchQueryPolicy.MAX_LENGTH -> problems += FieldFailure("q", Problem.QueryTooLong)
        }
        val limit = limitOf(query.limit) ?: null.also { problems += FieldFailure("limit", Problem.LimitInvalid) }
        if (problems.isNotEmpty() || limit == null) return Result.failure(Failure.InvalidInput(problems))
        val words = SearchQueryPolicy.wordsOf(normalized)
        val now = clock.instant()

        return transactionManager.inTransaction {
            val page = productQmRepository.suggest(words, limit)
            val categories = categoryRepository.findAll()
            val bySlug = categories.associateBy { it.slug }
            val palette = colorRepository.findAll()
            val byKey = palette.associateBy { it.key }
            val settings = shopSettingsRepository.get()
            val matchedColor = SearchQueryPolicy.matchedColorOf(normalized, palette.associate { it.key to it.name.value })
            val folded = words.map(MatchRanges::fold)
            val products = page.products.map { qm ->
                val card = ProductCards.cardOf(qm, bySlug.getValue(qm.categorySlug), byKey, now, settings.freeShippingThreshold, settings.lowStockThreshold)
                SearchSuggestDto.Product(
                    card = card,
                    matchedColorKey = matchedColor?.takeIf { key -> qm.colors.any { it.colorKey == key } }?.value,
                    nameRanges = MatchRanges.of(card.name, folded),
                )
            }
            // A category or colour for a quick link: one word (of two letters or more) in its name is enough (decisions 7, 8).
            val linkWords = words.filter { it.length >= 2 }
            fun named(name: String) = SearchQueryPolicy.normalize(name).lowercase().let { text -> linkWords.any { text.contains(it) } }
            val withProducts = productQmRepository.countActiveByCategory()
            val usedColors = productQmRepository.countActiveByColor()
            Result.success(
                SearchSuggestDto(
                    query = query.q.orEmpty(),
                    normalizedQuery = normalized,
                    products = products,
                    categories = categories.filter { it.showInMenu && (withProducts[it.slug] ?: 0) > 0 && named(it.name.value) }.take(MAX_LINKS),
                    colors = palette.filter { (usedColors[it.key] ?: 0) > 0 && named(it.name.value) }.take(MAX_LINKS),
                    totalProducts = page.total,
                ),
            )
        }
    }

    /** Missing or empty is the contract's default; a number outside 1..[MAX_LIMIT] or not a number is null. */
    private fun limitOf(raw: String?): Int? {
        val text = raw?.trim()?.ifEmpty { null } ?: return DEFAULT_LIMIT
        return text.normalizeDigits().toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
    }

    companion object {
        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 20
        const val MAX_LINKS = 4
    }

    sealed class Problem(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object QueryEmpty : Problem("SEARCH_QUERY_EMPTY")
        data object QueryTooLong : Problem("SEARCH_QUERY_TOO_LONG", mapOf("maxLength" to SearchQueryPolicy.MAX_LENGTH))
        data object LimitInvalid : Problem("LIMIT_INVALID", mapOf("max" to MAX_LIMIT))
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
