package com.kiva.domain.aggregate.order

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.cart.CartFixtures
import com.kiva.domain.aggregate.cart.CartFixtures.itemId
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.order.OrderDomainService.PlaceOrderFailure
import com.kiva.domain.aggregate.order.OrderFixtures.SequentialOrderIds
import com.kiva.domain.aggregate.order.OrderFixtures.aidaCaramel
import com.kiva.domain.aggregate.order.OrderFixtures.at
import com.kiva.domain.aggregate.order.OrderFixtures.item
import com.kiva.domain.aggregate.order.OrderFixtures.kv1015Brown
import com.kiva.domain.aggregate.order.OrderFixtures.line
import com.kiva.domain.aggregate.order.OrderFixtures.nilaBlack
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.OrderFixtures.placement
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.model.OrderPlacement
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PENDING_PAYMENT
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PROCESSING
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.reservation.ReservationFixtures
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.COURIER
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.TIPAX
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrderDomainServiceTest {

    private val ids = SequentialOrderIds()
    private val service = OrderDomainService(ids)

    private fun place(placement: OrderPlacement) = service.placeOrder(placement, now)

    private fun toman(amount: Long) = Money.constructFromPersisted(amount)

    @Test
    fun `the first golden order - Aida caramel and KV-1015 by post, 3,480,000, ships free, code KV-218340`() {
        val note = CustomerNote.constructFromPersisted("لطفاً زنگ نزنید")
        val gift = GiftNote.constructFromPersisted("تولدت مبارک!", hidePrices = true)

        val order = place(placement(customerNote = note, gift = gift, expectedPayable = 3_480_000)).getOrNull()!!

        assertEquals("KV-218340", order.code.value)
        assertEquals(1L, order.id.value)
        assertEquals(PENDING_PAYMENT, order.status)
        assertEquals(toman(3_480_000), order.totals.payable)
        assertEquals(toman(4_080_000), order.totals.itemsCompareAtTotal)
        assertEquals(toman(600_000), order.totals.productDiscount)
        assertEquals(ShippingFreeReason.THRESHOLD, order.totals.shippingFreeReason)
        assertEquals(now.plus(Duration.ofMinutes(15)), order.holdUntil)
        assertEquals(note, order.customerNote)
        assertEquals(gift, order.gift)

        val (caramel, brown) = order.lines
        assertEquals(listOf(1, 2), order.lines.map { it.lineId })
        assertEquals(aidaCaramel.priceTag, caramel.priceTag)
        assertEquals(aidaCaramel.productId, caramel.productId)
        assertEquals("KV-1003-CARAMEL", caramel.item.sku.value)
        assertEquals("KV-1015-BROWN", brown.item.sku.value)
        assertEquals(listOf(true, false), order.lines.map { it.returnable })
        assertEquals(sara, assertIs<OrderPlaced>(order.getRecordedEvents().single()).occurredByUserId)
    }

    @Test
    fun `Nila black by post costs 1,525,000 - shipping isn't free under the threshold`() {
        val order = place(placement(lines = listOf(line(1, nilaBlack)))).getOrNull()!!

        assertEquals(toman(1_525_000), order.totals.payable)
        assertEquals(toman(75_000), order.totals.shippingCost)
    }

    @Test
    fun `tipax is never free - 3,625,000`() {
        assertEquals(toman(3_625_000), place(placement(shippingMethod = TIPAX)).getOrNull()!!.totals.payable)
    }

    @Test
    fun `the payable the customer saw must be the payable now - else 409 PRICE_CHANGED with it, and no order id is used`() {
        val refused = place(placement(expectedPayable = 3_290_000)).failureOrNull()

        assertEquals(PlaceOrderFailure.PriceChanged(toman(3_480_000)), refused)
        assertEquals("PRICE_CHANGED", refused?.code)
        assertEquals(mapOf("payable" to 3_480_000L), refused?.meta)
        assertEquals(0, ids.handedOut)
    }

    @Test
    fun `without the payable the customer saw, nothing is compared`() {
        assertTrue(place(placement(expectedPayable = null)).isSuccess)
    }

    @Test
    fun `the rules come one at a time, in the contract's order`() {
        val heldOrders = listOf(at(5), at(10), at(14))
        val gone = line(1, aidaCaramel.copy(inShop = false))
        var wrong = placement(
            lines = listOf(gone, line(2, kv1015Brown)),
            shippingMethod = COURIER,
            gatewayAvailable = false,
            acceptTerms = false,
            reserve = true,
            unpaidOrdersHoldingUntil = heldOrders,
            expectedPayable = 1,
        )

        val seen = mutableListOf<String>()
        while (true) {
            val failure = place(wrong).failureOrNull() ?: break
            seen += failure.code
            wrong = when (failure) {
                PlaceOrderFailure.ShippingMethodUnavailable -> wrong.copy(shippingMethod = TIPAX)
                PlaceOrderFailure.GatewayUnavailable -> wrong.copy(gatewayAvailable = true)
                PlaceOrderFailure.TermsNotAccepted -> wrong.copy(acceptTerms = true)
                is PlaceOrderFailure.ReservationUnavailable -> wrong.copy(reserve = false)
                is PlaceOrderFailure.TooManyUnpaidOrders -> wrong.copy(unpaidOrdersHoldingUntil = heldOrders.drop(1))
                is PlaceOrderFailure.ProductUnavailable -> wrong.copy(lines = listOf(line(1, aidaCaramel), line(2, kv1015Brown)))
                is PlaceOrderFailure.PriceChanged -> wrong.copy(expectedPayable = failure.payable)
                else -> error("unexpected $failure")
            }
        }

        assertEquals(
            listOf(
                "SHIPPING_METHOD_UNAVAILABLE", "PAYMENT_GATEWAY_UNAVAILABLE", "TERMS_NOT_ACCEPTED", "RESERVATION_UNAVAILABLE", "RATE_LIMITED",
                "PRODUCT_UNAVAILABLE", "PRICE_CHANGED",
            ),
            seen,
        )
        assertEquals(1, ids.handedOut)
    }

    @Test
    fun `an order to the held reservation's address joins it - its method only, shipping free, its own «رزرو» ignored`() {
        val group = ReservationFixtures.group()
        val cheap = listOf(line(1, nilaBlack))
        val joining = placement(lines = cheap, reserve = true, settings = PricingFixtures.shopSettings(reservationEnabled = true))
            .copy(holdingGroup = group)
        val inTime = now.plus(Duration.ofDays(1))

        val order = service.placeOrder(joining, inTime).getOrNull()!!

        assertEquals(group.id, order.reservationGroupId)
        assertFalse(order.reserveRequested)
        assertEquals(toman(0), order.totals.shippingCost)
        assertEquals(ShippingFreeReason.RESERVATION_CONSOLIDATION, order.totals.shippingFreeReason)
        assertEquals(toman(1_450_000), order.totals.payable)
        assertEquals(PlaceOrderFailure.ShippingMethodUnavailable, service.placeOrder(joining.copy(shippingMethod = TIPAX), inTime).failureOrNull())
        assertEquals(
            assertIs<OrderPlaced>(order.getRecordedEvents().single()).reservationGroupId,
            group.id,
        )
    }

    @Test
    fun `another address, the group's address edited, or the group's time over - no joining, shipping as usual`() {
        val group = ReservationFixtures.group()
        val cheap = listOf(line(1, nilaBlack))
        val inTime = now.plus(Duration.ofDays(1))
        val placements = listOf(
            placement(lines = cheap).copy(holdingGroup = group, address = ReservationFixtures.address(addressId = 43)) to inTime,
            placement(lines = cheap).copy(holdingGroup = group, address = ReservationFixtures.address(recipientName = "مریم محمدی")) to inTime,
            placement(lines = cheap).copy(holdingGroup = group) to now.plus(Duration.ofDays(4)),
        )

        placements.forEach { (placement, at) ->
            val order = service.placeOrder(placement, at).getOrNull()!!
            assertEquals(null, order.reservationGroupId)
            assertTrue(order.totals.shippingCost > toman(0))
        }
    }

    @Test
    fun `while a group holds, a new «رزرو» elsewhere is HAS_ACTIVE_RESERVATION - once its time is over it can be asked for`() {
        val group = ReservationFixtures.group()
        val elsewhere = placement(reserve = true, settings = PricingFixtures.shopSettings(reservationEnabled = true))
            .copy(holdingGroup = group, address = ReservationFixtures.address(addressId = 43))

        val refused = service.placeOrder(elsewhere, now.plus(Duration.ofDays(1))).failureOrNull()

        assertEquals(PlaceOrderFailure.ReservationUnavailable(ReservationUnavailableReason.HAS_ACTIVE_RESERVATION), refused)
        assertTrue(service.placeOrder(elsewhere, now.plus(Duration.ofDays(4))).getOrNull()!!.reserveRequested)
    }

    @Test
    fun `an empty cart is refused before anything`() {
        assertEquals(PlaceOrderFailure.CartEmpty, place(placement(lines = emptyList(), shippingMethod = COURIER)).failureOrNull())
    }

    @Test
    fun `with nothing to pay the gateway doesn't matter, and the order is paid at once`() {
        val free = line(1, CartFixtures.offer(901, productId = 9, price = 0))

        val order = place(placement(lines = listOf(free), gatewayAvailable = false, settings = PricingFixtures.shopSettings(freeShippingThreshold = 0)))

        assertEquals(PROCESSING, order.getOrNull()?.status)
    }

    @Test
    fun `a reservation that isn't possible says why - off in the settings, or a line that can't be held`() {
        val off = place(placement(reserve = true)).failureOrNull()
        val notHeld = place(
            placement(
                lines = listOf(line(1, aidaCaramel.copy(reservable = false))),
                reserve = true,
                settings = PricingFixtures.shopSettings(reservationEnabled = true),
            ),
        ).failureOrNull()

        assertEquals(PlaceOrderFailure.ReservationUnavailable(ReservationUnavailableReason.RESERVATION_DISABLED), off)
        assertEquals(mapOf("reason" to "ITEM_NOT_RESERVABLE"), notHeld?.meta)
    }

    @Test
    fun `three unpaid orders holding stock is 429, until the earliest stops holding it`() {
        val refused = place(placement(unpaidOrdersHoldingUntil = listOf(at(14), at(9), at(12)))).failureOrNull()

        assertIs<PlaceOrderFailure.TooManyUnpaidOrders>(refused)
        assertEquals("RATE_LIMITED", refused.code)
        assertEquals(mapOf("retryAfterSeconds" to 9 * 60L), refused.meta)
        assertTrue(place(placement(unpaidOrdersHoldingUntil = listOf(at(14), at(9)))).isSuccess)
    }

    @Test
    fun `the lines - out of the shop first, then not enough left, then over the limit, each naming the cart's lines`() {
        val outOfShop = place(placement(lines = listOf(line(1, aidaCaramel.copy(inShop = false)), line(2, nilaBlack.copy(available = 0))))).failureOrNull()
        val short = place(placement(lines = listOf(line(1, aidaCaramel, quantity = 4), line(2, nilaBlack.copy(available = 0)), line(3, kv1015Brown)))).failureOrNull()
        val overLimit = place(
            placement(
                lines = listOf(
                    line(1, aidaCaramel, quantity = 3),
                    line(2, CartFixtures.aida(CartFixtures.AIDA_BLACK), quantity = 3, item = item("KV-1003-BLACK", "black", "مشکی")),
                    line(3, kv1015Brown),
                ),
            ),
        ).failureOrNull()

        assertEquals(PlaceOrderFailure.ProductUnavailable(listOf(itemId(1))), outOfShop)
        assertEquals(PlaceOrderFailure.OutOfStock(listOf(itemId(1), itemId(2))), short)
        assertEquals(mapOf("itemIds" to listOf(itemId(1).value, itemId(2).value)), short?.meta)
        assertEquals(PlaceOrderFailure.QuantityExceedsLimit(listOf(itemId(1), itemId(2))), overLimit)
        assertEquals(0, ids.handedOut)
    }

    @Test
    fun `every order gets the next id and its own code`() {
        val first = place(placement()).getOrNull()!!
        val second = place(placement()).getOrNull()!!

        assertEquals(listOf(1L, 2L), listOf(first.id.value, second.id.value))
        assertEquals(listOf("KV-218340", "KV-774583"), listOf(first.code.value, second.code.value))
    }

    @Test
    fun `a placement prints no address, phone or note`() {
        val printed = "${placement(customerNote = CustomerNote.constructFromPersisted("پشت در بذارید"))}"

        listOf("ولیعصر", "0912", "پشت در").forEach { assertFalse(it in printed, printed) }
    }
}
