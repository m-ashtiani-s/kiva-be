package com.kiva.application.usecase.testimonial.usecase.query

import com.kiva.application.dto.TestimonialDto
import com.kiva.application.usecase.testimonial.Testimonials
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository

/**
 * The panel's testimonials (`GET /admin/testimonials`; ADMIN, CONTENT_EDITOR): published and unpublished, or one of
 * them (`published`), in the shop's order. `published` neither `true` nor `false` is 400 BOOLEAN_INVALID.
 */
class ListAdminTestimonialsUc(
    private val transactionManager: TransactionManager,
    private val testimonialRepository: TestimonialRepository,
    private val testimonials: Testimonials,
) : QueryUseCase<ListAdminTestimonialsUc.Qry, Page<TestimonialDto>, ListAdminTestimonialsUc.Failure> {

    private val logger = getLogger()

    data class Qry(val pageable: Pageable, val published: String? = null)

    override suspend fun execute(query: Qry): Result<Page<TestimonialDto>, Failure> {
        logger.info("ListAdminTestimonialsUc.execute published=${query.published} page=${query.pageable.page}")
        val published = when (query.published?.trim()?.ifEmpty { null }) {
            null -> null
            "true" -> true
            "false" -> false
            else -> return Result.failure(Failure.InvalidInput(listOf(FieldFailure("published", Problem.BooleanInvalid))))
        }
        return transactionManager.inTransaction {
            val page = testimonialRepository.page(published, query.pageable)
            Result.success(Page(testimonials.of(page.items), page.page, page.size, page.totalItems))
        }
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object BooleanInvalid : Problem("BOOLEAN_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
