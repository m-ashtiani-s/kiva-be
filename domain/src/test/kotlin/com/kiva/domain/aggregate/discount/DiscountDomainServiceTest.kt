package com.kiva.domain.aggregate.discount

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.discount.DiscountFixtures.code
import com.kiva.domain.aggregate.discount.DiscountFixtures.customer
import com.kiva.domain.aggregate.discount.DiscountFixtures.firstCart
import com.kiva.domain.aggregate.discount.DiscountFixtures.kiva10
import com.kiva.domain.aggregate.discount.DiscountFixtures.line
import com.kiva.domain.aggregate.discount.DiscountFixtures.now
import com.kiva.domain.aggregate.discount.DiscountFixtures.paeez15
import com.kiva.domain.aggregate.discount.DiscountFixtures.toman
import com.kiva.domain.aggregate.discount.DiscountFixtures.welcome
import com.kiva.domain.aggregate.discount.entity.DiscountCode
import com.kiva.domain.aggregate.discount.model.CodeDiscount
import com.kiva.domain.aggregate.discount.model.DiscountLine
import com.kiva.domain.aggregate.discount.model.DiscountShopper
import com.kiva.domain.aggregate.discount.model.DiscountUsage
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.order.OrderFixtures
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class DiscountDomainServiceTest {

    private val service = DiscountDomainService()
    private val unused = DiscountUsage(ofCode = 0, ofCustomer = 0)

    private fun check(
        code: DiscountCode?,
        lines: List<DiscountLine> = firstCart,
        shopper: DiscountShopper = customer,
        usage: DiscountUsage = unused,
    ): Result<CodeDiscount, DiscountDomainService.Failure> = service.check(code, shopper, usage, lines, now)

    private fun failureOf(result: Result<CodeDiscount, DiscountDomainService.Failure>): DiscountDomainService.Failure =
        assertIs<Result.Failure<DiscountDomainService.Failure>>(result).failure

    private fun amountOf(result: Result<CodeDiscount, DiscountDomainService.Failure>): Long =
        assertIs<Result.Success<CodeDiscount>>(result).value.amount.toman

    @Nested
    inner class TheSeederCodes {
        @Test
        fun `KIVA10 on the first cart takes 348,000 off 3,480,000`() {
            assertEquals(348_000, amountOf(check(kiva10)))
        }

        @Test
        fun `PAEEZ15 under 2,000,000 - MIN_SUBTOTAL_NOT_MET with the minimum, from 2,000,000 on it applies`() {
            val failure = failureOf(check(paeez15, listOf(line(1_450_000, compareAt = 1_750_000))))
            assertEquals("DISCOUNT_MIN_SUBTOTAL_NOT_MET", failure.code)
            assertEquals(mapOf<String, Any>("minSubtotal" to 2_000_000L), failure.meta)

            assertEquals(300_000, amountOf(check(paeez15, listOf(line(2_000_000)))))
        }

        @Test
        fun `WELCOME - a guest must log in, a customer with a paid order is refused, a first order of 100,000 gets 100,000 off`() {
            assertEquals("DISCOUNT_LOGIN_REQUIRED", failureOf(check(welcome, shopper = DiscountShopper.Guest)).code)
            assertEquals("DISCOUNT_FIRST_ORDER_ONLY", failureOf(check(welcome, shopper = customer.copy(hasPaidOrder = true))).code)
            assertEquals(100_000, amountOf(check(welcome, listOf(line(100_000)))))
            assertEquals(150_000, amountOf(check(welcome)))
        }

        @Test
        fun `a guest may use a code that counts nobody`() {
            assertEquals(348_000, amountOf(check(kiva10, shopper = DiscountShopper.Guest)))
        }
    }

    @Nested
    inner class TheOrderOfTheRules {
        @Test
        fun `no such code, or one switched off - INVALID`() {
            assertEquals("DISCOUNT_CODE_INVALID", failureOf(check(null)).code)
            assertEquals("DISCOUNT_CODE_INVALID", failureOf(check(code(isActive = false, endsAt = now.minusSeconds(1)))).code)
        }

        @Test
        fun `not started yet, then over at its very end`() {
            assertEquals("DISCOUNT_CODE_NOT_STARTED", failureOf(check(code(startsAt = now.plusSeconds(1)))).code)
            assertEquals(348_000, amountOf(check(code(startsAt = now))))
            assertEquals("DISCOUNT_CODE_EXPIRED", failureOf(check(code(startsAt = now.minus(Duration.ofDays(9)), endsAt = now))).code)
            assertEquals(348_000, amountOf(check(code(endsAt = now.plusSeconds(1)))))
        }

        @Test
        fun `a guest and a code that counts its customers - LOGIN_REQUIRED, before its limits`() {
            listOf(code(requiresLogin = true), code(firstOrderOnly = true), code(perUserLimit = 3)).forEach { code ->
                assertEquals("DISCOUNT_LOGIN_REQUIRED", failureOf(check(code, shopper = DiscountShopper.Guest, usage = DiscountUsage(5, 0))).code, code.toString())
            }
        }

        @Test
        fun `held and confirmed uses count against both limits`() {
            assertEquals("DISCOUNT_USAGE_LIMIT_REACHED", failureOf(check(code(usageLimit = 1), usage = DiscountUsage(1, 0))).code)
            assertEquals(348_000, amountOf(check(code(usageLimit = 2), usage = DiscountUsage(1, 0))))
            assertEquals("DISCOUNT_USAGE_LIMIT_REACHED", failureOf(check(code(perUserLimit = 1), usage = DiscountUsage(4, 1))).code)
        }

        @Test
        fun `first order only comes before the limits, the limits before the minimum, the minimum before what it covers`() {
            val everything = code(firstOrderOnly = true, usageLimit = 1, minSubtotal = 9_000_000, productIds = setOf(99))
            assertEquals("DISCOUNT_FIRST_ORDER_ONLY", failureOf(check(everything, shopper = customer.copy(hasPaidOrder = true), usage = DiscountUsage(1, 1))).code)
            assertEquals("DISCOUNT_USAGE_LIMIT_REACHED", failureOf(check(everything, usage = DiscountUsage(1, 0))).code)
            assertEquals("DISCOUNT_MIN_SUBTOTAL_NOT_MET", failureOf(check(everything)).code)
            assertEquals("DISCOUNT_NOT_APPLICABLE", failureOf(check(code(productIds = setOf(99)))).code)
        }

        @Test
        fun `the minimum looks at the subtotal of what can be bought`() {
            val lines = listOf(line(1_500_000), line(900_000, productId = 4, available = 0))
            assertEquals("DISCOUNT_MIN_SUBTOTAL_NOT_MET", failureOf(check(paeez15, lines)).code)
        }
    }

    @Nested
    inner class WhatItCovers {
        @Test
        fun `excludeSaleItems - only lines without a crossed-out price are its base`() {
            val lines = listOf(line(2_790_000, compareAt = 3_290_000), line(1_200_000, productId = 6))
            assertEquals(120_000, amountOf(check(code(excludeSaleItems = true), lines)))
        }

        @Test
        fun `excludeSaleItems and only sale items - NOT_APPLICABLE`() {
            assertEquals("DISCOUNT_NOT_APPLICABLE", failureOf(check(code(excludeSaleItems = true))).code)
        }

        @Test
        fun `products and categories - a line of either is covered`() {
            val lines = listOf(line(1_000_000, productId = 3, categoryId = 1), line(2_000_000, productId = 4, categoryId = 2), line(4_000_000, productId = 5, categoryId = 3))
            assertEquals(300_000, amountOf(check(code(productIds = setOf(3), categoryIds = setOf(2)), lines)))
        }

        @Test
        fun `a line that can't be bought is never covered`() {
            assertEquals("DISCOUNT_NOT_APPLICABLE", failureOf(check(kiva10, listOf(line(1_000_000, inShop = false)))).code)
            assertEquals("DISCOUNT_NOT_APPLICABLE", failureOf(check(kiva10, emptyList())).code)
        }

        @Test
        fun `a fixed code takes at most what it covers`() {
            assertEquals(90_000, amountOf(check(code(type = DiscountType.FIXED, value = 150_000), listOf(line(90_000)))))
        }
    }

    @Nested
    inner class Redeeming {
        @Test
        fun `an order placed with a code holds its use, for its customer, at the amount it took`() {
            val discount = CodeDiscount(kiva10, toman(348_000))
            val order = OrderFixtures.placedOrder(OrderFixtures.placement(codeDiscount = discount))

            val redemption = service.redeem(discount, order, now)

            assertEquals(DiscountRedemptionStatus.HELD, redemption.status)
            assertEquals(OrderFixtures.sara, redemption.userId)
            assertEquals(order.id, redemption.orderId)
            assertEquals(toman(348_000), redemption.amount)
            assertNull(redemption.settledOn)
        }

        @Test
        fun `an order the code paid in full is paid at once, and its use confirmed at once`() {
            val discount = CodeDiscount(code(type = DiscountType.FIXED, value = 5_000_000), toman(3_480_000))
            val order = OrderFixtures.placedOrder(OrderFixtures.placement(codeDiscount = discount))

            val redemption = service.redeem(discount, order, now)

            assertEquals(OrderStatus.PROCESSING, order.status)
            assertEquals(DiscountRedemptionStatus.CONFIRMED, redemption.status)
            assertEquals(now, redemption.settledOn)
        }

        @Test
        fun `an order placed without the code is a bug`() {
            val order = OrderFixtures.placedOrder()
            assertFailsWith<IllegalArgumentException> { service.redeem(CodeDiscount(kiva10, toman(348_000)), order, now) }
        }
    }
}
