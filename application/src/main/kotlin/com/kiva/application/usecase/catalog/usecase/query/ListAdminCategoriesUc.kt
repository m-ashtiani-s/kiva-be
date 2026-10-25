package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository

/**
 * Every category for the panel (`GET /admin/categories`; ADMIN, CATALOG_MANAGER), in menu order, each with its photo's
 * media id and how many products of **any** status it holds (drafts and archived ones too), so the panel shows why one
 * can't be removed. The shop's GET /categories counts only the products in the shop. Can't fail.
 */
class ListAdminCategoriesUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val mediaRepository: MediaRepository,
) : QueryUseCase<ListAdminCategoriesUc.Qry, List<CategoryDto>, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<List<CategoryDto>, Nothing> {
        logger.info("ListAdminCategoriesUc.execute")
        return transactionManager.inTransaction {
            val productCounts = productQmRepository.countAllByCategory()
            val categories = categoryRepository.findAll()
            val images = mediaRepository.findAll(categories.mapNotNull { it.imageMediaId }).associateBy { it.id }
            Result.success(categories.map { CategoryDto.from(it, productCounts[it.slug] ?: 0, it.imageMediaId?.let(images::get)) })
        }
    }
}
