package com.kiva.infrastructure.persistence.event.serializer

import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.infrastructure.persistence.event.InventoryEventSamples
import com.kiva.infrastructure.persistence.event.OrderEventSamples
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPlacedPayload
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The order events' part of DomainEventSerializer; the products' and the stock's have their own tests. */
class DomainEventSerializerOrderTest {

    private fun roundTrip(event: OrderDomainEvent): OrderDomainEvent =
        DomainEventSerializer.deserializeOrder(DomainEventSerializer.serialize(event), event.version.value, event.type.value)

    @Nested
    inner class RoundTrip {
        @Test
        fun `every kind of order event comes back exactly as it was, and the empty optional parts stay empty`() {
            (OrderEventSamples.onePerKind + OrderEventSamples.nothingOptional).forEach { event ->
                assertEquals(event, roundTrip(event), "${event.type.value} changed on the way")
            }
        }

        @Test
        fun `the samples cover every kind of order event, so a new kind can't skip the round trip`() {
            assertEquals(OrderDomainEvent::class.sealedSubclasses.toSet(), OrderEventSamples.onePerKind.map { it::class }.toSet())
        }

        @Test
        fun `no stored type is used twice, not even by a product or a stock event`() {
            val types = (OrderEventSamples.onePerKind + ProductEventSamples.onePerKind + InventoryEventSamples.onePerKind).map { it.type.value }

            assertEquals(types.size, types.toSet().size, "repeated types: ${types.groupBy { it }.filterValues { it.size > 1 }.keys}")
        }

        @Test
        fun `printing a stored order shows neither the address nor a phone`() {
            val printed = "${OrderEventSamples.orderPlaced} ${OrderPlacedPayload(OrderEventSamples.orderPlaced)}"

            listOf("ولیعصر", "0912", "1965843117", "@sara_m", "زنگ").forEach { assertFalse(it in printed, "$it in $printed") }
        }
    }

    /** Stored shapes written out by hand: renaming or removing a payload field breaks them, which is the point. */
    @Nested
    inner class StoredShape {
        private val orderPlacedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000001",
              "orderId": 42,
              "sequence": 1,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 7,
              "code": "KV-218340",
              "userId": 7,
              "lines": [
                {
                  "lineId": 1, "productId": 3, "variantId": 301,
                  "name": "کیف دستی آیدا", "slug": "aida-handbag", "sku": "KV-1003-CARAMEL",
                  "colorKey": "caramel", "colorName": "کاراملی", "colorHex": "#C68B59",
                  "imageUrl": "https://cdn.kiva.ir/p/aida/caramel-1.jpg",
                  "quantity": 1,
                  "priceTag": { "price": 2790000, "compareAtPrice": 3290000 },
                  "reservable": true, "returnable": true
                },
                {
                  "lineId": 2, "productId": 15, "variantId": 1501,
                  "name": "کیف پول KV-1015", "slug": "kv-1015-wallet", "sku": "KV-1015-BROWN",
                  "colorKey": "brown", "colorName": "شکلاتی", "colorHex": "#7B4A2D",
                  "imageUrl": null,
                  "quantity": 1,
                  "priceTag": { "price": 690000, "compareAtPrice": 790000 },
                  "reservable": false, "returnable": false
                }
              ],
              "address": {
                "addressId": 42, "provinceName": "تهران", "cityName": "تهران", "addressLine": "خیابان ولیعصر، کوچه نسترن",
                "plaque": "12", "unit": "4", "postalCode": "1965843117", "recipientName": "سارا محمدی", "recipientPhone": "09121234567"
              },
              "messenger": { "channel": "TELEGRAM", "phone": "09121234567", "username": "@sara_m", "note": "از داخل کیف هم عکس بگیرید" },
              "shippingMethod": "POST",
              "totals": {
                "itemsCount": 2, "linesCount": 2, "itemsCompareAtTotal": 4080000, "productDiscount": 600000, "subtotal": 3480000,
                "codeDiscount": 0, "shippingCost": 0, "shippingIsFree": true, "shippingFreeReason": "THRESHOLD", "payable": 3480000
              },
              "reserveRequested": false,
              "holdUntil": "2026-10-12T08:15:00.123456789Z",
              "customerNote": "لطفاً قبل از ارسال زنگ بزنید",
              "gift": { "message": "تولدت مبارک!", "hidePrices": true },
              "reservationGroupId": 1001
            }
        """.trimIndent()

        private val orderPaymentFailedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000002",
              "orderId": 42,
              "sequence": 2,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 0,
              "paymentId": "pay_0000000000A1",
              "reason": "USER_CANCELLED"
            }
        """.trimIndent()

