package com.kiva.application.dto

import com.kiva.application.repository.ProductBriefQm
import com.kiva.common.pagination.Page
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.settings.model.valueobject.Satisfaction
import com.kiva.domain.aggregate.testimonial.entity.Testimonial

/**
 * A testimonial as the shop and the panel show it (contract Testimonial and AdminTestimonial, before their Persian
 * texts): with its product, its colour and its screenshot in full.
 */
data class TestimonialDto(val testimonial: Testimonial, val product: ProductBriefQm, val color: Color?, val screenshot: UploadedMediaDto?) {
    override fun toString(): String = "TestimonialDto(id=${testimonial.id.value})"
}

/**
 * The shop's testimonials (contract TestimonialListResponse): the satisfaction summary the panel typed (decision 14; null
 * until it does) and one page of the published ones.
 */
data class TestimonialListDto(val summary: Satisfaction?, val page: Page<TestimonialDto>)
