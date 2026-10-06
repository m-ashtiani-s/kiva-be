package com.kiva.domain.aggregate.product.model.policy

import com.kiva.domain.aggregate.product.ProductTestData.priceTag
import com.kiva.domain.aggregate.product.ProductTestData.toman
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode.FREE_SHIPPING
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode.HAS_VIDEO
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode.LOW_STOCK
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode.NEW
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode.SALE
import com.kiva.domain.aggregate.product.model.valueobject.BadgeCode.SOLD_OUT
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus.IN_STOCK
import com.kiva.domain.aggregate.product.model.valueobject.StockStatus.OUT_OF_STOCK
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BadgePolicyTest {

    private val freeShippingThreshold = toman(3_000_000)

    private fun badges(
        isNew: Boolean = false,
        priceTag: PriceTag = priceTag(1_890_000),
        hasVideo: Boolean = false,
        stock: StockStatus = IN_STOCK,
    ) = BadgePolicy.badgesOf(isNew, priceTag, hasVideo, stock, freeShippingThreshold)

    @Nested
    inner class IsNew {
        private val newUntil = Instant.parse("2026-10-20T00:00:00Z")

        @Test
        fun `new until the moment newUntil is reached, not after`() {
            assertTrue(BadgePolicy.isNew(newUntil, now = Instant.parse("2026-10-19T23:59:59Z")))
            assertFalse(BadgePolicy.isNew(newUntil, now = newUntil))
            assertFalse(BadgePolicy.isNew(newUntil, now = Instant.parse("2026-11-01T00:00:00Z")))
        }

        @Test
        fun `never new when newUntil is not set`() {
            assertFalse(BadgePolicy.isNew(null, now = Instant.parse("2026-10-06T08:00:00Z")))
        }
    }

    @Test
    fun `a plain bag with nothing special wears no badge`() {
        assertEquals(emptyList(), badges())
    }

    @Test
    fun `each badge appears when its reason is there, in the contract's order`() {
        assertEquals(
            listOf(NEW, SALE, HAS_VIDEO, FREE_SHIPPING, LOW_STOCK),
            badges(isNew = true, priceTag = priceTag(3_150_000, compareAt = 3_500_000), hasVideo = true, stock = StockStatus.LOW_STOCK),
        )
    }

    @Test
    fun `a sold-out bag drops sale and free shipping but keeps new and video, like the prototype's card`() {
        assertEquals(
            listOf(NEW, HAS_VIDEO, SOLD_OUT),
            badges(isNew = true, priceTag = priceTag(3_150_000, compareAt = 3_500_000), hasVideo = true, stock = OUT_OF_STOCK),
        )
    }

    @Test
    fun `prototype product 2, on sale with a video and 9 left, shows sale and video`() {
        assertEquals(listOf(SALE, HAS_VIDEO), badges(priceTag = priceTag(1_450_000, compareAt = 1_750_000), hasVideo = true))
    }

    @Test
    fun `prototype product 4, on sale with 3 left, shows sale and low stock`() {
        assertEquals(listOf(SALE, LOW_STOCK), badges(priceTag = priceTag(2_790_000, compareAt = 3_290_000), stock = StockStatus.LOW_STOCK))
    }

    @Test
    fun `prototype product 5, with none left, shows only sold out`() {
        assertEquals(listOf(SOLD_OUT), badges(priceTag = priceTag(980_000), stock = OUT_OF_STOCK))
    }
}
