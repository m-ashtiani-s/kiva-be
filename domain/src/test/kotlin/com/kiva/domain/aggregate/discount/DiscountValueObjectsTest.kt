package com.kiva.domain.aggregate.discount

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.DiscountFixtures.code
import com.kiva.domain.aggregate.discount.DiscountFixtures.line
import com.kiva.domain.aggregate.discount.DiscountFixtures.now
import com.kiva.domain.aggregate.discount.DiscountFixtures.toman
import com.kiva.domain.aggregate.discount.entity.DiscountRedemption
import com.kiva.domain.aggregate.discount.model.policy.DiscountPolicy
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiscountValueObjectsTest {

    @Nested
    inner class TheCodeText {
        private fun textOf(raw: String): String = assertIs<Result.Success<DiscountCodeText>>(DiscountCodeText.makeNew(raw)).value.value

        private fun failureOf(raw: String): String = assertIs<Result.Failure<DiscountCodeText.Failure>>(DiscountCodeText.makeNew(raw)).failure.code

        @Test
        fun `kiva10 with a space and KIVA with Persian digits are both KIVA10`() {
            assertEquals("KIVA10", textOf("kiva10 "))
            assertEquals("KIVA10", textOf("KIVA۱۰"))
            assertEquals("KIVA10", textOf("\tKiva١٠"))
            assertEquals("SUMMER_25-OFF", textOf("summer_25-off"))
        }

        @Test
        fun `nothing typed is a missing field, anything no code can be is an invalid code`() {
            assertEquals("DISCOUNT_CODE_REQUIRED", failureOf("   "))
            assertEquals("code", DiscountCodeText.Failure.Required.field)
            listOf("K", "A".repeat(33), "کیوا۱۰", "KIVA 10", "KIVA!0").forEach { assertEquals("DISCOUNT_CODE_INVALID", failureOf(it), it) }
        }

        @Test
        fun `a stored code that breaks the format is a bug`() {
            assertFailsWith<IllegalArgumentException> { DiscountCodeText.constructFromPersisted("kiva10") }
        }
    }

    @Nested
    inner class TheMoney {
        @Test
        fun `10 percent to the nearest thousand, a half up`() {
            assertEquals(toman(348_000), DiscountPolicy.percentOf(toman(3_480_000), 10))
            assertEquals(toman(146_000), DiscountPolicy.percentOf(toman(1_455_000), 10))
            assertEquals(toman(145_000), DiscountPolicy.percentOf(toman(1_454_990), 10))
            assertEquals(toman(0), DiscountPolicy.percentOf(toman(4_999), 10))
            assertEquals(toman(1_000), DiscountPolicy.percentOf(toman(5_000), 10))
        }

        @Test
        fun `a percent code stops at its maximum, a fixed one at its base`() {
            assertEquals(toman(200_000), DiscountPolicy.amountOf(code(value = 15, maxDiscount = 200_000), toman(3_480_000)))
            assertEquals(toman(522_000), DiscountPolicy.amountOf(code(value = 15), toman(3_480_000)))
            assertEquals(toman(1_455_000), DiscountPolicy.amountOf(code(value = 100), toman(1_455_000)))
            assertEquals(toman(100_000), DiscountPolicy.amountOf(code(type = DiscountType.FIXED, value = 150_000), toman(100_000)))
            assertEquals(toman(150_000), DiscountPolicy.amountOf(code(type = DiscountType.FIXED, value = 150_000), toman(3_480_000)))
        }

        @Test
        fun `the base is what its covered lines cost, each line's quantity counted`() {
            val lines = listOf(line(1_000_000, quantity = 2), line(500_000, compareAt = 600_000, productId = 4), line(700_000, productId = 5, available = 0))
            assertEquals(toman(2_500_000), DiscountPolicy.baseOf(code(), lines))
            assertEquals(toman(2_000_000), DiscountPolicy.baseOf(code(excludeSaleItems = true), lines))
        }

        @Test
        fun `a code that can't exist is a bug`() {
            assertFailsWith<IllegalArgumentException> { code(value = 101) }
            assertFailsWith<IllegalArgumentException> { code(type = DiscountType.FIXED, value = 0) }
            assertFailsWith<IllegalArgumentException> { code(usageLimit = 0) }
            assertFailsWith<IllegalArgumentException> { code(startsAt = now, endsAt = now) }
        }
    }

    @Nested
    inner class TheRedemption {
        private fun held() = DiscountRedemption.of(
            DiscountCodeId.constructFromPersisted(1), UserId.constructFromPersisted(7), OrderId.constructFromPersisted(5), toman(348_000), paid = false, now,
        )

        @Test
        fun `held, then confirmed once - a release after that changes nothing`() {
            val redemption = held()
            assertTrue(redemption.confirm(now.plusSeconds(60)))
            assertFalse(redemption.confirm(now.plusSeconds(120)))
            assertFalse(redemption.release(now.plusSeconds(180)))
            assertEquals(DiscountRedemptionStatus.CONFIRMED, redemption.status)
            assertEquals(now.plusSeconds(60), redemption.settledOn)
        }

        @Test
        fun `held, then released once - its use goes back to the code`() {
            val redemption = held()
            assertTrue(redemption.release(now.plusSeconds(60)))
            assertFalse(redemption.release(now.plusSeconds(120)))
            assertFalse(redemption.confirm(now.plusSeconds(180)))
            assertEquals(DiscountRedemptionStatus.RELEASED, redemption.status)
        }
    }
}