        private val orderPaidJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000003",
              "orderId": 42,
              "sequence": 3,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 0,
              "paymentId": "pay_0000000000B2",
              "amount": 3480000,
              "heldForReservation": true,
              "reservationGroupId": 1001
            }
        """.trimIndent()

        private val orderExpiredJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000004",
              "orderId": 42,
              "sequence": 4,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 0
            }
        """.trimIndent()

        private val orderReleasedFromReservationJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000005",
              "orderId": 42,
              "sequence": 4,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 0
            }
        """.trimIndent()

        private val stored = listOf(
            OrderEventSamples.orderPlaced to orderPlacedJson,
            OrderEventSamples.orderPaymentFailed to orderPaymentFailedJson,
            OrderEventSamples.orderPaid to orderPaidJson,
            OrderEventSamples.orderExpired to orderExpiredJson,
            OrderEventSamples.orderReleasedFromReservation to orderReleasedFromReservationJson,
        )

        @Test
        fun `stored order events are read back`() {
            stored.forEach { (event, json) ->
                assertEquals(event, DomainEventSerializer.deserializeOrder(json, 1, event.type.value), event.type.value)
            }
        }

        @Test
        fun `order events are written in that same shape`() {
            stored.forEach { (event, json) ->
                assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(DomainEventSerializer.serialize(event)), event.type.value)
            }
        }

        @Test
        fun `an order placed and paid before step 19 has no reservationGroupId, and reads back with none`() {
            val placedBefore = orderPlacedJson.replace(",\n  \"reservationGroupId\": 1001", "")
            val paidBefore = orderPaidJson.replace(",\n  \"reservationGroupId\": 1001", "").replace("\"heldForReservation\": true", "\"heldForReservation\": false")
            check("reservationGroupId" !in placedBefore + paidBefore)

            assertEquals(
                OrderEventSamples.orderPlaced.copy(reservationGroupId = null),
                DomainEventSerializer.deserializeOrder(placedBefore, 1, "OrderPlaced"),
            )
            assertEquals(
                OrderEventSamples.orderPaid.copy(heldForReservation = false, reservationGroupId = null),
                DomainEventSerializer.deserializeOrder(paidBefore, 1, "OrderPaid"),
            )
        }

        @Test
        fun `there is a stored shape for every kind of order event`() {
            assertEquals(OrderDomainEvent::class.sealedSubclasses.toSet(), stored.map { it.first::class }.toSet())
        }
    }

    @Nested
    inner class UnreadableData {
        @Test
        fun `a stock event is not an order event, so reading it as one stops`() {
            val stockReserved = DomainEventSerializer.serialize(InventoryEventSamples.stockReserved)

            val error = assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeOrder(stockReserved, 1, "StockReserved") }
            assertTrue(error.message!!.contains("Unknown order event type"))
        }

        @Test
        fun `a version nobody wrote a reader for stops`() {
            val stored = DomainEventSerializer.serialize(OrderEventSamples.orderPaid)

            assertFailsWith<IllegalStateException> { DomainEventSerializer.deserializeOrder(stored, 2, "OrderPaid") }
        }

        @Test
        fun `stored totals that don't add up are a bug, not an order`() {
            val broken = DomainEventSerializer.serialize(OrderEventSamples.orderPlaced).replace("\"payable\":3480000", "\"payable\":3470000")

            assertFailsWith<IllegalArgumentException> { DomainEventSerializer.deserializeOrder(broken, 1, "OrderPlaced") }
        }
    }
}
