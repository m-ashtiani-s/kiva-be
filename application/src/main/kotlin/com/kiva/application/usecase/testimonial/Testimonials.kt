package com.kiva.application.usecase.testimonial

import com.kiva.application.dto.TestimonialDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.product.MomentField
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.testimonial.entity.Testimonial
import com.kiva.domain.aggregate.testimonial.model.TestimonialDraft
import java.time.Instant

/**
 * The contract's TestimonialUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and
 * `PUT /admin/testimonials*`. `PUT` replaces the whole testimonial, so a field left out is its default (5 stars,
 * published, `sortOrder` 0).
 */
data class TestimonialFields(
    val customerName: String?,
    val city: String?,
    val source: String?,
    val message: String?,
    val messageTime: String?,
    val receivedAt: String?,
    val rating: Int?,
    val productId: Long?,
    val colorKey: String?,
    val screenshotMediaId: String?,
    val isPublished: Boolean?,
    val sortOrder: Int?,
) {
    /** Every problem of the form at once, each under its field; [now] for «not after now». */
    fun check(now: Instant): Result<TestimonialDraft, InputProblems> = TestimonialDraft.makeNew(
        TestimonialDraft.Input(
            customerName, city, source, message, messageTime, MomentField.makeNew(receivedAt), rating, productId, colorKey, screenshotMediaId,
            isPublished, sortOrder,
        ),
        now,
    )
}

/**
 * A testimonial's product must be there and not archived (400 PRODUCT_NOT_FOUND on `productId`), and its colour, when
 * given, one of that product's (400 COLOR_NOT_FOUND on `colorKey`; decision 11). One read; inside the caller's
 * transaction.
 */
class TestimonialReferences(private val productQmRepository: ProductQmRepository) {

    fun problemsOf(draft: TestimonialDraft): List<ResultFailure> {
        val product = productQmRepository.findAdminDetail(draft.productId)
        if (product == null || product.status == ProductStatus.ARCHIVED) return listOf(FieldFailure("productId", TestimonialDraft.Problem.ProductNotFound))
        val color = draft.colorKey ?: return emptyList()
        return if (product.variants.any { it.colorKey == color }) emptyList() else listOf(FieldFailure("colorKey", ColorNotFound))
    }

    data object ColorNotFound : ResultFailure("COLOR_NOT_FOUND", FailureKind.INVALID)
}

/**
 * Testimonials as the shop and the panel show them (Rule 15): each with its product (any status: one taken off the shop
 * keeps its name), its colour from the palette, and its screenshot. Three reads for a whole page. Inside the caller's
 * transaction.
 */
class Testimonials(
    private val productQmRepository: ProductQmRepository,
    private val colorRepository: ColorRepository,
    private val mediaRepository: MediaRepository,
) {

    fun of(testimonials: List<Testimonial>): List<TestimonialDto> {
        if (testimonials.isEmpty()) return emptyList()
        val products = productQmRepository.findBriefs(testimonials.map { it.productId }.distinct())
        val palette = colorRepository.findAll().associateBy { it.key }
        val screenshots = mediaRepository.findAll(testimonials.mapNotNull { it.screenshotMediaId }.distinct()).associateBy { it.id }
        return testimonials.map { testimonial ->
            // The read model keeps every product ever made, and the form only takes one that is there.
            val product = products[testimonial.productId] ?: error("Testimonial ${testimonial.id.value} points at product ${testimonial.productId.value}, which never was")
            TestimonialDto(
                testimonial = testimonial,
                product = product,
                color = testimonial.colorKey?.let { palette[it] },
                screenshot = testimonial.screenshotMediaId?.let { screenshots[it] }?.let(UploadedMediaDto::from),
            )
        }
    }
}
