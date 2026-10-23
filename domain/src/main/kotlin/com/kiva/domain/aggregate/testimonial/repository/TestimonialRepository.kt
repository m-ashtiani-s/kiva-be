package com.kiva.domain.aggregate.testimonial.repository

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.testimonial.entity.Testimonial
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId

/** Testimonials, a row each. Their colour follows a renamed colour key (the table's ON UPDATE CASCADE). */
interface TestimonialRepository {

    fun nextId(): TestimonialId

    fun find(id: TestimonialId): Testimonial?

    /**
     * One page, by `sortOrder`, then the latest received (none last), then the newest id (decision 12); only the
     * published or unpublished ones when [published] says which.
     */
    fun page(published: Boolean?, pageable: Pageable): Page<Testimonial>

    /** How many name [colorKey]: a colour in use can't leave the palette (decision 13). */
    fun countWithColor(colorKey: ColorKey): Int

    fun add(testimonial: Testimonial)

    fun save(testimonial: Testimonial)

    /** Removes it for good; false when there was none. */
    fun delete(id: TestimonialId): Boolean
}
