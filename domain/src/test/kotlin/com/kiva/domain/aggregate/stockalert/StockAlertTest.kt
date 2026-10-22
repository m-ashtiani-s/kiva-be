package com.kiva.domain.aggregate.stockalert

import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.StockAlertDomainService.Failure
import com.kiva.domain.aggregate.stockalert.StockAlertDomainService.ProductColors
import com.kiva.domain.aggregate.stockalert.StockAlertDomainService.Standing
import com.kiva.domain.aggregate.stockalert.entity.StockAlert
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.domain.aggregate.stockalert.repository.StockAlertIdGenerator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StockAlertTest {

    private val now = Instant.parse("2026-10-12T08:00:00Z")
    private val user = UserId.constructFromPersisted(7)
    private val service = StockAlertDomainService(object : StockAlertIdGenerator {
        private var next = 0L
        override fun nextStockAlertId() = StockAlertId.constructFromPersisted(++next)
    })

    private fun variant(n: Long) = VariantId.constructFromPersisted(n)
    private val bag = ProductColors(
        ProductId.constructFromPersisted(1),
        listOf(ProductColors.Color(variant(11), ColorKey.constructFromPersisted("lilac"), 0), ProductColors.Color(variant(12), ColorKey.constructFromPersisted("black"), 3)),
    )
    private val fresh = Standing(activeCount = 0, madeSince = emptyList(), alreadyActive = false)

    @Test
    fun `in the contract's order - the color, one per key, in stock, the limit, the hourly cap`() {
        val everything = Standing(activeCount = 30, madeSince = List(10) { now.minusSeconds(60) }, alreadyActive = true)

        assertEquals(Result.failure(Failure.VariantNotFound), service.subscribe(user, bag, variant(99), everything, now))
        assertEquals(Result.failure(Failure.AlreadySubscribed), service.subscribe(user, bag, variant(11), everything, now))
        assertEquals(Result.failure(Failure.InStock(listOf("black"))), service.subscribe(user, bag, null, fresh, now))
        assertEquals(Result.failure(Failure.LimitReached), service.subscribe(user, bag, variant(11), everything.copy(alreadyActive = false), now))
        assertEquals(Failure.InStock(listOf("black")).meta, mapOf("inStockColorKeys" to listOf("black")))
    }

    @Test
    fun `the 11th in an hour waits until the oldest of the ten leaves the hour - one made 61 minutes ago no longer counts`() {
        val made = List(10) { i -> now.minus(Duration.ofMinutes(50L - i)) }

        val refused = service.subscribe(user, bag, variant(11), fresh.copy(madeSince = made), now)

        assertEquals(Result.failure(Failure.TooMany(10 * 60)), refused)
        val older = made.drop(1) + now.minus(Duration.ofMinutes(61))
        assertTrue(service.subscribe(user, bag, variant(11), fresh.copy(madeSince = older), now).isSuccess)
    }

    @Test
    fun `a new one is ACTIVE - called off once - told once, a second time stops loudly`() {
        val alert = service.subscribe(user, bag, variant(11), fresh, now).getOrNull()!!
        assertEquals(StockAlertStatus.ACTIVE, alert.status)

        val told = StockAlert.constructFromPersisted(alert.id, user, bag.productId, null, StockAlertStatus.ACTIVE, now, null, null, null)
        told.markNotified(variant(12), now)
        assertEquals(listOf(StockAlertStatus.NOTIFIED, variant(12)), listOf(told.status, told.notifiedVariantId))
        assertThrows<IllegalStateException> { told.markNotified(variant(12), now) }
        assertFalse(told.cancel(now), "a told one isn't called off")

        assertTrue(alert.cancel(now))
        assertFalse(alert.cancel(now))
        assertEquals(StockAlertStatus.CANCELLED, alert.status)
    }

    @Test
    fun `quiet from 22 to 8 in Tehran - 21-59 and 8-00 are not`() {
        fun at(utc: String) = StockAlertPolicy.isQuietHour(Instant.parse(utc))
        // Tehran is UTC+3:30.
        assertEquals(listOf(false, true, true, true, false), listOf(at("2026-10-12T18:29:00Z"), at("2026-10-12T18:30:00Z"), at("2026-10-12T22:00:00Z"), at("2026-10-13T04:29:00Z"), at("2026-10-13T04:30:00Z")))
    }
}
