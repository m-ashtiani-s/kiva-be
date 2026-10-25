package com.kiva.domain.aggregate.product.model.policy

import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.policy.ProductGalleryPolicy.Entry
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProductGalleryPolicyTest {

    private fun id(n: Int) = MediaId.constructFromPersisted("med_" + n.toString().padStart(22, '0'))

    private fun entry(n: Int, isPrimary: Boolean = false, sortOrder: Int? = null) = Entry(id(n), MediaView.FRONT, null, isPrimary, sortOrder)

    @Test
    fun `the primary one first, then by sortOrder (none last), then as listed`() {
        val ordered = ProductGalleryPolicy.order(
            listOf(entry(1), entry(2, sortOrder = 5), entry(3, sortOrder = 1), entry(4, isPrimary = true, sortOrder = 9), entry(5)),
        ).getOrNull()!!

        assertEquals(listOf(4, 3, 2, 1, 5).map(::id), ordered.map { it.mediaId })
    }

    @Test
    fun `an empty gallery is fine`() {
        assertEquals(emptyList(), ProductGalleryPolicy.order(emptyList()).getOrNull())
    }

    @Test
    fun `21 items is MEDIA_TOO_MANY on the list itself`() {
        val failure = ProductGalleryPolicy.order((1..21).map { entry(it) }).failureOrNull()!!

        assertEquals(listOf("MEDIA_TOO_MANY" to null), failure.causes.map { it.code to it.field })
        assertEquals(20, ProductGalleryPolicy.order((1..20).map { entry(it) }).getOrNull()?.size)
    }

    @Test
    fun `a file twice and a second primary are each refused at their place`() {
        val failure = ProductGalleryPolicy.order(listOf(entry(1, isPrimary = true), entry(2), entry(1), entry(3, isPrimary = true))).failureOrNull()!!

        assertEquals(
            listOf("MEDIA_DUPLICATE" to "2.mediaId", "MEDIA_PRIMARY_DUPLICATE" to "3.isPrimary"),
            failure.causes.map { it.code to it.field },
        )
    }
}
