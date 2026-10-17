package com.kiva.domain.aggregate.product.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.ProductTestData
import com.kiva.domain.aggregate.product.ProductTestData.admin
import com.kiva.domain.aggregate.product.ProductTestData.color
import com.kiva.domain.aggregate.product.ProductTestData.details
import com.kiva.domain.aggregate.product.ProductTestData.later
import com.kiva.domain.aggregate.product.ProductTestData.newProduct
import com.kiva.domain.aggregate.product.ProductTestData.now
import com.kiva.domain.aggregate.product.ProductTestData.priceTag
import com.kiva.domain.aggregate.product.ProductTestData.snapshot
import com.kiva.domain.aggregate.product.ProductTestData.variantId
import com.kiva.domain.aggregate.product.ProductTestData.withColor
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.entity.event.ProductDetailsChanged
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.ACTIVE
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.ARCHIVED
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.DRAFT
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductTest {

    private fun Product.eventTypes() = getRecordedEvents().map { it.type.value }

    @Nested
    inner class Creating {
        @Test
        fun `a new product is a draft without colors, made by one ProductCreated event`() {
            val product = newProduct()

            assertEquals(DRAFT, product.status)
            assertEquals(emptyList(), product.variants)
            val created = product.getRecordedEvents().single() as ProductCreated
            assertEquals(1L, created.sequence.value)
            assertEquals("PRODUCT", created.aggregateName.value)
            assertEquals("1", created.aggregateId.value)
            assertEquals(admin, created.occurredByUserId)
            assertEquals(now, created.occurredOn)
        }

        @Test
        fun `without a typed SKU the product gets KV- and 1000 plus its id`() {
            assertEquals("KV-1016", newProduct(id = 16).sku.value)
        }

        @Test
        fun `a typed SKU is kept`() {
            assertEquals("MAHAK", newProduct(sku = "MAHAK").sku.value)
        }
    }

    @Nested
    inner class Details {
        @Test
        fun `changing the details records who changed them and when`() {
            val product = newProduct()

            product.changeDetails(details(name = "کیف دوشی ماهک جدید"), admin, later)

            assertEquals("کیف دوشی ماهک جدید", product.details.name.value)
            val changed = product.getRecordedEvents().last() as ProductDetailsChanged
            assertEquals(later, changed.occurredOn)
        }

        @Test
        fun `saving the same details records nothing`() {
            val product = newProduct()

            assertTrue(product.changeDetails(details(), admin, later).isSuccess)
            assertEquals(listOf("ProductCreated"), product.eventTypes())
        }

        @Test
        fun `an archived product's details can't be changed`() {
            val product = newProduct()
            product.archive(admin, now)

            assertEquals(
                Result.failure(Product.ChangeDetailsFailure.ProductIsArchived),
                product.changeDetails(details(name = "اسم دیگه"), admin, later),
            )
            assertEquals("کیف دوشی ماهک", product.details.name.value)
            assertEquals(listOf("ProductCreated", "ProductArchived"), product.eventTypes())
        }
    }

    @Nested
    inner class Price {
        @Test
        fun `a price change is its own event, apart from the details`() {
            val product = newProduct()

            product.changePrice(priceTag(1_450_000, compareAt = 1_750_000), admin, later)

            assertEquals(priceTag(1_450_000, 1_750_000), product.priceTag)
            assertEquals(listOf("ProductCreated", "ProductPriceChanged"), product.eventTypes())
        }

        @Test
        fun `the same price again records nothing`() {
            val product = newProduct(priceTag = priceTag(1_890_000))

            assertTrue(product.changePrice(priceTag(1_890_000), admin, later).isSuccess)
            assertEquals(listOf("ProductCreated"), product.eventTypes())
        }

        @Test
        fun `an archived product's price can't be changed`() {
            val product = newProduct()
            product.archive(admin, now)

            assertEquals(Result.failure(Product.ChangePriceFailure.ProductIsArchived), product.changePrice(priceTag(1), admin, later))
        }

        @Test
        fun `a color with its own price keeps it when the product's price changes`() {
            val product = newProduct().withColor(101, "lilac", ownPriceTag = priceTag(2_100_000))

            product.changePrice(priceTag(1_450_000), admin, later)

            assertEquals(priceTag(2_100_000), product.variants.single().ownPriceTag)
        }
    }

    @Nested
    inner class Colors {
        @Test
        fun `the first color becomes the default even when not asked, and takes its SKU from the product's`() {
            val variant = newProduct().withColor(101, "lilac").variants.single()

            assertTrue(variant.isDefault)
            assertTrue(variant.isActive)
            assertEquals("KV-1001-LILAC", variant.sku.value)
        }

        @Test
        fun `a color asked to be the default takes it over, so exactly one stays the default`() {
            val product = newProduct().withColor(101, "black").withColor(102, "lilac", makeDefault = true)

            assertEquals(listOf(102L), product.variants.filter { it.isDefault }.map { it.id.value })
            assertEquals(102L, product.defaultVariant()?.id?.value)
        }

        @Test
        fun `a color not asked to be the default leaves the default where it was`() {
            val product = newProduct().withColor(101, "black").withColor(102, "lilac")

            assertEquals(101L, product.defaultVariant()?.id?.value)
        }

        @Test
        fun `the same color can't be added twice`() {
            val product = newProduct().withColor(101, "lilac")

            val result = product.addVariant(variantId(102), color("lilac"), null, null, false, 1, emptyList(), admin, now)

            assertEquals(Result.failure(Product.AddVariantFailure.ColorAlreadyAdded(color("lilac"))), result)
            assertEquals(FailureKind.CONFLICT, result.failureOrNull()?.kind)
            assertEquals(1, product.variants.size)
        }

        @Test
        fun `a typed SKU already used by another color, or by the product itself, is DUPLICATE_SKU`() {
            val product = newProduct().withColor(101, "black", sku = "MAHAK-BLACK")

            val takenByColor = product.addVariant(variantId(102), color("lilac"), Sku.constructFromPersisted("MAHAK-BLACK"), null, false, 1, emptyList(), admin, now)
            val takenByProduct = product.addVariant(variantId(102), color("lilac"), Sku.constructFromPersisted("KV-1001"), null, false, 1, emptyList(), admin, now)

            assertEquals(Result.failure(Product.AddVariantFailure.SkuAlreadyUsed), takenByColor)
            assertEquals(Result.failure(Product.AddVariantFailure.SkuAlreadyUsed), takenByProduct)
            assertEquals("DUPLICATE_SKU", Product.AddVariantFailure.SkuAlreadyUsed.code)
        }

        @Test
        fun `an archived product gets no new colors`() {
            val product = newProduct()
            product.archive(admin, now)

            val result = product.addVariant(variantId(101), color("lilac"), null, null, false, 0, emptyList(), admin, now)

            assertEquals(Result.failure(Product.AddVariantFailure.ProductIsArchived), result)
        }

        @Test
        fun `an id already on the product is a bug, because ids are handed out once`() {
            val product = newProduct().withColor(101, "lilac")

            assertFailsWith<IllegalArgumentException> {
                product.addVariant(variantId(101), color("black"), null, null, false, 1, emptyList(), admin, now)
            }
        }

        @Test
        fun `a color keeps its gallery as it was given, even if the caller's list changes later`() {
            val front = ProductTestData.photo("lilacfront")
            val gallery = mutableListOf(front)
            val product = newProduct().withColor(101, "lilac", media = gallery)

            gallery.clear()

            assertEquals(listOf(front), product.variants.single().media)
        }

    }

    @Nested
    inner class Status {
        @Test
        fun `a product without an active color can't be published`() {
            val product = newProduct()

            assertEquals(Result.failure(Product.PublishFailure.NoActiveVariant), product.publish(admin, now))
            assertEquals(DRAFT, product.status)
        }

        @Test
        fun `a draft with a color is published`() {
            val product = newProduct().withColor(101, "lilac")

            assertTrue(product.publish(admin, now).isSuccess)
            assertEquals(ACTIVE, product.status)
            assertEquals("ProductPublished", product.eventTypes().last())
        }

        @Test
        fun `publishing what is already in the shop is AlreadyActive, and records nothing`() {
            val product = newProduct().withColor(101, "lilac")
            product.publish(admin, now)

            assertEquals(Result.failure(Product.PublishFailure.AlreadyActive), product.publish(admin, later))
            assertEquals(1, product.eventTypes().count { it == "ProductPublished" })
        }

        @Test
        fun `unpublishing takes a published product back to draft`() {
            val product = newProduct().withColor(101, "lilac")
            product.publish(admin, now)

            assertTrue(product.unpublish(admin, later).isSuccess)
            assertEquals(DRAFT, product.status)
        }

        @Test
        fun `unpublishing a draft is AlreadyDraft`() {
            assertEquals(Result.failure(Product.UnpublishFailure.AlreadyDraft), newProduct().unpublish(admin, now))
        }

        @Test
        fun `a draft or a published product can be archived`() {
            val draft = newProduct()
            val published = newProduct().withColor(101, "lilac").also { it.publish(admin, now) }

            assertTrue(draft.archive(admin, later).isSuccess)
            assertTrue(published.archive(admin, later).isSuccess)
            assertEquals(listOf(ARCHIVED, ARCHIVED), listOf(draft.status, published.status))
        }

        @Test
        fun `archiving twice is AlreadyArchived`() {
            val product = newProduct()
            product.archive(admin, now)

            assertEquals(Result.failure(Product.ArchiveFailure.AlreadyArchived), product.archive(admin, later))
        }

        @Test
        fun `archived is final, so publishing or unpublishing it is INVALID_STATUS_TRANSITION with from and to`() {
            val product = newProduct().withColor(101, "lilac")
            product.archive(admin, now)

            val publish = product.publish(admin, later).failureOrNull()!!
            val unpublish = product.unpublish(admin, later).failureOrNull()!!

            assertEquals("INVALID_STATUS_TRANSITION", publish.code)
            assertEquals(FailureKind.BUSINESS_RULE, publish.kind)
            assertEquals(mapOf<String, Any>("from" to "ARCHIVED", "to" to "ACTIVE"), publish.meta)
            assertEquals(mapOf<String, Any>("from" to "ARCHIVED", "to" to "DRAFT"), unpublish.meta)
            assertEquals(ARCHIVED, product.status)
        }
    }

    @Nested
    inner class Replay {
        @Test
        fun `one command may raise several events, numbered 1, 2, 3`() {
            val product = newProduct().withColor(101, "black").withColor(102, "lilac")

            assertEquals(listOf(1L, 2L, 3L), product.getRecordedEvents().map { it.sequence.value })
        }

        @Test
        fun `create, change the price, add colors, then replaying the events rebuilds exactly the same product`() {
            val original = newProduct()
            original.changePrice(priceTag(1_450_000, compareAt = 1_750_000), admin, later)
            original.withColor(101, "black").withColor(102, "lilac", makeDefault = true, ownPriceTag = priceTag(1_590_000))
            original.changeDetails(details(maxPerOrder = 3), admin, later)
            original.publish(admin, later)

            val loaded = Product(original.getRecordedEvents())

            assertEquals(original.snapshot(), loaded.snapshot())
            assertEquals(emptyList(), loaded.getRecordedEvents())
        }

        @Test
        fun `a new product created with two colors is version 3, one event each`() {
            val product = newProduct().withColor(101, "black").withColor(102, "lilac")

            assertEquals(3L, product.version)
        }

        @Test
        fun `a product loaded from 5 events is version 5, and the next change makes it 6`() {
            val original = newProduct().withColor(101, "black").withColor(102, "lilac")
            original.changePrice(priceTag(1_450_000), admin, later)
            original.publish(admin, later)
            val loaded = Product(original.getRecordedEvents())
            assertEquals(5L, loaded.version)

            loaded.unpublish(admin, later)

            assertEquals(6L, loaded.version)
        }

        @Test
        fun `saving the same details again leaves the version where it was`() {
            val product = newProduct()

            product.changeDetails(details(), admin, later)

            assertEquals(1L, product.version)
        }

        @Test
        fun `a loaded product goes on numbering after its last event`() {
            val original = newProduct().withColor(101, "lilac")
            val loaded = Product(original.getRecordedEvents())

            loaded.archive(admin, later)

            assertEquals(listOf(3L), loaded.getRecordedEvents().map { it.sequence.value })
        }
    }
}
