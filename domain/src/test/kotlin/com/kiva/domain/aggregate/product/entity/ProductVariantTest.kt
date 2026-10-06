package com.kiva.domain.aggregate.product.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.product.ProductTestData.admin
import com.kiva.domain.aggregate.product.ProductTestData.color
import com.kiva.domain.aggregate.product.ProductTestData.later
import com.kiva.domain.aggregate.product.ProductTestData.newProduct
import com.kiva.domain.aggregate.product.ProductTestData.now
import com.kiva.domain.aggregate.product.ProductTestData.photo
import com.kiva.domain.aggregate.product.ProductTestData.priceTag
import com.kiva.domain.aggregate.product.ProductTestData.snapshot
import com.kiva.domain.aggregate.product.ProductTestData.variantId
import com.kiva.domain.aggregate.product.ProductTestData.withColor
import com.kiva.domain.aggregate.product.entity.event.VariantDeactivated
import com.kiva.domain.aggregate.product.model.valueobject.MediaType
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductMedia
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What can happen to a product's colors after they were added. */
class ProductVariantTest {

    private fun Product.eventTypes() = getRecordedEvents().map { it.type.value }
    private fun Product.variant(id: Long) = findVariant(variantId(id))!!
    private fun Product.defaultId() = defaultVariant()?.id?.value

    /** Black (101, default), lilac (102) and cream (103), added in that order. */
    private fun threeColors() = newProduct().withColor(101, "black").withColor(102, "lilac").withColor(103, "cream")

    private fun Product.change(id: Long, ownPriceTag: PriceTag? = variant(id).ownPriceTag, sortOrder: Int = variant(id).sortOrder, makeDefault: Boolean = false) =
        changeVariant(variantId(id), ownPriceTag, sortOrder, makeDefault, admin, later)

    @Nested
    inner class ChangingAColor {
        @Test
        fun `its own price and position change, recorded as VariantChanged`() {
            val product = threeColors()

            product.change(102, ownPriceTag = priceTag(1_590_000, compareAt = 1_890_000), sortOrder = 9)

            assertEquals(priceTag(1_590_000, 1_890_000), product.variant(102).ownPriceTag)
            assertEquals(9, product.variant(102).sortOrder)
            assertEquals("VariantChanged", product.eventTypes().last())
        }

        @Test
        fun `it can go back to following the product's price`() {
            val product = newProduct().withColor(101, "lilac", ownPriceTag = priceTag(2_100_000))

            product.change(101, ownPriceTag = null)

            assertNull(product.variant(101).ownPriceTag)
        }

        @Test
        fun `making a color the default takes it from the one before, so exactly one stays the default`() {
            val product = threeColors()

            product.change(103, makeDefault = true)

            assertEquals(listOf(103L), product.variants.filter { it.isDefault }.map { it.id.value })
        }

        @Test
        fun `makeDefault false leaves the default where it is, even when changing the default color itself`() {
            val product = threeColors()

            product.change(101, sortOrder = 5, makeDefault = false)

            assertEquals(101L, product.defaultId())
        }

        @Test
        fun `the same values again record nothing`() {
            val product = threeColors()

            assertTrue(product.change(102).isSuccess)
            assertEquals(4, product.getRecordedEvents().size)
        }

        @Test
        fun `an unknown color is VARIANT_NOT_FOUND, a 404`() {
            val result = threeColors().changeVariant(variantId(999), null, 0, false, admin, later)

            assertEquals(Result.failure(Product.ChangeVariantFailure.VariantNotFound(variantId(999))), result)
            assertEquals(FailureKind.NOT_FOUND, result.failureOrNull()?.kind)
        }

        @Test
        fun `an inactive color can't become the default`() {
            val product = threeColors()
            product.deactivateVariant(variantId(102), admin, now)

            assertEquals(Result.failure(Product.ChangeVariantFailure.InactiveVariantCannotBeDefault), product.change(102, makeDefault = true))
            assertEquals(101L, product.defaultId())
        }

        @Test
        fun `an archived product's colors can't change`() {
            val product = threeColors()
            product.archive(admin, now)

            assertEquals(Result.failure(Product.ChangeVariantFailure.ProductIsArchived), product.change(102, sortOrder = 9))
        }
    }

    @Nested
    inner class DeactivatingAColor {
        @Test
        fun `it stays on the product, out of the shop and no longer the default`() {
            val product = threeColors()

            product.deactivateVariant(variantId(101), admin, now)

            assertFalse(product.variant(101).isActive)
            assertFalse(product.variant(101).isDefault)
            assertEquals(3, product.variants.size)
        }

        @Test
        fun `when the default goes, the next active color in display order takes over, and the event says which`() {
            val product = threeColors()
            product.change(103, sortOrder = -1) // cream now shows first

            product.deactivateVariant(variantId(101), admin, now)

            assertEquals(103L, product.defaultId())
            assertEquals(variantId(103), (product.getRecordedEvents().last() as VariantDeactivated).newDefaultVariantId)
        }

        @Test
        fun `deactivating a color that is not the default leaves the default alone`() {
            val product = threeColors()

            product.deactivateVariant(variantId(102), admin, now)

            assertEquals(101L, product.defaultId())
            assertNull((product.getRecordedEvents().last() as VariantDeactivated).newDefaultVariantId)
        }

        @Test
        fun `a draft may lose all its active colors, and then has no default and can't be published`() {
            val product = newProduct().withColor(101, "lilac")

            product.deactivateVariant(variantId(101), admin, now)

            assertNull(product.defaultVariant())
            assertEquals(Result.failure(Product.PublishFailure.NoActiveVariant), product.publish(admin, later))
        }

        @Test
        fun `a published product keeps at least one active color, until it is unpublished`() {
            val product = newProduct().withColor(101, "lilac")
            product.publish(admin, now)

            assertEquals(Result.failure(Product.DeactivateVariantFailure.LastActiveVariant), product.deactivateVariant(variantId(101), admin, later))

            product.unpublish(admin, later)
            assertTrue(product.deactivateVariant(variantId(101), admin, later).isSuccess)
        }

        @Test
        fun `deactivating twice is AlreadyInactive`() {
            val product = threeColors()
            product.deactivateVariant(variantId(102), admin, now)

            assertEquals(Result.failure(Product.DeactivateVariantFailure.AlreadyInactive), product.deactivateVariant(variantId(102), admin, later))
        }

        @Test
        fun `an unknown color is VARIANT_NOT_FOUND, and an archived product's colors stay as they are`() {
            val product = threeColors()
            assertEquals(
                Result.failure(Product.DeactivateVariantFailure.VariantNotFound(variantId(999))),
                product.deactivateVariant(variantId(999), admin, now),
            )

            product.archive(admin, now)
            assertEquals(Result.failure(Product.DeactivateVariantFailure.ProductIsArchived), product.deactivateVariant(variantId(102), admin, later))
        }
    }

