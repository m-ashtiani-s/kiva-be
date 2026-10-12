package com.kiva.domain.aggregate.cart

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartFixtures.AIDA_BLACK
import com.kiva.domain.aggregate.cart.CartFixtures.NILA_BLACK
import com.kiva.domain.aggregate.cart.CartFixtures.NILA_OLIVE
import com.kiva.domain.aggregate.cart.CartFixtures.SequentialCartIds
import com.kiva.domain.aggregate.cart.CartFixtures.TOKEN
import com.kiva.domain.aggregate.cart.CartFixtures.at
import com.kiva.domain.aggregate.cart.CartFixtures.guestCart
import com.kiva.domain.aggregate.cart.CartFixtures.itemId
import com.kiva.domain.aggregate.cart.CartFixtures.line
import com.kiva.domain.aggregate.cart.CartFixtures.nila
import com.kiva.domain.aggregate.cart.CartFixtures.now
import com.kiva.domain.aggregate.cart.CartFixtures.offers
import com.kiva.domain.aggregate.cart.CartFixtures.quantity
import com.kiva.domain.aggregate.cart.CartFixtures.userCart
import com.kiva.domain.aggregate.cart.CartFixtures.variant
import com.kiva.domain.aggregate.cart.model.CartIssueCode.PRICE_INCREASED
import com.kiva.domain.aggregate.cart.model.CartIssueCode.SHIPPING_METHOD_CHANGED
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken
import com.kiva.domain.aggregate.order.PricingFixtures.post
import com.kiva.domain.aggregate.order.PricingFixtures.shopSettings
import com.kiva.domain.aggregate.order.PricingFixtures.tipax
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.TIPAX
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CartDomainServiceTest {

    private val service = CartDomainService(SequentialCartIds())
    private val settings = shopSettings()

    /** Nila black has become dearer since the shopper saw it. */
    private val dearerNila = offers(nila(NILA_BLACK, price = 1_600_000))

    @Nested
    inner class NewCarts {
        @Test
        fun `a customer's new cart is empty, ships by the shop's default and gets a random id`() {
            val cart = service.newUserCart(UserId.constructFromPersisted(7), settings, now)

            assertEquals(CartOwner.User(UserId.constructFromPersisted(7)), cart.owner)
            assertEquals("cart_0000000001", cart.publicId.value)
            assertEquals(POST, cart.shippingMethod)
            assertTrue(cart.lines.isEmpty())
            assertFalse(cart.reserve)
            assertEquals(now, cart.createdOn)
        }

        @Test
        fun `a guest's new cart keeps only the hash of its token`() {
            val new = service.newGuestCart(settings, now)

            assertEquals(CartOwner.Guest(CartToken.makeNew(TOKEN).getOrNull()!!.hash()), new.cart.owner)
            assertEquals(TOKEN, new.token.reveal())
            assertTrue(new.cart.isGuest)
            assertFalse("$new".contains(TOKEN))
        }
    }

    @Nested
    inner class SyncFirst {
        @Test
        fun `adding first brings the cart up to date, so the shopper hears what changed, then adds with a new id`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            val added = service.addItem(cart, variant(AIDA_BLACK), quantity(1), dearerNila, settings, now)

            assertEquals(itemId(101), added.getOrNull())
            assertEquals(listOf(PRICE_INCREASED), cart.issues.map { it.code })
            assertEquals(listOf(NILA_BLACK, AIDA_BLACK), cart.lines.map { it.variantId.value })
        }

        @Test
        fun `an unknown color is VARIANT_NOT_FOUND`() {
            assertEquals("VARIANT_NOT_FOUND", service.addItem(userCart(), variant(999), quantity(1), offers(), settings, now).failureOrNull()?.code)
        }

        @Test
        fun `choosing and reserving bring the cart up to date first too`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            assertTrue(service.chooseShippingMethod(cart, TIPAX, dearerNila, settings, now).isSuccess)
            assertEquals("RESERVATION_UNAVAILABLE", service.setReservation(cart, true, dearerNila, settings, now).failureOrNull()?.code)

            assertEquals(TIPAX, cart.shippingMethod)
            assertEquals(listOf(PRICE_INCREASED), cart.issues.map { it.code })
        }

        @Test
        fun `opening the cart is the same bringing up to date`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            service.openCart(cart, dearerNila, settings, at(10))

            assertEquals(listOf(PRICE_INCREASED), cart.issues.map { it.code })
            assertEquals(at(10), cart.updatedOn)
        }
    }

    @Nested
    inner class ChangeItem {
        @Test
        fun `color and quantity together - the color first, then the quantity on the line that remains`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000, addedOn = at(0)), line(itemId(2), NILA_OLIVE, 1, 1_450_000, addedOn = at(1)))

            val remaining = service.changeItem(cart, itemId(1), quantity(4), variant(NILA_OLIVE), offers(), settings, now)

            assertEquals(itemId(2), remaining.getOrNull())
            assertEquals(mapOf(NILA_OLIVE to 4), cart.lines.associate { it.variantId.value to it.quantity.value })
        }

        @Test
        fun `a refused color stops there, and the quantity is not touched`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

            val result = service.changeItem(cart, itemId(1), quantity(2), variant(AIDA_BLACK), offers(), settings, now)

            assertEquals("VARIANT_ID_INVALID", result.failureOrNull()?.code)
            assertEquals(1, cart.lines.single().quantity.value)
        }

        @Test
        fun `a change with neither is a bug in the caller`() {
            assertFailsWith<IllegalArgumentException> { service.changeItem(userCart(), itemId(1), null, null, offers(), settings, now) }
        }
    }

    @Nested
    inner class TakingAway {
        @Test
        fun `a removed line is taken out first, so nothing is told about it, only about the rest`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000), line(itemId(2), AIDA_BLACK, 1, 2_000_000))

            val removed = service.removeItem(cart, itemId(1), dearerNila, settings, now)

            assertEquals(variant(NILA_BLACK), removed.getOrNull()?.variantId)
            assertEquals(listOf(itemId(2)), cart.issues.map { it.itemId })
        }

        @Test
        fun `clearing tells nothing about the lines it took, only what the cart keeps`() {
            val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000), method = TIPAX)

            service.clearCart(cart, dearerNila, shopSettings(listOf(tipax(isActive = false), post())), now)

            assertTrue(cart.lines.isEmpty())
            assertEquals(listOf(SHIPPING_METHOD_CHANGED), cart.issues.map { it.code })
        }
    }

    @Test
    fun `merging brings both carts up to date and takes the guest's in, with what the guest is told`() {
        val cart = userCart(line(itemId(1), AIDA_BLACK, 1, 2_790_000))
        val guest = guestCart(line(itemId(2), NILA_BLACK, 2, 1_450_000))

        service.mergeGuestCart(cart, guest, dearerNila, settings, now)

        assertEquals(mapOf(AIDA_BLACK to 1, NILA_BLACK to 2), cart.lines.associate { it.variantId.value to it.quantity.value })
        assertEquals(listOf(itemId(2) to PRICE_INCREASED), cart.issues.map { it.itemId to it.code })
    }

    @Test
    fun `a login without a guest cart is a look at the customer's cart - what changed is told, once`() {
        val cart = userCart(line(itemId(1), NILA_BLACK, 1, 1_450_000))

        service.mergeGuestCart(cart, guest = null, dearerNila, settings, at(2))

        assertEquals(listOf(itemId(1) to PRICE_INCREASED), cart.issues.map { it.itemId to it.code })
        assertEquals(1_600_000, cart.lines.single().seenUnitPrice.toman)
        assertEquals(at(2), cart.updatedOn)
    }
}
