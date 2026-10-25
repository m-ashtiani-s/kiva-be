package com.kiva.application.dto

import com.kiva.application.repository.TopProductQm
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.LocalDate

/**
 * The panel's report of a range of Tehran days (the contract's AdminReportOverview; roadmap section 9.3). Shares are in
 * tenths of a percent (972 is the contract's 97.2), so no number here is a Double.
 *
 * @property shippingMix every carrier, zero too. @property messengerMix every messenger, zero too.
 * @property dailySeries every day of the range in order, zero too.
 */
data class ReportOverviewDto(
    val from: LocalDate,
    val to: LocalDate,
    val revenue: Money,
    val ordersCount: Int,
    val averageOrderValue: Money,
    val newCustomers: Int,
    val reservationSharePerMille: Int,
    val mediaApprovalRatePerMille: Int,
    val changeRequestRatePerMille: Int,
    val returnRatePerMille: Int,
    val discountTotal: Money,
    val shippingMix: Map<ShippingMethodCode, Int>,
    val messengerMix: Map<MessengerChannel, Int>,
    val topProducts: List<TopProduct>,
    val dailySeries: List<Day>,
) {
    /** A best seller as the contract's ProductRef shows it, with what it sold. */
    data class TopProduct(
        val id: Long,
        val slug: String,
        val sku: String,
        val name: String,
        val image: UploadedMediaDto?,
        val unitsSold: Int,
        val revenue: Money,
    ) {
        companion object {
            fun from(qm: TopProductQm) =
                TopProduct(qm.productId.value, qm.slug.value, qm.sku.value, qm.name.value, qm.image, qm.unitsSold, qm.revenue)
        }
    }

    data class Day(val date: LocalDate, val revenue: Money, val orders: Int)
}
