package com.kiva.application.usecase.catalog.usecase.query

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository

/**
 * One category by its slug, with how many products in the shop it holds, for the category page
 * (`GET /categories/{slug}`).
 */
class GetCategoryUc(
    private val transactionManager: TransactionManager,
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val mediaRepository: MediaRepository,
) : QueryUseCase<GetCategoryUc.Qry, CategoryDto, GetCategoryUc.Failure> {

    private val logger = getLogger()

    data class Qry(val slug: String)

    override suspend fun execute(query: Qry): Result<CategoryDto, Failure> {
        logger.info("GetCategoryUc.execute slug=${query.slug}")

        // A malformed slug can't name any category, so it is simply "not found" (the contract has no 400 here).
        // Checked before the transaction, so bad input never takes a database connection.
        val slug = Slug.makeNew(query.slug).unwrapOr { return Result.failure(Failure.CategoryNotFound) }

        return transactionManager.inTransaction {
            val category = categoryRepository.findBySlug(slug)
                ?: return@inTransaction Result.failure(Failure.CategoryNotFound)
            val productCount = productQmRepository.countActiveByCategory()[category.slug] ?: 0
            Result.success(CategoryDto.from(category, productCount, category.imageMediaId?.let(mediaRepository::find)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        // The contract has no category-specific code; the general NOT_FOUND is what the client expects.
        data object CategoryNotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
