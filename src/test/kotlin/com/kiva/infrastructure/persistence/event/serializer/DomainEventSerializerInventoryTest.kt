package com.kiva.infrastructure.persistence.event.serializer

import com.kiva.domain.aggregate.inventory.entity.event.InventoryDomainEvent
import com.kiva.infrastructure.persistence.event.InventoryEventSamples
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The stock events' half of DomainEventSerializer; DomainEventSerializerTest has the products'. */
class DomainEventSerializerInventoryTest {

    private fun roundTrip(event: InventoryDomainEvent): InventoryDomainEvent =
        DomainEventSerializer.deserializeInventory(DomainEventSerializer.serialize(event), event.version.value, event.type.value)

    @Nested
    inner class RoundTrip {
        @Test
        fun `every kind of stock event comes back exactly as it was, and an empty note stays empty`() {
            (InventoryEventSamples.onePerKind + InventoryEventSamples.nothingOptional).forEach { event ->
                assertEquals(event, roundTrip(event), "${event.type.value} changed on the way")
            }
        }

        @Test
        fun `the samples cover every kind of stock event, so a new kind can't skip the round trip`() {
            assertEquals(
                InventoryDomainEvent::class.sealedSubclasses.toSet(),
                InventoryEventSamples.onePerKind.map { it::class }.toSet(),
            )
        }

        @Test
        fun `no stored type is used twice, not even by a product event`() {
            val types = (InventoryEventSamples.onePerKind + ProductEventSamples.onePerKind).map { it.type.value }

            assertEquals(types.size, types.toSet().size, "repeated types: ${types.groupBy { it }.filterValues { it.size > 1 }.keys}")
        }
    }

    /** Stored shapes written out by hand: renaming or removing a payload field breaks them, which is the point. */
    @Nested
    inner class StoredShape {
        private val stockAdjustedJson = """
            {
              "id": "7a1c9e2b-5d3f-4b8a-8c6d-000000000001",
              "variantId": 21,
              "sequence": 1,
              "occurredOn": "2026-10-06T08:15:30.123456789Z",
              "occurredByUserId": 12,
              "delta": 12,
              "reason": "RESTOCK",
              "note": "بارنامه \"۲۳۴\" از کارگاه"
            }
        """.trimIndent()

        private val stockReservedJson = """
            {
              "id": "7a1c9e2b-5d3f-4b8a-8c6d-000000000002",
              "variantId": 21,
              "sequence": 2,
              "occurredOn": "2026-10-06T08:15:30.123456789Z",
              "occurredByUserId": 0,
              "orderCode": "KV-218340",
              "quantity": 2
            }
        """.trimIndent()

        @Test
        fun `stored stock events are read back`() {
            assertEquals(InventoryEventSamples.stockAdjusted, DomainEventSerializer.deserializeInventory(stockAdjustedJson, 1, "StockAdjusted"))
            assertEquals(InventoryEventSamples.stockReserved, DomainEventSerializer.deserializeInventory(stockReservedJson, 1, "StockReserved"))
        }

        @Test
        fun `stock events are written in that same shape`() {
            assertEquals(Json.parseToJsonElement(stockAdjustedJson), Json.parseToJsonElement(DomainEventSerializer.serialize(InventoryEventSamples.stockAdjusted)))
            assertEquals(Json.parseToJsonElement(stockReservedJson), Json.parseToJsonElement(DomainEventSerializer.serialize(InventoryEventSamples.stockReserved)))
        }
    }

    @Nested
    inner class UnreadableData {
        @Test
        fun `a product event is not a stock event, so reading it as one stops`() {
            val productCreated = DomainEventSerializer.serialize(ProductEventSamples.productCreated)

            val error = assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeInventory(productCreated, 1, "ProductCreated") }
            assertTrue(error.message!!.contains("Unknown inventory event type"))
        }

        @Test
        fun `a version nobody wrote a reader for stops`() {
            val stored = DomainEventSerializer.serialize(InventoryEventSamples.stockAdjusted)

            assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeInventory(stored, 2, "StockAdjusted") }
        }
    }
}
