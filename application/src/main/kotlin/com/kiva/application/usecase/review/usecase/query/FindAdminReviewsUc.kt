package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.AdminReviewDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.usecase.review.Reviews
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.review.repository.ReviewRepository

/**
 * The panel's review queue (`GET /admin/reviews`; ADMIN, SUPPORT; roadmap section 13.2, decision 25): every review, or
 * one status, one product, with or without Kiva's reply; newest written first. Each row the whole review (its reason
 * and its reply too), its author's account, and how to reach them (the contact they gave, or the account's phone now).
 * `pendingCount` is every PENDING review, whatever the filters.
 */
class FindAdminReviewsUc(
    private val transactionManager: TransactionManager,
    private val reviewRepository: ReviewRepository,
    private val reviews: Reviews,
    private val adminCustomerRepository: AdminCustomerRepository,
) : QueryUseCase<FindAdminReviewsUc.Qry, FindAdminReviewsUc.Queue, FindAdminReviewsUc.Failure> {

    private val logger = getLogger()

    /** The query parameters as they came. */
    data class Qry(val pageable: Pageable, val status: String? = null, val productId: String? = null, val hasReply: String? = null)

    data class Queue(val page: Page<AdminReviewDto>, val pendingCount: Int)

    override suspend fun execute(query: Qry): Result<Queue, Failure> {
        logger.info("FindAdminReviewsUc.execute status=${query.status} productId=${query.productId} hasReply=${query.hasReply} page=${query.pageable.page}")
        val filter = when (val parsed = filterOf(query)) {
            is Result.Success -> parsed.value
            is Result.Failure -> return Result.failure(parsed.failure)
        }
        return transactionManager.inTransaction {
            val page = reviewRepository.pageAdmin(filter, query.pageable)
            val seen = reviews.of(page.items, Reviews.Viewer(viewer = null, panel = true))
            val accounts = adminCustomerRepository.findAll(page.items.map { it.userId }.distinct())
            val items = page.items.zip(seen) { review, dto ->
                AdminReviewDto(dto, review.userId.value, review.contact?.value ?: accounts[review.userId]?.phone?.value)
            }
            Result.success(Queue(Page(items, page.page, page.size, page.totalItems), reviewRepository.countPending()))
        }
    }

    private fun filterOf(query: Qry): Result<ReviewRepository.AdminFilter, Failure> {
        val problems = mutableListOf<ResultFailure>()
        val status = query.status?.trim()?.ifEmpty { null }?.let { raw ->
            when (val parsed = ReviewStatus.makeNew(raw)) {
                is Result.Success -> parsed.value
                is Result.Failure -> null.also { problems += parsed.failure }
            }
        }
        val productId = query.productId?.trim()?.ifEmpty { null }?.let { raw ->
            raw.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() } ?: null.also { problems += FieldFailure("productId", Problem.ProductIdInvalid) }
        }
        val hasReply = when (query.hasReply?.trim()?.ifEmpty { null }) {
            null -> null
            "true" -> true
            "false" -> false
            else -> null.also { problems += FieldFailure("hasReply", Problem.BooleanInvalid) }
        }
        return if (problems.isEmpty()) Result.success(ReviewRepository.AdminFilter(status, productId, hasReply)) else Result.failure(Failure.InvalidInput(problems))
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object BooleanInvalid : Problem("BOOLEAN_INVALID")
        data object ProductIdInvalid : Problem("PRODUCT_ID_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
