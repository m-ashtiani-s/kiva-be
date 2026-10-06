package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository

/**
 * Every category, in menu order (`GET /categories`): mega menu, mobile menu, shop chips, home page section.
 * Hidden ones are included with `showInMenu = false`; the client decides where to leave them out. Can't fail.
 */
class ListCategoriesUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
) : QueryUseCase<ListCategoriesUc.Qry, List<CategoryDto>, Nothing> {

    private val logger = getLogger()

    /** Nothing to choose: it is always every category. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<CategoryDto>, Nothing> {
        logger.info("ListCategoriesUc.execute")
        return transactionManager.inTransaction {
            // No products are stored yet; the real count comes from the product read model once it exists.
            Result.success(categoryRepository.findAll().map { CategoryDto.from(it, productCount = 0) })
        }
    }
}
