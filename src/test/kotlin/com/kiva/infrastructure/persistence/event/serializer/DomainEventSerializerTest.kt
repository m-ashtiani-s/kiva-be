package com.kiva.infrastructure.persistence.event.serializer

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.product.entity.event.ProductCreated
import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.domain.aggregate.product.entity.event.VariantAdded
import com.kiva.domain.aggregate.product.entity.event.VariantChanged
import com.kiva.domain.aggregate.product.model.valueobject.PocketInfo
import com.kiva.domain.aggregate.product.model.valueobject.ProductTags
import com.kiva.domain.aggregate.product.model.valueobject.StrapInfo
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
                  "strap": null,
                  "pockets": null,
                  "occasions": ["DAILY", "WORK"],
                  "straps": [
                    { "type": "SHOULDER", "label": "بند دوشی ثابت ۵۵ سانتی‌متر", "lengthCm": 55.0, "minLengthCm": null, "maxLengthCm": null, "adjustable": false, "detachable": false, "material": null },
                    { "type": "CROSSBODY", "label": null, "lengthCm": null, "minLengthCm": 80.0, "maxLengthCm": 120.0, "adjustable": true, "detachable": true, "material": "زنجیر فلزی" }
                  ],
                  "pocketInfo": {
                    "internal": 0, "internalZip": 1, "external": 0, "phone": 2, "cardSlots": 0, "billSlots": 0,
                    "coinPocket": false, "detachablePouch": true, "laptopSleeve": false, "summary": "۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل"
                  },
                  "laptopMaxInch": 13.3,
                  "capacityLiters": 9.5,
                  "waterproof": true,
                  "fits": ["PHONE", "A5_NOTEBOOK"],
                  "lining": { "material": "آستر ضدلک", "color": "یاسی کیوا" },
                  "hardware": { "material": "فلز ضدزنگ", "finish": null },
                  "stitching": "دوخت دولایه",
                  "handmade": true,
                  "madeIn": "ایران",
                  "careInstructions": ["با دستمال مرطوب"],
                  "warranty": { "months": 6, "description": "ضمانت دوخت و یراق" }
                },
                "newUntil": "2026-10-20T00:00:00Z",
                "maxPerOrder": 3,
                "returnable": false,
                "reservable": false,
                "sortPriority": 10,
                "tags": ["چرم", "اداری"],
                "seo": { "title": "کیف دوشی ماهک | کیوا", "description": null, "noIndex": true }
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
                { "type": "IMAGE", "url": null, "view": "FRONT", "alt": "نمای جلوی کیف یاسی", "mediaId": "med_5Hq2xT9aLmQ0vB3nR7sK1w" },
                { "type": "VIDEO", "url": null, "view": "SIDE", "alt": null, "mediaId": "med_0000000000000000000002" }
              ],
              "barcode": "6260000123456"
            }
        """.trimIndent()

        /** A ProductCreated as stored before step 1.5: one line of text for the strap and the pockets, no tags, no seo. */
        private val productCreatedBefore15Json = """
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
                "materialDescription": null,
                "bagType": "HOBO",
                "shortDescription": null,
                "description": null,
                "highlights": [],
                "specs": {
                  "dimensions": null,
                  "weightGrams": 650,
                  "closure": "ZIPPER",
                  "strap": "بند دوشی ثابت ۵۵ سانتی‌متر",
                  "pockets": "۱ جیب زیپ‌دار داخلی",
                  "occasions": ["DAILY"]
                },
                "newUntil": null,
                "maxPerOrder": 5,
                "returnable": true,
                "reservable": true,
                "sortPriority": 0
              },
              "priceTag": { "price": 1750000, "compareAtPrice": null }
            }
        """.trimIndent()

        /** A VariantAdded and a VariantChanged as stored before step 1.5: no barcode; every stored gallery was empty. */
        private val variantAddedBefore15Json = """
            {
              "id": "3f6d2c1e-8b4a-4c6e-9a1b-000000000002", "productId": 7, "sequence": 2, "occurredOn": "2026-10-06T08:15:30Z",
              "occurredByUserId": 0, "variantId": 21, "colorKey": "lilac", "sku": "KV-1007-LILAC", "ownPriceTag": null,
              "isDefault": true, "sortOrder": 0, "media": []
            }
        """.trimIndent()

        private val variantChangedBefore15Json = """
            {
              "id": "3f6d2c1e-8b4a-4c6e-9a1b-000000000003", "productId": 7, "sequence": 3, "occurredOn": "2026-10-06T08:15:30Z",
              "occurredByUserId": 12, "variantId": 21, "ownPriceTag": null, "sortOrder": 1, "isDefault": true
            }
        """.trimIndent()

        @Test
        fun `events stored before step 1_5 still read back - the old strap and pockets text, no tags, no seo, no barcode`() {
            val created = DomainEventSerializer.deserializeProduct(productCreatedBefore15Json, 1, "ProductCreated") as ProductCreated
            val added = DomainEventSerializer.deserializeProduct(variantAddedBefore15Json, 1, "VariantAdded") as VariantAdded
            val changed = DomainEventSerializer.deserializeProduct(variantChangedBefore15Json, 1, "VariantChanged") as VariantChanged

            assertEquals(listOf(StrapInfo.legacy("بند دوشی ثابت ۵۵ سانتی‌متر")), created.details.specs.straps)
            assertEquals(PocketInfo.legacy("۱ جیب زیپ‌دار داخلی"), created.details.specs.pockets)
            assertEquals(ProductTags.NONE, created.details.tags)
            assertEquals(SeoMeta.NONE, created.details.seo)
            assertEquals(CareInstructions.NONE, created.details.specs.careInstructions)
            assertNull(added.barcode)
            assertEquals(emptyList(), added.media)
            assertNull(changed.barcode)
        }

        @Test
        fun `an old event written again takes the new shape - the text moves into straps and pocketInfo`() {
            val created = DomainEventSerializer.deserializeProduct(productCreatedBefore15Json, 1, "ProductCreated")
            val rewritten = Json.parseToJsonElement(DomainEventSerializer.serialize(created)).jsonObject["details"]!!.jsonObject["specs"]!!.jsonObject

            assertEquals(JsonNull, rewritten["strap"])
            assertEquals("بند دوشی ثابت ۵۵ سانتی‌متر", rewritten["straps"]!!.jsonArray.single().jsonObject["label"]!!.jsonPrimitive.content)
            assertEquals("۱ جیب زیپ‌دار داخلی", rewritten["pocketInfo"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        }

        @Test
        fun `a gallery item stored with an address and no file id is broken data, so it stops`() {
            val withAddress = variantAddedBefore15Json.replace(
                "\"media\": []",
                "\"media\": [{ \"type\": \"IMAGE\", \"url\": \"https://cdn.kiva.ir/p/7/front.webp\", \"view\": \"FRONT\", \"alt\": null }]",
            )
            check("cdn.kiva.ir" in withAddress)

            assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeProduct(withAddress, 1, "VariantAdded") }
        }

        @Test
        fun `a stored ProductCreated is read back`() {
            assertEquals(ProductEventSamples.productCreated, DomainEventSerializer.deserializeProduct(productCreatedJson, 1, "ProductCreated"))
        }

        @Test
        fun `a ProductCreated stored before reservable existed reads as reservable, as every product was then`() {
            val beforeReservable = productCreatedJson.lines().filterNot { "\"reservable\"" in it }.joinToString("\n")
            check("reservable" !in beforeReservable)

            val read = DomainEventSerializer.deserializeProduct(beforeReservable, 1, "ProductCreated") as ProductCreated

            assertEquals(ProductEventSamples.fullDetails.copy(reservable = true), read.details)
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
