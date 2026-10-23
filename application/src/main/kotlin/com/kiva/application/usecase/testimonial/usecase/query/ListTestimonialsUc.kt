package com.kiva.application.usecase.testimonial.usecase.query

import com.kiva.application.dto.TestimonialListDto
import com.kiva.application.usecase.testimonial.Testimonials
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository

/**
 * The shop's testimonials (`GET /testimonials`, public; roadmap section 16.2, decisions 12 and 14): one page of the
 * published ones in the panel's order, and the satisfaction summary exactly as the panel typed it in the settings
 * (nothing is counted from reviews or messages). Never cached.
 */
class ListTestimonialsUc(
    private val transactionManager: TransactionManager,
    private val testimonialRepository: TestimonialRepository,
    private val testimonials: Testimonials,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<ListTestimonialsUc.Qry, TestimonialListDto, Nothing> {

    private val logger = getLogger()

    data class Qry(val pageable: Pageable)

    override suspend fun execute(query: Qry): Result<TestimonialListDto, Nothing> {
        logger.info("ListTestimonialsUc.execute page=${query.pageable.page}")
        return transactionManager.inTransaction {
            val page = testimonialRepository.page(published = true, query.pageable)
            val items = testimonials.of(page.items)
            Result.success(TestimonialListDto(shopSettingsRepository.get().siteStats.satisfaction, Page(items, page.page, page.size, page.totalItems)))
        }
    }
}
