package com.kiva.application.repository

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the panel's report counts (`GET /admin/reports/overview`, roadmap section 9.3): sums made by the database over
 * the order read model, never the orders loaded one by one. Every range is [from] inclusive to [until] exclusive; the use
 * case makes them from Tehran days.
 *
 * «سفارش دوره» (roadmap phase 4, decision 17): an order paid in the range (its first payment, `paidOn`) and not cancelled.
 * Each of its numbers is what the order shows now: paid at checkout plus a balance paid after a change, less what went
 * back; its lines after a change request.
 */
interface ReportQmRepository {

    /** The period's orders summed up; a carrier or messenger none of them used is left out of its map. */
    fun periodTotals(from: Instant, until: Instant): PeriodTotals

    /** The period's orders of each day in [zone] that has any. */
    fun dailyTotals(from: Instant, until: Instant, zone: ZoneId): Map<LocalDate, DayTotals>

    /** How many customers' first purchase that stands (OrderStatus.countsAsPurchase) was paid in the range. */
    fun newCustomers(from: Instant, until: Instant): Int

    /** The orders whose first photos were sent in the range, cancelled ones too (decision 36), and what came of them. */
    fun photoOutcomes(from: Instant, until: Instant): PhotoOutcomes

    /**
     * The [limit] products the period's orders sold most of, counted on their lines now; on a tie the smaller id first.
     * Each as the product is today (decision 37).
     */
    fun topProducts(from: Instant, until: Instant, limit: Int): List<TopProductQm>
}

/**
 * The period's orders together.
 *
 * @property revenue what they were paid (at checkout, and a balance after a change) less what went back.
 * @property discountTotal the discount codes took off, as the orders show it now (decision 35).
 * @property inReservation how many belong to a reservation group.
 */
data class PeriodTotals(
    val ordersCount: Int,
    val revenue: Money,
    val discountTotal: Money,
    val inReservation: Int,
    val byShippingMethod: Map<ShippingMethodCode, Int>,
    val byMessenger: Map<MessengerChannel, Int>,
) {
    companion object {
        val NONE = PeriodTotals(0, Money.ZERO, Money.ZERO, 0, emptyMap(), emptyMap())
    }
}

/** One day's orders of the period: the money they kept and how many. */
data class DayTotals(val revenue: Money, val orders: Int)

/** @property sent orders whose first photos were sent. @property changeRequested those of them a change was ever asked for. */
data class PhotoOutcomes(val sent: Int, val changeRequested: Int)

/**
 * One of the period's best sellers.
 *
 * @property image the first photo of its default color (videos left out), or null while it has none.
 * @property revenue its lines' price times quantity, before a discount code.
 */
data class TopProductQm(
    val productId: ProductId,
    val slug: Slug,
    val sku: Sku,
    val name: ProductName,
    val image: UploadedMediaDto?,
    val unitsSold: Int,
    val revenue: Money,
)