    @Nested
    inner class ReactivatingAColor {
        @Test
        fun `a deactivated color can't be added again, so it comes back by reactivating`() {
            val product = threeColors()
            product.deactivateVariant(variantId(102), admin, now)

            val addedAgain = product.addVariant(variantId(104), color("lilac"), null, null, false, 3, emptyList(), admin, later)
            assertEquals(Result.failure(Product.AddVariantFailure.ColorAlreadyAdded(color("lilac"))), addedAgain)

            assertTrue(product.reactivateVariant(variantId(102), admin, later).isSuccess)
            assertTrue(product.variant(102).isActive)
        }

        @Test
        fun `it doesn't take the default from another active color`() {
            val product = threeColors()
            product.deactivateVariant(variantId(102), admin, now)

            product.reactivateVariant(variantId(102), admin, later)

            assertEquals(101L, product.defaultId())
        }

        @Test
        fun `it becomes the default when no other active color is`() {
            val product = newProduct().withColor(101, "lilac")
            product.deactivateVariant(variantId(101), admin, now)

            product.reactivateVariant(variantId(101), admin, later)

            assertEquals(101L, product.defaultId())
        }

        @Test
        fun `reactivating an active color is AlreadyActive`() {
            assertEquals(
                Result.failure(Product.ReactivateVariantFailure.AlreadyActive),
                threeColors().reactivateVariant(variantId(101), admin, now),
            )
        }
    }

    @Nested
    inner class Gallery {
        @Test
        fun `the gallery is replaced as a whole, in the given order`() {
            val product = newProduct().withColor(101, "lilac", media = listOf(photo("front"), photo("side")))

            product.replaceVariantMedia(variantId(101), listOf(photo("side"), photo("detail")), admin, later)

            assertEquals(listOf(photo("side"), photo("detail")), product.variant(101).media)
            assertEquals("VariantMediaReplaced", product.eventTypes().last())
        }

        @Test
        fun `an inactive color's gallery can be replaced too`() {
            val product = threeColors()
            product.deactivateVariant(variantId(102), admin, now)

            assertTrue(product.replaceVariantMedia(variantId(102), listOf(photo("front")), admin, later).isSuccess)
        }

        @Test
        fun `the same gallery again records nothing`() {
            val product = newProduct().withColor(101, "lilac", media = listOf(photo("front")))

            assertTrue(product.replaceVariantMedia(variantId(101), listOf(photo("front")), admin, later).isSuccess)
            assertEquals(2, product.getRecordedEvents().size)
        }

        @Test
        fun `a color with a video has one, and so does the product while that color is in the shop`() {
            val video = ProductMedia.constructFromPersisted(MediaType.VIDEO, "https://cdn.kiva.ir/p/1/lilac/intro.mp4", null, null)
            val product = threeColors()
            product.replaceVariantMedia(variantId(102), listOf(photo("front"), video), admin, now)

            assertTrue(product.variant(102).hasVideo)
            assertFalse(product.variant(101).hasVideo)
            assertTrue(product.hasVideo())

            product.deactivateVariant(variantId(102), admin, later)
            assertFalse(product.hasVideo())
        }

        @Test
        fun `an unknown color is VARIANT_NOT_FOUND`() {
            assertEquals(
                Result.failure(Product.ReplaceVariantMediaFailure.VariantNotFound(variantId(999))),
                newProduct().replaceVariantMedia(variantId(999), emptyList(), admin, now),
            )
        }
    }

    @Test
    fun `every color event replays to exactly the same product`() {
        val original = threeColors()
        original.change(102, ownPriceTag = priceTag(1_590_000), makeDefault = true)
        original.deactivateVariant(variantId(102), admin, later)
        original.reactivateVariant(variantId(102), admin, later)
        original.replaceVariantMedia(variantId(103), listOf(photo("front"), photo("side")), admin, later)
        original.publish(admin, later)

        val loaded = Product(original.getRecordedEvents())

        assertEquals(original.snapshot(), loaded.snapshot())
        assertEquals(
            listOf("ProductCreated", "VariantAdded", "VariantAdded", "VariantAdded", "VariantChanged", "VariantDeactivated",
                "VariantReactivated", "VariantMediaReplaced", "ProductPublished"),
            original.eventTypes(),
        )
    }
}
