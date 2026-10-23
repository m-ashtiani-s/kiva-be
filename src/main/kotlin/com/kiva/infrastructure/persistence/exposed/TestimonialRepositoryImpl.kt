package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.testimonial.entity.Testimonial
import com.kiva.domain.aggregate.testimonial.model.valueobject.TestimonialId
import com.kiva.domain.aggregate.testimonial.repository.TestimonialRepository
import com.kiva.infrastructure.persistence.exposed.tbl.TestimonialTbl
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class TestimonialRepositoryImpl : TestimonialRepository {

    override fun nextId(): TestimonialId =
        TransactionManager.current().exec("SELECT nextval('${TestimonialTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            TestimonialId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${TestimonialTbl.ID_SEQUENCE}') returned nothing")

    override fun find(id: TestimonialId): Testimonial? =
        TestimonialTbl.selectAll().where { TestimonialTbl.testimonialId eq id.value }.singleOrNull()?.toTestimonial()

    // The public order (decision 12), which testimonial_public_idx serves for the published ones.
    override fun page(published: Boolean?, pageable: Pageable): Page<Testimonial> {
        val condition: Op<Boolean> = if (published == null) Op.TRUE else TestimonialTbl.isPublished eq published
        val total = TestimonialTbl.selectAll().where { condition }.count()
        val rows = TestimonialTbl.selectAll().where { condition }
            .orderBy(TestimonialTbl.sortOrder to SortOrder.ASC, TestimonialTbl.receivedAt to SortOrder.DESC_NULLS_LAST, TestimonialTbl.testimonialId to SortOrder.DESC)
            .limit(pageable.size).offset(pageable.offset)
            .map { it.toTestimonial() }
        return Page(rows, pageable.page, pageable.size, total)
    }

    override fun countWithColor(colorKey: ColorKey): Int =
        TestimonialTbl.selectAll().where { TestimonialTbl.colorKey eq colorKey.value }.count().toInt()

    override fun add(testimonial: Testimonial) {
        TestimonialTbl.insert {
            it[testimonialId] = testimonial.id.value
            it[createdOn] = testimonial.createdOn.utc()
            it.fill(testimonial)
        }
    }

    override fun save(testimonial: Testimonial) {
        TestimonialTbl.update({ TestimonialTbl.testimonialId eq testimonial.id.value }) { it.fill(testimonial) }
    }

    override fun delete(id: TestimonialId): Boolean = TestimonialTbl.deleteWhere { TestimonialTbl.testimonialId eq id.value } > 0

    private fun UpdateBuilder<*>.fill(testimonial: Testimonial) {
        this[TestimonialTbl.customerName] = testimonial.customerName
        this[TestimonialTbl.city] = testimonial.city
        this[TestimonialTbl.sourceChannel] = testimonial.source
        this[TestimonialTbl.message] = testimonial.message
        this[TestimonialTbl.messageTime] = testimonial.messageTime
        this[TestimonialTbl.receivedAt] = testimonial.receivedAt?.utc()
        this[TestimonialTbl.rating] = testimonial.rating.value.toShort()
        this[TestimonialTbl.productId] = testimonial.productId.value
        this[TestimonialTbl.colorKey] = testimonial.colorKey?.value
        this[TestimonialTbl.screenshotMediaId] = testimonial.screenshotMediaId?.value
        this[TestimonialTbl.isPublished] = testimonial.isPublished
        this[TestimonialTbl.sortOrder] = testimonial.sortOrder
        this[TestimonialTbl.updatedOn] = testimonial.updatedOn.utc()
    }

    private fun ResultRow.toTestimonial(): Testimonial = Testimonial.constructFromPersisted(
        id = TestimonialId.constructFromPersisted(this[TestimonialTbl.testimonialId]),
        customerName = this[TestimonialTbl.customerName],
        city = this[TestimonialTbl.city],
        source = this[TestimonialTbl.sourceChannel],
        message = this[TestimonialTbl.message],
        messageTime = this[TestimonialTbl.messageTime],
        receivedAt = this[TestimonialTbl.receivedAt]?.toInstant(),
        rating = StarRating.constructFromPersisted(this[TestimonialTbl.rating].toInt()),
        productId = ProductId.constructFromPersisted(this[TestimonialTbl.productId]),
        colorKey = this[TestimonialTbl.colorKey]?.let { ColorKey.constructFromPersisted(it) },
        screenshotMediaId = this[TestimonialTbl.screenshotMediaId]?.let { MediaId.constructFromPersisted(it) },
        isPublished = this[TestimonialTbl.isPublished],
        sortOrder = this[TestimonialTbl.sortOrder],
        createdOn = this[TestimonialTbl.createdOn].toInstant(),
        updatedOn = this[TestimonialTbl.updatedOn].toInstant(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
