package com.kiva.domain.aggregate.cart.entity

import com.kiva.common.railway.FailureKind
import com.kiva.domain.aggregate.cart.CartFixtures.AIDA_BLACK
import com.kiva.domain.aggregate.cart.CartFixtures.AIDA_CARAMEL
import com.kiva.domain.aggregate.cart.CartFixtures.AIDA_RED
import com.kiva.domain.aggregate.cart.CartFixtures.NILA_BLACK
import com.kiva.domain.aggregate.cart.CartFixtures.NILA_OLIVE
import com.kiva.domain.aggregate.cart.CartFixtures.aida
import com.kiva.domain.aggregate.cart.CartFixtures.at
import com.kiva.domain.aggregate.cart.CartFixtures.guestCart
import com.kiva.domain.aggregate.cart.CartFixtures.itemId
import com.kiva.domain.aggregate.cart.CartFixtures.line
import com.kiva.domain.aggregate.cart.CartFixtures.nila
import com.kiva.domain.aggregate.cart.CartFixtures.now
import com.kiva.domain.aggregate.cart.CartFixtures.offers
import com.kiva.domain.aggregate.cart.CartFixtures.quantity
import com.kiva.domain.aggregate.cart.CartFixtures.toman
import com.kiva.domain.aggregate.cart.CartFixtures.userCart
import com.kiva.domain.aggregate.cart.CartFixtures.variant
import com.kiva.domain.aggregate.cart.model.CartIssue
import com.kiva.domain.aggregate.cart.model.CartIssueCode
import com.kiva.domain.aggregate.cart.model.CartIssueCode.OUT_OF_STOCK
import com.kiva.domain.aggregate.cart.model.CartIssueCode.PRICE_DECREASED
import com.kiva.domain.aggregate.cart.model.CartIssueCode.PRICE_INCREASED
import com.kiva.domain.aggregate.cart.model.CartIssueCode.QUANTITY_REDUCED
import com.kiva.domain.aggregate.cart.model.CartIssueCode.SHIPPING_METHOD_CHANGED
import com.kiva.domain.aggregate.cart.model.CartIssueCode.VARIANT_UNAVAILABLE
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.order.PricingFixtures.post
import com.kiva.domain.aggregate.order.PricingFixtures.shopSettings
import com.kiva.domain.aggregate.order.PricingFixtures.tipax
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.COURIER
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.TIPAX
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CartTest {

    private val settings = shopSettings()
    private val reservationsOn = shopSettings(reservationEnabled = true)

    private fun Cart.quantities(): Map<Long, Int> = lines.associate { it.variantId.value to it.quantity.value }

    private fun Cart.codes(): List<CartIssueCode> = issues.map { it.code }

    private fun reduced(itemId: Int, variantId: Long, previous: Int, current: Int) =
        CartIssue(QUANTITY_REDUCED, itemId(itemId), variant(variantId), previous.toLong(), current.toLong())

    @Nested
    inner class Add {
        @Test
        fun `adding a color twice makes one line with both quantities`() {
            val cart = userCart()

            cart.add(itemId(1), variant(NILA_BLACK), quantity(1), offers(), now)
            val second = cart.add(itemId(2), variant(NILA_BLACK), quantity(2), offers(), at(1))

            assertEquals(itemId(1), second.getOrNull())
            assertEquals(listOf(itemId(1)), cart.lines.map { it.itemId })
            assertEquals(mapOf(NILA_BLACK to 3), cart.quantities())
            assertTrue(cart.issues.isEmpty())
        }

        @Test
        fun `the limit of 5 is shared by Aida's colors - 3 caramel, then 3 black is cut to 2, then red is refused`() {
            val cart = userCart()

            cart.add(itemId(1), variant(AIDA_CARAMEL), quantity(3), offers(), now)
            cart.add(itemId(2), variant(AIDA_BLACK), quantity(3), offers(), at(1))
            val red = cart.add(itemId(3), variant(AIDA_RED), quantity(1), offers(), at(2))

            assertEquals(mapOf(AIDA_CARAMEL to 3, AIDA_BLACK to 2), cart.quantities())
            assertEquals(listOf(reduced(2, AIDA_BLACK, 3, 2)), cart.issues)
            val failure = red.failureOrNull()
            assertEquals("QUANTITY_EXCEEDS_LIMIT", failure?.code)
            assertEquals(FailureKind.BUSINESS_RULE, failure?.kind)
            assertEquals(mapOf<String, Any>("maxPerOrder" to 5), failure?.meta)
        }

        @Test
        fun `more than is left is cut to what is left`() {
            val cart = userCart()

            cart.add(itemId(1), variant(AIDA_BLACK), quantity(5), offers(), now)

            assertEquals(mapOf(AIDA_BLACK to 3), cart.quantities())
            assertEquals(listOf(reduced(1, AIDA_BLACK, 5, 3)), cart.issues)
        }

        @Test
        fun `a line never holds more than 20 - 15 and then 10 stops at 20`() {
            val roomy = offers(nila(NILA_BLACK, available = 50, maxPerOrder = 30))
            val cart = userCart()

            cart.add(itemId(1), variant(NILA_BLACK), quantity(15), roomy, now)
            cart.add(itemId(2), variant(NILA_BLACK), quantity(10), roomy, now)

            assertEquals(mapOf(NILA_BLACK to 20), cart.quantities())
            assertEquals(listOf(reduced(1, NILA_BLACK, 25, 20)), cart.issues)
        }

        @Test
        fun `a new line is seen at today's price, as something that can be bought`() {
            val cart = userCart()

            cart.add(itemId(1), variant(NILA_BLACK), quantity(1), offers(), at(3))

            assertEquals(line(itemId(1), NILA_BLACK, 1, 1_450_000, seenPurchasable = true, addedOn = at(3)), cart.lines.single())
            assertEquals(toman(1_450_000), cart.lines.single().seenUnitPrice)
            assertEquals(at(3), cart.updatedOn)
        }

        @Test
        fun `nothing left, out of the shop or unknown is refused, and nothing is added`() {
            val cart = userCart()

            assertEquals("OUT_OF_STOCK", cart.add(itemId(1), variant(AIDA_BLACK), quantity(1), offers(aida(AIDA_BLACK, available = 0)), now).failureOrNull()?.code)
            assertEquals("PRODUCT_UNAVAILABLE", cart.add(itemId(1), variant(AIDA_BLACK), quantity(1), offers(aida(AIDA_BLACK, inShop = false)), now).failureOrNull()?.code)
            val unknown = cart.add(itemId(1), variant(999), quantity(1), offers(), now).failureOrNull()
            assertEquals("VARIANT_NOT_FOUND", unknown?.code)
            assertEquals(FailureKind.NOT_FOUND, unknown?.kind)
            assertTrue(cart.lines.isEmpty())
        }
    }

    @Nested
    inner class ChangeQuantity {
        @Test
        fun `sets the line's quantity`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            assertEquals(itemId(1), cart.changeQuantity(itemId(1), quantity(4), offers(), now).getOrNull())
            assertEquals(mapOf(NILA_BLACK to 4), cart.quantities())
        }

        @Test
        fun `more than is left is refused, telling the line's maxQuantity, not the stock`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            val failure = cart.changeQuantity(itemId(1), quantity(5), offers(nila(NILA_BLACK, available = 4)), now).failureOrNull()

            assertEquals("QUANTITY_EXCEEDS_STOCK", failure?.code)
            assertEquals(mapOf<String, Any>("availableQuantity" to 4), failure?.meta)
            assertEquals(mapOf(NILA_BLACK to 1), cart.quantities())
        }

        @Test
        fun `with plenty left, the product's limit refuses it and tells the room left`() {
            val cart = userCart(line(itemId(1), NILA_OLIVE, 3, 1_450_000, addedOn = at(0)), line(itemId(2), NILA_BLACK, 1, 1_450_000, addedOn = at(1)))

            val failure = cart.changeQuantity(itemId(2), quantity(3), offers(), now).failureOrNull()

            assertEquals("QUANTITY_EXCEEDS_LIMIT", failure?.code)
            assertEquals(mapOf<String, Any>("maxQuantity" to 2), failure?.meta)
            assertEquals("QUANTITY_EXCEEDS_LIMIT", cart.changeQuantity(itemId(1), quantity(6), offers(), now).failureOrNull()?.code)
        }

        @Test
        fun `a line that isn't there, or can't be bought, can't be changed`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000))

            assertEquals("CART_ITEM_NOT_FOUND", cart.changeQuantity(itemId(9), quantity(2), offers(), now).failureOrNull()?.code)
            assertEquals("OUT_OF_STOCK", cart.changeQuantity(itemId(1), quantity(2), offers(aida(AIDA_BLACK, available = 0)), now).failureOrNull()?.code)
            assertEquals("PRODUCT_UNAVAILABLE", cart.changeQuantity(itemId(1), quantity(2), offers(aida(AIDA_BLACK, inShop = false)), now).failureOrNull()?.code)
        }

        @Test
        fun `the same quantity again changes nothing`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 2, 2_790_000))

            assertTrue(cart.changeQuantity(itemId(1), quantity(2), offers(aida(AIDA_BLACK, available = 0)), at(5)).isSuccess)
            assertEquals(now, cart.updatedOn)
        }
    }

    @Nested
    inner class ChangeColor {
        @Test
        fun `moving to a color that has a line makes them one, and that line's id remains`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000, addedOn = at(0)), line(itemId(2), NILA_OLIVE, 2, 1_450_000, addedOn = at(1)))

            val remaining = cart.changeColor(itemId(1), variant(NILA_OLIVE), offers(), now)

            assertEquals(itemId(2), remaining.getOrNull())
            assertEquals(listOf(itemId(2)), cart.lines.map { it.itemId })
            assertEquals(mapOf(NILA_OLIVE to 3), cart.quantities())
            assertTrue(cart.issues.isEmpty())
        }

        @Test
        fun `merged lines are cut to what the color has left`() {
            val cart = userCart(line(itemId(1), AIDA_CARAMEL, 2, 2_790_000, addedOn = at(0)), line(itemId(2), AIDA_BLACK, 2, 2_790_000, addedOn = at(1)))

            cart.changeColor(itemId(1), variant(AIDA_BLACK), offers(), now)

            assertEquals(mapOf(AIDA_BLACK to 3), cart.quantities())
            assertEquals(listOf(reduced(2, AIDA_BLACK, 4, 3)), cart.issues)
        }

        @Test
        fun `a plain change keeps the line's id and place, and it is seen at the new color's price`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 2, 1_450_000, addedOn = at(4)))

            cart.changeColor(itemId(1), variant(NILA_OLIVE), offers(nila(NILA_OLIVE, price = 1_500_000)), now)

            val moved = cart.lines.single()
            assertEquals(itemId(1), moved.itemId)
            assertEquals(variant(NILA_OLIVE), moved.variantId)
            assertEquals(2, moved.quantity.value)
            assertEquals(toman(1_500_000), moved.seenUnitPrice)
            assertEquals(at(4), moved.addedOn)
        }

        @Test
        fun `a new color with less left cuts the line`() {
            val cart = userCart(line(itemId(1), AIDA_CARAMEL, 3, 2_790_000))

            cart.changeColor(itemId(1), variant(AIDA_BLACK), offers(aida(AIDA_BLACK, available = 2)), now)

            assertEquals(mapOf(AIDA_BLACK to 2), cart.quantities())
            assertEquals(listOf(reduced(1, AIDA_BLACK, 3, 2)), cart.issues)
        }

        @Test
        fun `a color of another product, or of none, is a wrong input on variantId`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            listOf(variant(AIDA_BLACK), variant(999)).forEach {
                val failure = cart.changeColor(itemId(1), it, offers(), now).failureOrNull()
                assertEquals("VARIANT_ID_INVALID", failure?.code)
                assertEquals(FailureKind.INVALID, failure?.kind)
                assertEquals("variantId", failure?.field)
            }
            assertEquals(mapOf(NILA_BLACK to 1), cart.quantities())
        }

        @Test
        fun `a new color that is gone or out of the shop is refused`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            assertEquals("OUT_OF_STOCK", cart.changeColor(itemId(1), variant(NILA_OLIVE), offers(nila(NILA_OLIVE, available = 0)), now).failureOrNull()?.code)
            assertEquals("PRODUCT_UNAVAILABLE", cart.changeColor(itemId(1), variant(NILA_OLIVE), offers(nila(NILA_OLIVE, inShop = false)), now).failureOrNull()?.code)
            assertEquals("CART_ITEM_NOT_FOUND", cart.changeColor(itemId(9), variant(NILA_OLIVE), offers(), now).failureOrNull()?.code)
        }

        @Test
        fun `the same color again changes nothing`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            assertEquals(itemId(1), cart.changeColor(itemId(1), variant(NILA_BLACK), offers(), at(5)).getOrNull())
            assertEquals(now, cart.updatedOn)
        }
    }

    @Nested
    inner class RemoveAndClear {
        @Test
        fun `removing a line gives it back, for «برگردون»`() {
            val removedLine = line(itemId(1), NILA_BLACK, 2, 1_450_000)
            val cart = userCart(removedLine, line(itemId(2), AIDA_BLACK, 1, 2_790_000))

            val removed = cart.remove(itemId(1), now).getOrNull()

            assertEquals(variant(NILA_BLACK), removed?.variantId)
            assertEquals(2, removed?.quantity?.value)
            assertEquals(listOf(itemId(2)), cart.lines.map { it.itemId })
            assertEquals("CART_ITEM_NOT_FOUND", cart.remove(itemId(1), now).failureOrNull()?.code)
        }

        @Test
        fun `clearing takes the lines and the reservation, but keeps the shipping method`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 2, 1_450_000), method = TIPAX, reserve = true)

            cart.clear(at(2))

            assertTrue(cart.lines.isEmpty())
            assertFalse(cart.reserve)
            assertEquals(TIPAX, cart.shippingMethod)
            assertEquals(at(2), cart.updatedOn)
        }
    }

    @Nested
    inner class ShippingMethod {
        @Test
        fun `an offered method can be chosen`() {
            val cart = userCart()

            assertTrue(cart.chooseShippingMethod(TIPAX, settings, now).isSuccess)
            assertEquals(TIPAX, cart.shippingMethod)
        }

        @Test
        fun `a method without a row, or switched off, can't`() {
            val cart = userCart()

            assertEquals("SHIPPING_METHOD_UNAVAILABLE", cart.chooseShippingMethod(COURIER, settings, now).failureOrNull()?.code)
            assertEquals(
                "SHIPPING_METHOD_UNAVAILABLE",
                cart.chooseShippingMethod(TIPAX, shopSettings(listOf(tipax(isActive = false), post())), now).failureOrNull()?.code,
            )
            assertEquals(POST, cart.shippingMethod)
        }

        @Test
        fun `joining a reservation allows only the group's method`() {
            val cart = userCart()

            assertEquals("SHIPPING_METHOD_UNAVAILABLE", cart.chooseShippingMethod(TIPAX, settings, now, ShippingConsolidation(POST)).failureOrNull()?.code)
            assertTrue(cart.chooseShippingMethod(POST, settings, now, ShippingConsolidation(POST)).isSuccess)
        }
    }

    @Nested
    inner class Reservation {
        @Test
        fun `switching it on while the shop has reservations off is refused with the reason`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            val failure = cart.setReservation(true, offers(), settings, now).failureOrNull()

            assertEquals("RESERVATION_UNAVAILABLE", failure?.code)
            assertEquals(mapOf<String, Any>("reason" to "RESERVATION_DISABLED"), failure?.meta)
            assertFalse(cart.reserve)
        }

        @Test
        fun `switching it off is always allowed`() {
            val cart = userCart(reserve = true)

            assertTrue(cart.setReservation(false, offers(), settings, now).isSuccess)
            assertFalse(cart.reserve)
        }

        @Test
        fun `with reservations on, it switches on`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            assertTrue(cart.setReservation(true, offers(), reservationsOn, now).isSuccess)
            assertTrue(cart.reserve)
        }

        @Test
        fun `a bag that can be bought but not reserved stops it, one that can't be bought doesn't`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000))

            assertEquals(
                ReservationUnavailableReason.ITEM_NOT_RESERVABLE,
                cart.reservationUnavailableReason(offers(aida(AIDA_BLACK, reservable = false)), reservationsOn),
            )
            assertNull(cart.reservationUnavailableReason(offers(aida(AIDA_BLACK, reservable = false, available = 0)), reservationsOn))
        }

        @Test
        fun `the reasons come in the contract's order`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000))
            val notReservable = offers(aida(AIDA_BLACK, reservable = false))

            assertEquals(ReservationUnavailableReason.RESERVATION_DISABLED, cart.reservationUnavailableReason(notReservable, settings, hasActiveReservation = true))
            assertEquals(ReservationUnavailableReason.HAS_ACTIVE_RESERVATION, cart.reservationUnavailableReason(notReservable, reservationsOn, hasActiveReservation = true))
        }
    }

    @Nested
    inner class Sync {
        @Test
        fun `a price going from 1,450,000 to 1,600,000 is told once, then never again`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))
            val dearer = offers(nila(NILA_BLACK, price = 1_600_000))

            cart.sync(dearer, settings, now)
            assertEquals(listOf(CartIssue(PRICE_INCREASED, itemId(1), variant(NILA_BLACK), 1_450_000, 1_600_000)), cart.issues)
            assertEquals(toman(1_600_000), cart.lines.single().seenUnitPrice)

            val again = userCart(cart.lines.single())
            again.sync(dearer, settings, now)
            assertTrue(again.issues.isEmpty())
        }

        @Test
        fun `a cheaper price is told as a decrease`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            cart.sync(offers(nila(NILA_BLACK, price = 1_300_000)), settings, now)

            assertEquals(listOf(PRICE_DECREASED), cart.codes())
        }

        @Test
        fun `none left - told once, the line stays with its quantity but can't be bought`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 2, 2_790_000))
            val soldOut = offers(aida(AIDA_BLACK, available = 0))

            cart.sync(soldOut, settings, now)

            assertEquals(listOf(CartIssue(OUT_OF_STOCK, itemId(1), variant(AIDA_BLACK), null, null)), cart.issues)
            assertEquals(mapOf(AIDA_BLACK to 2), cart.quantities())
            assertFalse(cart.lines.single().seenPurchasable)
            assertEquals(0, cart.maxQuantityOf(cart.lines.single(), soldOut))

            val again = userCart(cart.lines.single())
            again.sync(soldOut, settings, now)
            assertTrue(again.issues.isEmpty())
        }

        @Test
        fun `out of the shop, or not known at all, is VARIANT_UNAVAILABLE`() {
            val archived = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000))
            archived.sync(offers(aida(AIDA_BLACK, inShop = false)), settings, now)

            val unknown = userCart(line(itemId(1), 999, 1, 2_790_000))
            unknown.sync(offers(), settings, now)

            assertEquals(listOf(VARIANT_UNAVAILABLE), archived.codes())
            assertEquals(listOf(VARIANT_UNAVAILABLE), unknown.codes())
            assertEquals(1, unknown.lines.size)
        }

        @Test
        fun `coming back is quiet, but a price changed meanwhile is told then`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000, seenPurchasable = false))

            cart.sync(offers(aida(AIDA_BLACK, price = 2_590_000)), settings, now)

            assertEquals(listOf(PRICE_DECREASED), cart.codes())
            assertTrue(cart.lines.single().seenPurchasable)
        }

        @Test
        fun `less left than the line holds - the line is cut and stays cut`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 3, 2_790_000))

            cart.sync(offers(aida(AIDA_BLACK, available = 2)), settings, now)

            assertEquals(listOf(reduced(1, AIDA_BLACK, 3, 2)), cart.issues)
            assertEquals(mapOf(AIDA_BLACK to 2), cart.quantities())
        }

        @Test
        fun `a lowered limit is cut from the newest line, and a line left with no room goes`() {
            val older = line(itemId(1), AIDA_CARAMEL, 3, 2_790_000, addedOn = at(0))
            val newer = line(itemId(2), AIDA_BLACK, 2, 2_790_000, addedOn = at(1))

            val toFour = userCart(older, newer)
            toFour.sync(offers(aida(AIDA_CARAMEL, maxPerOrder = 4), aida(AIDA_BLACK, maxPerOrder = 4)), settings, now)
            val toThree = userCart(older, newer)
            toThree.sync(offers(aida(AIDA_CARAMEL, maxPerOrder = 3), aida(AIDA_BLACK, maxPerOrder = 3)), settings, now)

            assertEquals(mapOf(AIDA_CARAMEL to 3, AIDA_BLACK to 1), toFour.quantities())
            assertEquals(listOf(reduced(2, AIDA_BLACK, 2, 1)), toFour.issues)
            assertEquals(mapOf(AIDA_CARAMEL to 3), toThree.quantities())
            assertEquals(listOf(reduced(2, AIDA_BLACK, 2, 0)), toThree.issues)
        }

        @Test
        fun `a line that can't be bought keeps its share of the limit, so its return never cuts the color being bought`() {
            val soldOutCaramel = line(itemId(1), AIDA_CARAMEL, 3, 2_790_000, seenPurchasable = false, addedOn = at(0))
            val black = line(itemId(2), AIDA_BLACK, 2, 2_790_000, addedOn = at(1))
            val cart = userCart(soldOutCaramel, black)

            assertEquals(2, cart.maxQuantityOf(black, offers(aida(AIDA_CARAMEL, available = 0))))
            cart.sync(offers(), settings, now) // the caramel is back

            assertEquals(mapOf(AIDA_CARAMEL to 3, AIDA_BLACK to 2), cart.quantities())
            assertTrue(cart.issues.isEmpty())
        }

        @Test
        fun `a method no longer offered moves to the shop's default, and is told`() {
            val cart = userCart(method = TIPAX)

            cart.sync(offers(), shopSettings(listOf(tipax(isActive = false), post())), now)

            assertEquals(POST, cart.shippingMethod)
            assertEquals(listOf(SHIPPING_METHOD_CHANGED), cart.codes())
        }

        @Test
        fun `with the default gone too, the first method on offer, and with none, the cart keeps its own`() {
            val toTipax = userCart(method = COURIER)
            toTipax.sync(offers(), shopSettings(listOf(tipax(), post(isActive = false))), now)
            val nothingOffered = userCart(method = POST)
            nothingOffered.sync(offers(), shopSettings(listOf(tipax(isActive = false), post(isActive = false))), now)

            assertEquals(TIPAX, toTipax.shippingMethod)
            assertEquals(POST, nothingOffered.shippingMethod)
            assertTrue(nothingOffered.issues.isEmpty())
        }

        @Test
        fun `the reservation goes off silently when it is no longer possible`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000), reserve = true)

            cart.sync(offers(), settings, now)

            assertFalse(cart.reserve)
            assertTrue(cart.issues.isEmpty())
        }

        @Test
        fun `looking at the cart is using it`() {
            val cart = userCart()

            cart.sync(offers(), settings, at(30))

            assertEquals(at(30), cart.updatedOn)
        }
    }

    @Nested
    inner class Absorb {
        @Test
        fun `the guest's 2 Nila black and the customer's 1 make one line of 3, the customer's`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000, addedOn = at(0)))
            val guest = guestCart(line(itemId(2), NILA_BLACK, 2, 1_450_000, addedOn = at(5)))

            cart.absorb(guest, offers(), settings, now)

            assertEquals(listOf(itemId(1)), cart.lines.map { it.itemId })
            assertEquals(mapOf(NILA_BLACK to 3), cart.quantities())
            assertTrue(cart.issues.isEmpty())
        }

        @Test
        fun `more than the limit is cut, and told`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 3, 1_450_000, addedOn = at(0)))
            val guest = guestCart(line(itemId(2), NILA_BLACK, 4, 1_450_000, addedOn = at(5)))

            cart.absorb(guest, offers(), settings, now)

            assertEquals(mapOf(NILA_BLACK to 5), cart.quantities())
            assertEquals(listOf(reduced(1, NILA_BLACK, 7, 5)), cart.issues)
        }

        @Test
        fun `a color only the guest had moves over with its id, its place and what was seen`() {
            val guestLine = line(itemId(2), AIDA_BLACK, 1, 2_790_000, addedOn = at(-10))
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000, addedOn = at(0)))

            cart.absorb(guestCart(guestLine), offers(), settings, now)

            assertEquals(listOf(itemId(2), itemId(1)), cart.lines.map { it.itemId })
            val moved = cart.find(itemId(2))!!
            assertEquals(guestLine.seenUnitPrice, moved.seenUnitPrice)
            assertEquals(at(-10), moved.addedOn)
        }

        @Test
        fun `the guest's shipping method and reservation switch win`() {
            val cart = userCart(method = POST, reserve = false)

            cart.absorb(guestCart(line(itemId(2), NILA_BLACK, 1, 1_450_000), method = TIPAX, reserve = true), offers(), reservationsOn, now)

            assertEquals(TIPAX, cart.shippingMethod)
            assertTrue(cart.reserve)
        }

        @Test
        fun `but the reservation goes off when the customer's own lines can't be reserved`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000))

            cart.absorb(
                guestCart(line(itemId(2), NILA_BLACK, 1, 1_450_000), reserve = true),
                offers(aida(AIDA_BLACK, reservable = false)), reservationsOn, now,
            )

            assertFalse(cart.reserve)
        }

        @Test
        fun `across both carts the older line keeps its share - the guest's older caramel stays, the customer's black is cut`() {
            val cart = userCart(line(itemId(1), AIDA_BLACK, 3, 2_790_000, addedOn = at(5)))
            val guest = guestCart(line(itemId(2), AIDA_CARAMEL, 3, 2_790_000, addedOn = at(1)))

            cart.absorb(guest, offers(), settings, now)

            assertEquals(mapOf(AIDA_CARAMEL to 3, AIDA_BLACK to 2), cart.quantities())
            assertEquals(listOf(reduced(1, AIDA_BLACK, 3, 2)), cart.issues)
        }

        @Test
        fun `a guest line with no room left under the limit goes, told as cut to 0`() {
            val cart = userCart(line(itemId(1), AIDA_CARAMEL, 3, 2_790_000, addedOn = at(0)), line(itemId(2), AIDA_BLACK, 2, 2_790_000, addedOn = at(1)))
            val guest = guestCart(line(itemId(3), AIDA_RED, 2, 2_790_000, addedOn = at(5)))

            cart.absorb(guest, offers(), settings, now)

            assertEquals(mapOf(AIDA_CARAMEL to 3, AIDA_BLACK to 2), cart.quantities())
            assertEquals(listOf(reduced(3, AIDA_RED, 2, 0)), cart.issues)
        }

        @Test
        fun `what the guest cart was told comes along`() {
            val guest = guestCart(line(itemId(2), NILA_BLACK, 1, 1_450_000))
            guest.sync(offers(nila(NILA_BLACK, price = 1_600_000)), settings, now)
            val cart = userCart()

            cart.absorb(guest, offers(nila(NILA_BLACK, price = 1_600_000)), settings, now)

            assertEquals(listOf(PRICE_INCREASED), cart.codes())
        }

        @Test
        fun `only a guest cart goes into a customer's cart`() {
            assertFailsWith<IllegalArgumentException> { userCart().absorb(userCart(), offers(), settings, now) }
            assertFailsWith<IllegalArgumentException> { guestCart().absorb(guestCart(), offers(), settings, now) }
        }
    }

    @Test
    fun `a stored cart holding a color twice is a bug`() {
        assertFailsWith<IllegalArgumentException> {
            userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000), line(itemId(2), NILA_BLACK, 1, 1_450_000))
        }
    }

    @Test
    fun `printing a guest cart shows nothing of its token`() {
        assertEquals("Cart(publicId=cart_guest00001, owner=Guest, lines=0)", guestCart().toString())
    }
}
