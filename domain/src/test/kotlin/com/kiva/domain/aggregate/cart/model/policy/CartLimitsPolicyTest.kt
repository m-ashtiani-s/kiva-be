package com.kiva.domain.aggregate.cart.model.policy

import com.kiva.domain.aggregate.cart.CartFixtures.AIDA_BLACK
import com.kiva.domain.aggregate.cart.CartFixtures.NILA_BLACK
import com.kiva.domain.aggregate.cart.CartFixtures.aida
import com.kiva.domain.aggregate.cart.CartFixtures.nila
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CartLimitsPolicyTest {

    @Test
    fun `what is left, when it is below the product's limit`() {
        assertEquals(3, CartLimitsPolicy.maxQuantity(aida(AIDA_BLACK, available = 3), othersOfProduct = 0))
    }

    @Test
    fun `the product's limit, shared with its other colors in the cart`() {
        assertEquals(5, CartLimitsPolicy.maxQuantity(nila(NILA_BLACK, available = 10), othersOfProduct = 0))
        assertEquals(2, CartLimitsPolicy.maxQuantity(nila(NILA_BLACK, available = 10), othersOfProduct = 3))
        assertEquals(0, CartLimitsPolicy.maxQuantity(nila(NILA_BLACK, available = 10), othersOfProduct = 5))
    }

    @Test
    fun `never more than 20 in a line, however big the limit and the stock`() {
        assertEquals(20, CartLimitsPolicy.maxQuantity(nila(NILA_BLACK, available = 50, maxPerOrder = 20), othersOfProduct = 0))
        assertEquals(20, CartLimitsPolicy.roomUnderLimit(nila(NILA_BLACK, maxPerOrder = 20), othersOfProduct = 0))
    }

    @Test
    fun `0 for a color that can't be bought, but its room under the limit is still known`() {
        val soldOut = aida(AIDA_BLACK, available = 0)

        assertEquals(0, CartLimitsPolicy.maxQuantity(soldOut, othersOfProduct = 0))
        assertEquals(0, CartLimitsPolicy.maxQuantity(aida(AIDA_BLACK, inShop = false), othersOfProduct = 0))
        assertEquals(5, CartLimitsPolicy.roomUnderLimit(soldOut, othersOfProduct = 0))
    }

    @Test
    fun `other colors holding more than the limit leave no room, not a negative one`() {
        assertEquals(0, CartLimitsPolicy.roomUnderLimit(nila(NILA_BLACK), othersOfProduct = 7))
        assertFailsWith<IllegalArgumentException> { CartLimitsPolicy.roomUnderLimit(nila(NILA_BLACK), othersOfProduct = -1) }
    }
}
