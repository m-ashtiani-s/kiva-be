package com.kiva.application.usecase.testimonial.usecase.query

import com.kiva.application.dto.TestimonialDto
import com.kiva.application.usecase.testimonial.Testimonials
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository

/** One testimonial as the panel's form shows it: the answer of making and replacing one (AdminTestimonial). */
class GetAdminTestimonialUc(
    private val transactionManager: TransactionManager,
    private val testimonialRepository: TestimonialRepository,
    private val testimonials: Testimonials,
) : QueryUseCase<GetAdminTestimonialUc.Qry, TestimonialDto, GetAdminTestimonialUc.Failure> {

    private val logger = getLogger()

    data class Qry(val testimonialId: TestimonialId)

    override suspend fun execute(query: Qry): Result<TestimonialDto, Failure> {
        logger.info("GetAdminTestimonialUc.execute testimonialId=${query.testimonialId.value}")
        return transactionManager.inTransaction {
            val testimonial = testimonialRepository.find(query.testimonialId) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(testimonials.of(listOf(testimonial)).single())
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
