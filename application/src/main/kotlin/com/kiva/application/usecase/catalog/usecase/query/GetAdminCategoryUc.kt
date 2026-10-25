package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository

/**
 * One category as the panel lists it (every status counted): the body of `POST` and `PUT /admin/categories*`, read
 * right after the command.
 */
class GetAdminCategoryUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val mediaRepository: MediaRepository,
) : QueryUseCase<GetAdminCategoryUc.Qry, CategoryDto, GetAdminCategoryUc.Failure> {

    private val logger = getLogger()

    data class Qry(val categoryId: CategoryId)

    override suspend fun execute(query: Qry): Result<CategoryDto, Failure> {
        logger.info("GetAdminCategoryUc.execute category=${query.categoryId.value}")
        return transactionManager.inTransaction {
            val category = categoryRepository.findById(query.categoryId) ?: return@inTransaction Result.failure(Failure.NotFound)
            val productCount = productQmRepository.countAllByCategory()[category.slug] ?: 0
            Result.success(CategoryDto.from(category, productCount, category.imageMediaId?.let(mediaRepository::find)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
