package com.kiva.infrastructure.persistence.event.serializer

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.infrastructure.persistence.event.InventoryEventSamples
import com.kiva.infrastructure.persistence.event.OrderEventSamples
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderCancelledPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderChangeAppliedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderChangeRejectedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderChangeRequestedPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderDeliveredPayload
import com.kiva.infrastructure.persistence.event.serializer.payload.order.OrderPhotosSentPayload
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

        @Test
        fun `printing a cancellation, the photos sent or a delivery shows none of the notes nor the operator's message`() {
            val printed = listOf(
                OrderEventSamples.orderCancelled, OrderCancelledPayload(OrderEventSamples.orderCancelled),
                OrderEventSamples.orderPhotosSent, OrderPhotosSentPayload(OrderEventSamples.orderPhotosSent),
                OrderEventSamples.orderDelivered, OrderDeliveredPayload(OrderEventSamples.orderDelivered),
            ).joinToString(" ")

            listOf("ارزون", "این کیف", "پیک").forEach { assertFalse(it in printed, "$it in $printed") }
        }

        @Test
        fun `printing a change request, its rejection or its application shows none of the notes`() {
            val printed = listOf(
                OrderEventSamples.orderChangeRequested, OrderChangeRequestedPayload(OrderEventSamples.orderChangeRequested),
                OrderEventSamples.orderChangeRejected, OrderChangeRejectedPayload(OrderEventSamples.orderChangeRejected),
                OrderEventSamples.orderChangeApplied, OrderChangeAppliedPayload(OrderEventSamples.orderChangeApplied),
            ).joinToString(" ")

            listOf("مشکی‌اش", "تموم شده", "گذاشتیم").forEach { assertFalse(it in printed, "$it in $printed") }
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
                "codeDiscount": 0, "shippingCost": 0, "shippingIsFree": true, "shippingFreeReason": "THRESHOLD", "payable": 3480000,
                "discountCode": null
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

        private val orderPhotosAttachedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000006",
              "orderId": 42,
              "sequence": 5,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "items": [
                { "mediaId": "med_0000000000000000000001", "lineId": 1, "view": "FRONT", "sortOrder": 0 },
                { "mediaId": "med_0000000000000000000002", "lineId": null, "view": null, "sortOrder": 1 }
              ]
            }
        """.trimIndent()

        private val orderPhotosSentJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000007",
              "orderId": 42,
              "sequence": 6,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "batchNo": 1,
              "channel": "BALE",
              "manual": true,
              "message": "این کیف خودته",
              "feedbackDeadline": "2026-10-13T08:00:00.123456789Z"
            }
        """.trimIndent()

        private val orderPhotosApprovedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000008",
              "orderId": 42,
              "sequence": 7,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 0,
              "automatic": true
            }
        """.trimIndent()

        private val orderShippedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000009",
              "orderId": 42,
              "sequence": 8,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "carrier": "POST",
              "trackingCode": "183920674512039845612307",
              "shippedOn": "2026-10-13T06:30:00Z",
              "showInDailyList": true,
              "reservationGroupId": 1001
            }
        """.trimIndent()

        private val orderShipmentCorrectedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000010",
              "orderId": 42,
              "sequence": 9,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "trackingCode": "183920674512039845612308",
              "shippedOn": "2026-10-13T06:00:00Z",
              "showInDailyList": false
            }
        """.trimIndent()

        private val orderDeliveredJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000014",
              "orderId": 42,
              "sequence": 10,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "returnableUntil": "2026-10-19T08:00:00.123456789Z",
              "note": "با پیک رسید"
            }
        """.trimIndent()

        private val orderCancelledJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000015",
              "orderId": 42,
              "sequence": 5,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 7,
              "by": "CUSTOMER",
              "reason": "FOUND_CHEAPER",
              "note": "ارزون‌ترش رو پیدا کردم"
            }
        """.trimIndent()

        private val orderRefundedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000016",
              "orderId": 42,
              "sequence": 6,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "refundId": 31,
              "amount": 3480000
            }
        """.trimIndent()

        private val orderChangeRequestedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000021",
              "orderId": 42,
              "sequence": 8,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 7,
              "changeType": "COLOR",
              "lineId": 1,
              "desiredVariantId": 302,
              "note": "می‌شه رنگ مشکی‌اش رو بفرستید؟"
            }
        """.trimIndent()

        private val orderChangeRejectedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000022",
              "orderId": 42,
              "sequence": 9,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "note": "مشکی تموم شده"
            }
        """.trimIndent()

        private val orderLineReplacedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000023",
              "orderId": 42,
              "sequence": 9,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "lineId": 1,
              "line": {
                "lineId": 1, "productId": 3, "variantId": 302,
                "name": "کیف دستی آیدا", "slug": "aida-handbag", "sku": "KV-1003-BLACK",
                "colorKey": "black", "colorName": "مشکی", "colorHex": "#1B1B1B",
                "imageUrl": "2026/10/med_0000000000000000000009/480.webp",
                "quantity": 1,
                "priceTag": { "price": 2990000, "compareAtPrice": 3290000 },
                "reservable": true, "returnable": true
              },
              "priceDifference": 200000
            }
        """.trimIndent()

        private val orderLineRemovedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000024",
              "orderId": 42,
              "sequence": 9,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "lineId": 2,
              "paidShare": 621000
            }
        """.trimIndent()

        private val orderChangeAppliedJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000025",
              "orderId": 42,
              "sequence": 10,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 900,
              "changeType": "COLOR",
              "variantId": 302,
              "note": "مشکیش رو گذاشتیم",
              "balanceDueAfter": 200000
            }
        """.trimIndent()

        private val orderBalancePaidJson = """
            {
              "id": "5d0f3a2c-8b1e-4c7d-9a6f-000000000026",
              "orderId": 42,
              "sequence": 11,
              "occurredOn": "2026-10-12T08:00:00.123456789Z",
              "occurredByUserId": 7,
              "paymentId": "pay_0000000000C3",
              "amount": 200000
            }
        """.trimIndent()

        private val stored = listOf(
            OrderEventSamples.orderPlaced to orderPlacedJson,
            OrderEventSamples.orderPaymentFailed to orderPaymentFailedJson,
            OrderEventSamples.orderPaid to orderPaidJson,
            OrderEventSamples.orderExpired to orderExpiredJson,
            OrderEventSamples.orderReleasedFromReservation to orderReleasedFromReservationJson,
            OrderEventSamples.orderPhotosAttached to orderPhotosAttachedJson,
            OrderEventSamples.orderPhotosSent to orderPhotosSentJson,
            OrderEventSamples.orderPhotosApproved to orderPhotosApprovedJson,
            OrderEventSamples.orderShipped to orderShippedJson,
            OrderEventSamples.orderShipmentCorrected to orderShipmentCorrectedJson,
            OrderEventSamples.orderDelivered to orderDeliveredJson,
            OrderEventSamples.orderCancelled to orderCancelledJson,
            OrderEventSamples.orderRefunded to orderRefundedJson,
            OrderEventSamples.orderChangeRequested to orderChangeRequestedJson,
            OrderEventSamples.orderChangeRejected to orderChangeRejectedJson,
            OrderEventSamples.orderLineReplaced to orderLineReplacedJson,
            OrderEventSamples.orderLineRemoved to orderLineRemovedJson,
            OrderEventSamples.orderChangeApplied to orderChangeAppliedJson,
            OrderEventSamples.orderBalancePaid to orderBalancePaidJson,
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
        fun `totals stored before step 20 have no discountCode and read back with none, and a code's totals come back with it`() {
            val placedBefore = orderPlacedJson.replace(",\n    \"discountCode\": null", "")
            check("discountCode" !in placedBefore)
            assertEquals(OrderEventSamples.orderPlaced, DomainEventSerializer.deserializeOrder(placedBefore, 1, "OrderPlaced"))

            val withCode = OrderEventSamples.orderPlaced.copy(
                totals = OrderTotals.constructFromPersisted(
                    2, 2, Money.constructFromPersisted(4_080_000), Money.constructFromPersisted(600_000), Money.constructFromPersisted(3_480_000),
                    Money.constructFromPersisted(348_000), Money.ZERO, true, ShippingFreeReason.THRESHOLD, Money.constructFromPersisted(3_132_000),
                    DiscountCodeText.constructFromPersisted("KIVA10"),
                ),
            )
            val json = DomainEventSerializer.serialize(withCode)
            assertTrue("\"discountCode\":\"KIVA10\"" in json.replace(" ", ""), json)
            assertEquals(withCode, DomainEventSerializer.deserializeOrder(json, 1, "OrderPlaced"))
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
