package com.kiva.application.usecase.search.usecase.query

import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.repository.SiteContentRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.repository.ColorRepository

/**
 * What the search panel shows before a letter is typed (`GET /search/hints`, public, cached for 5 minutes; roadmap
 * section 17.2, decision 13): the menu's categories that have a product in the shop (8 at most, menu order), the
 * colours some product in the shop comes in (12 at most, palette order), and the suggested queries typed by hand in the
 * site's content (8 at most). No search is ever logged.
 */
class GetSearchHintsUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val colorRepository: ColorRepository,
    private val productQmRepository: ProductQmRepository,
    private val siteContentRepository: SiteContentRepository,
) : QueryUseCase<GetSearchHintsUc.Qry, GetSearchHintsUc.Hints, Nothing> {

    private val logger = getLogger()

    data object Qry

    data class Hints(val categories: List<Category>, val colors: List<Color>, val popularQueries: List<String>)

    override suspend fun execute(query: Qry): Result<Hints, Nothing> {
        logger.info("GetSearchHintsUc.execute")
        return transactionManager.inTransaction {
            val withProducts = productQmRepository.countActiveByCategory()
            val usedColors = productQmRepository.countActiveByColor()
            Result.success(
                Hints(
                    categories = categoryRepository.findAll().filter { it.showInMenu && (withProducts[it.slug] ?: 0) > 0 }.take(MAX_CATEGORIES),
                    colors = colorRepository.findAll().filter { (usedColors[it.key] ?: 0) > 0 }.take(MAX_COLORS),
                    popularQueries = siteContentRepository.load().popularQueries.take(MAX_QUERIES),
                ),
            )
        }
    }

    companion object {
        const val MAX_CATEGORIES = 8
        const val MAX_COLORS = 12
        const val MAX_QUERIES = 8
    }
}
