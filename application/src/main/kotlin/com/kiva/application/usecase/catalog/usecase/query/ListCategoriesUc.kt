package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.catalog.CategoryMenu
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository

/**
 * Every category, in menu order (`GET /categories`): mega menu, mobile menu, shop chips, home page section.
 * Hidden ones are included with `showInMenu = false`; the client decides where to leave them out. Each comes with how
 * many products in the shop it holds and its photo (CategoryMenu, the same list as the home page's). Can't fail.
 */
class ListCategoriesUc(
    private val transactionManager: TransactionManager,
    categoryRepository: CategoryRepository,
    productQmRepository: ProductQmRepository,
    mediaRepository: MediaRepository,
) : QueryUseCase<ListCategoriesUc.Qry, List<CategoryDto>, Nothing> {

    private val logger = getLogger()
    private val menu = CategoryMenu(categoryRepository, productQmRepository, mediaRepository)

    /** Nothing to choose: it is always every category. */
    data object Qry

    override suspend fun execute(query: Qry): Result<List<CategoryDto>, Nothing> {
        logger.info("ListCategoriesUc.execute")
        return transactionManager.inTransaction { Result.success(menu.all()) }
    }
}
