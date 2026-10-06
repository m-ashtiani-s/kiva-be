package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository

/**
 * Every category, in menu order (`GET /categories`): mega menu, mobile menu, shop chips, home page section.
 * Hidden ones are included with `showInMenu = false`; the client decides where to leave them out. Each comes with how
 * many products in the shop it holds. Can't fail.
 */
class ListCategoriesUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
) : QueryUseCase<ListCategoriesUc.Qry, List<CategoryDto>, Nothing> {

    private val logger = getLogger()

    /** Nothing to choose: it is always every category. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<CategoryDto>, Nothing> {
        logger.info("ListCategoriesUc.execute")
        return transactionManager.inTransaction {
            val productCounts = productQmRepository.countActiveByCategory()
            Result.success(categoryRepository.findAll().map { CategoryDto.from(it, productCount = productCounts[it.slug] ?: 0) })
        }
    }
}
