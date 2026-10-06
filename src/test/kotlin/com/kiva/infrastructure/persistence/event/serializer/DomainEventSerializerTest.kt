package com.kiva.infrastructure.persistence.event.serializer

import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainEventSerializerTest {

    private fun roundTrip(event: ProductDomainEvent): ProductDomainEvent =
        DomainEventSerializer.deserializeProduct(DomainEventSerializer.serialize(event), event.version.value, event.type.value)

    @Nested
    inner class RoundTrip {
        @Test
        fun `every kind of event comes back exactly as it was, every field included`() {
            ProductEventSamples.onePerKind.forEach { event ->
                assertEquals(event, roundTrip(event), "${event.type.value} changed on the way")
            }
        }

        @Test
        fun `optional parts left empty come back empty, not filled with something`() {
            ProductEventSamples.nothingOptional.forEach { event ->
                assertEquals(event, roundTrip(event), "${event.type.value} changed on the way")
            }
        }

        @Test
        fun `the samples cover every kind of product event, so a new kind can't skip the round trip`() {
            assertEquals(
                ProductDomainEvent::class.sealedSubclasses.toSet(),
                ProductEventSamples.onePerKind.map { it::class }.toSet(),
            )
        }

        @Test
        fun `no two kinds of event share a stored type, or one would be read back as the other`() {
            val types = ProductEventSamples.onePerKind.map { it.type.value }

            assertEquals(types.size, types.toSet().size, "repeated types: ${types.groupBy { it }.filterValues { it.size > 1 }.keys}")
        }
    }

    /**
     * Events stored today must still be read after any change to the code. These are the stored shapes, written out by
     * hand: renaming or removing a payload field breaks them, which is the point.
     */
    @Nested
    inner class StoredShape {
        private val productCreatedJson = """
            {
              "id": "3f6d2c1e-8b4a-4c6e-9a1b-000000000001",
              "productId": 7,
              "sequence": 1,
              "occurredOn": "2026-10-06T08:15:30.123456789Z",
              "occurredByUserId": 12,
              "slug": "mahak-shoulder-bag",
              "sku": "KV-1007",
              "details": {
                "name": "کیف دوشی ماهک",
                "categoryId": 1,
                "materialId": 4,
                "materialDescription": "چرم مصنوعی مات درجه یک",
                "bagType": "HOBO",
                "shortDescription": "کیف دوشی جادار برای هر روز",
                "description": "یه کیف \"ساده\" با دو جیب.\nخط دوم.",
                "highlights": ["یراق‌آلات فلزی ضدزنگ", "آستر ساتن"],
                "specs": {
                  "dimensions": { "lengthCm": 32.5, "heightCm": 24.0, "widthCm": 12.0 },
                  "weightGrams": 650,
                  "closure": "ZIPPER_WITH_DECORATIVE_LOCK",
                  "strap": "بند دوشی ثابت ۵۵ سانتی‌متر",
                  "pockets": "۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل",
                  "occasions": ["DAILY", "WORK"]
                },
                "newUntil": "2026-10-20T00:00:00Z",
                "maxPerOrder": 3,
                "returnable": false,
                "sortPriority": 10
              },
              "priceTag": { "price": 1750000, "compareAtPrice": 2100000 }
            }
        """.trimIndent()

        private val variantAddedJson = """
            {
              "id": "3f6d2c1e-8b4a-4c6e-9a1b-000000000002",
              "productId": 7,
              "sequence": 2,
              "occurredOn": "2026-10-06T08:15:30.123456789Z",
              "occurredByUserId": 12,
              "variantId": 21,
              "colorKey": "lilac",
              "sku": "KV-1007-LILAC",
              "ownPriceTag": { "price": 1450000, "compareAtPrice": 1750000 },
              "isDefault": true,
              "sortOrder": 0,
              "media": [
                { "type": "IMAGE", "url": "https://cdn.kiva.ir/p/7/lilac/front.webp", "view": "FRONT", "alt": "نمای جلوی کیف یاسی" },
                { "type": "VIDEO", "url": "https://cdn.kiva.ir/p/7/lilac/turn.mp4", "view": null, "alt": null }
              ]
            }
        """.trimIndent()

        @Test
        fun `a stored ProductCreated is read back`() {
            assertEquals(ProductEventSamples.productCreated, DomainEventSerializer.deserializeProduct(productCreatedJson, 1, "ProductCreated"))
        }

        @Test
        fun `a stored VariantAdded is read back`() {
            assertEquals(ProductEventSamples.variantAdded, DomainEventSerializer.deserializeProduct(variantAddedJson, 1, "VariantAdded"))
        }

        @Test
        fun `events are written in that same shape`() {
            assertEquals(Json.parseToJsonElement(productCreatedJson), Json.parseToJsonElement(DomainEventSerializer.serialize(ProductEventSamples.productCreated)))
            assertEquals(Json.parseToJsonElement(variantAddedJson), Json.parseToJsonElement(DomainEventSerializer.serialize(ProductEventSamples.variantAdded)))
        }
    }

    /** Stored data this code can't read is a bug or a missing reader, never something to guess around. */
    @Nested
    inner class UnreadableData {
        @Test
        fun `an unknown event type stops`() {
            assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeProduct("{}", 1, "ProductTeleported") }
        }

        @Test
        fun `a version nobody wrote a reader for stops`() {
            val stored = DomainEventSerializer.serialize(ProductEventSamples.productCreated)

            assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeProduct(stored, 2, "ProductCreated") }
        }

        @Test
        fun `a missing field stops instead of being guessed`() {
            val withoutSku = DomainEventSerializer.serialize(ProductEventSamples.productCreated).replace(""""sku":"KV-1007",""", "")

            assertFailsWith<SerializationException> { DomainEventSerializer.deserializeProduct(withoutSku, 1, "ProductCreated") }
        }

        @Test
        fun `a stored value that breaks a rule stops at the value object`() {
            // A crossed-out price below the price: PriceTag's init refuses it.
            val broken = DomainEventSerializer.serialize(ProductEventSamples.productCreated).replace(""""compareAtPrice":2100000""", """"compareAtPrice":1000""")

            assertFailsWith<IllegalArgumentException> { DomainEventSerializer.deserializeProduct(broken, 1, "ProductCreated") }
        }
    }
}
