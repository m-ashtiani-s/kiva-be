package com.kiva.domain.aggregate.product.entity

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.product.ProductTestData.admin
import com.kiva.domain.aggregate.product.ProductTestData.newProduct
import com.kiva.domain.aggregate.product.ProductTestData.now
import com.kiva.domain.aggregate.product.ProductTestData.variantId
import com.kiva.domain.aggregate.product.ProductTestData.withColor
import com.kiva.domain.aggregate.product.entity.event.ProductPublishScheduled
import com.kiva.domain.aggregate.product.entity.event.ProductPublishUnscheduled
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProductScheduleTest {

    private val inTenMinutes = now.plus(Duration.ofMinutes(10))

    private fun draft() = newProduct().withColor(101, "lilac")

    @Test
    fun `a draft with an active color is scheduled and stays a draft`() {
        val product = draft()

        product.schedulePublish(inTenMinutes, admin, now)

        assertEquals(ProductStatus.DRAFT, product.status)
        assertEquals(inTenMinutes, product.publishAt)
        assertEquals(ProductPublishScheduled(product.id, product.getRecordedEvents().last().sequence, now, admin, inTenMinutes, product.getRecordedEvents().last().id), product.getRecordedEvents().last())
    }

    @Test
    fun `the same moment again records nothing, another one replaces it`() {
        val product = draft()
        product.schedulePublish(inTenMinutes, admin, now)
        val events = product.getRecordedEvents().size

        product.schedulePublish(inTenMinutes, admin, now)
        assertEquals(events, product.getRecordedEvents().size)

        product.schedulePublish(inTenMinutes.plusSeconds(60), admin, now)
        assertEquals(inTenMinutes.plusSeconds(60), product.publishAt)
    }

    @Test
    fun `only a draft with an active color, at most 365 days ahead`() {
        val bare = newProduct()
        val published = draft().also { it.publish(admin, now) }
        val archived = draft().also { it.archive(admin, now) }

        assertEquals(Result.failure(Product.SchedulePublishFailure.NoActiveVariant), bare.schedulePublish(inTenMinutes, admin, now))
        assertEquals(Result.failure(Product.SchedulePublishFailure.PublishAtNotAllowed), published.schedulePublish(inTenMinutes, admin, now))
        assertEquals(Result.failure(Product.SchedulePublishFailure.ProductIsArchived), archived.schedulePublish(inTenMinutes, admin, now))
        assertEquals(Result.failure(Product.SchedulePublishFailure.PublishAtTooFar), draft().schedulePublish(now.plus(Duration.ofDays(366)), admin, now))
        assertEquals(now.plus(Duration.ofDays(365)), draft().also { it.schedulePublish(now.plus(Duration.ofDays(365)), admin, now) }.publishAt)
    }

    @Test
    fun `a moment already passed is a publish now, so asking to schedule it is a bug`() {
        assertFailsWith<IllegalArgumentException> { draft().schedulePublish(now, admin, now) }
    }

    @Test
    fun `calling it off records one event, without a schedule it is PRODUCT_ALREADY_DRAFT`() {
        val product = draft().also { it.schedulePublish(inTenMinutes, admin, now) }

        product.unschedulePublish(admin, now)

        assertNull(product.publishAt)
        assertEquals(ProductPublishUnscheduled::class, product.getRecordedEvents().last()::class)
        assertEquals(Result.failure(Product.UnschedulePublishFailure.NotScheduled), product.unschedulePublish(admin, now))
    }

    @Test
    fun `publishing, taking out of the shop or archiving ends a schedule`() {
        val published = draft().also { it.schedulePublish(inTenMinutes, admin, now) }.also { it.publish(admin, now) }
        val archived = draft().also { it.schedulePublish(inTenMinutes, admin, now) }.also { it.archive(admin, now) }

        assertNull(published.publishAt)
        assertNull(archived.publishAt)
    }

    @Test
    fun `a schedule comes back when the product is read again`() {
        val product = draft().also { it.schedulePublish(inTenMinutes, admin, now) }
        val replayed = Product(product.getRecordedEvents())

        assertEquals(inTenMinutes, replayed.publishAt)
        assertEquals(variantId(101), replayed.variants.single().id)
    }
}
