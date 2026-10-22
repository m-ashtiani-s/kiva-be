package com.kiva.domain.aggregate.wishlist

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.wishlist.entity.Wishlist
import com.kiva.domain.aggregate.wishlist.entity.WishlistEntry
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import com.kiva.domain.aggregate.wishlist.model.valueobject.WishlistShareToken
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishlistTest {

    private val user = UserId.constructFromPersisted(7)
    private val now = Instant.parse("2026-10-12T08:00:00Z")

    private fun id(n: Long) = ProductId.constructFromPersisted(n)
    private fun toman(n: Long) = Money.constructFromPersisted(n)
    private fun key(k: String) = ColorKey.constructFromPersisted(k)

    @Test
    fun `saved, newest first - saved again only takes a new color, its price and moment stay - no color keeps the old one`() {
        val list = Wishlist.empty(user)

        assertEquals(Result.success(Wishlist.Added.ADDED), list.add(id(1), key("lilac"), toman(1_450_000), now))
        assertEquals(Result.success(Wishlist.Added.ADDED), list.add(id(2), null, toman(990_000), now.plusSeconds(60)))
        assertEquals(Result.success(Wishlist.Added.ALREADY_THERE), list.add(id(1), key("black"), toman(1_200_000), now.plusSeconds(120)))
        list.add(id(1), null, toman(1_000_000), now.plusSeconds(180))

        assertEquals(listOf(id(2), id(1)), list.entries.map { it.productId })
        assertEquals(WishlistEntry(id(1), key("black"), toman(1_450_000), now), list.entryOf(id(1)))
    }

    @Test
    fun `the 201st product is WISHLIST_LIMIT_REACHED with the limit - one already in is still saved again`() {
        val list = Wishlist.empty(user)
        (1L..200L).forEach { list.add(id(it), null, toman(100), now) }

        val refused = list.add(id(201), null, toman(100), now)

        assertEquals(Result.failure(Wishlist.LimitReached), refused)
        assertEquals(mapOf<String, Any>("limit" to 200), Wishlist.LimitReached.meta)
        assertEquals(Result.success(Wishlist.Added.ALREADY_THERE), list.add(id(5), key("lilac"), toman(100), now))
        assertEquals(200, list.entries.size)
    }

    @Test
    fun `taken out - false when it wasn't in - emptied - false when it was empty`() {
        val list = Wishlist.empty(user)
        list.add(id(1), null, toman(100), now)

        assertTrue(list.remove(id(1)))
        assertFalse(list.remove(id(1)))
        list.add(id(2), null, toman(100), now)
        assertTrue(list.clear())
        assertFalse(list.clear())
    }

    @Test
    fun `stored rows that break the rules stop loudly - 201 rows, a product twice`() {
        assertThrows<IllegalArgumentException> {
            Wishlist.constructFromPersisted(user, (1L..201L).map { WishlistEntry(id(it), null, toman(1), now) })
        }
        assertThrows<IllegalArgumentException> {
            Wishlist.constructFromPersisted(user, listOf(WishlistEntry(id(1), null, toman(1), now), WishlistEntry(id(1), null, toman(2), now)))
        }
    }

    @Test
    fun `a price drop - floored percent, at least 1 - the same, dearer or under 1 percent is none`() {
        assertEquals(WishlistPolicy.PriceDrop(toman(300_000), 15), WishlistPolicy.priceDropOf(toman(1_990_000), toman(1_690_000)))
        assertEquals(WishlistPolicy.PriceDrop(toman(19_900), 1), WishlistPolicy.priceDropOf(toman(1_990_000), toman(1_970_100)))
        assertNull(WishlistPolicy.priceDropOf(toman(2_000_000), toman(1_990_000)), "half a percent")
        assertNull(WishlistPolicy.priceDropOf(toman(1_990_000), toman(1_990_000)))
        assertNull(WishlistPolicy.priceDropOf(toman(1_990_000), toman(2_100_000)))
        assertEquals(WishlistPolicy.PriceDrop(toman(500), 100), WishlistPolicy.priceDropOf(toman(500), Money.ZERO))
    }

    @Test
    fun `a share token is wl_ and 32 base62 characters - anything else is none - never logged whole`() {
        val good = "wl_" + "aB3".repeat(10) + "xY"

        assertEquals(good, WishlistShareToken.makeNew(good)?.value)
        assertNull(WishlistShareToken.makeNew(good.dropLast(1)))
        assertNull(WishlistShareToken.makeNew(good + "z"))
        assertNull(WishlistShareToken.makeNew(good.replace("aB3", "a-3")))
        assertNull(WishlistShareToken.makeNew("WL_" + good.drop(3)))
        assertEquals("WishlistShareToken(wl_aB3…)", WishlistShareToken.makeNew(good).toString())
    }
}
