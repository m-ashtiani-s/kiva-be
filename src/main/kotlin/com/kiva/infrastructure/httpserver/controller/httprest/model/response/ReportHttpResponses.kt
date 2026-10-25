package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ReportOverviewDto
import com.kiva.application.usecase.order.OrderItems
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `AdminReportOverview`: amounts in toman, shares with one decimal written from tenths of a percent
 * (PerMilleAsPercentSerializer), and the two mixes with every carrier and messenger, zero too.
 */
@Serializable
data class AdminReportOverviewHttpResponse(
    val from: String,
    val to: String,
    val revenue: Long,
    val ordersCount: Int,
    val averageOrderValue: Long,
    val newCustomers: Int,
    @Serializable(with = PerMilleAsPercentSerializer::class)
    val reservationShare: Int,
    @Serializable(with = PerMilleAsPercentSerializer::class)
    val mediaApprovalRate: Int,
    @Serializable(with = PerMilleAsPercentSerializer::class)
    val changeRequestRate: Int,
    @Serializable(with = PerMilleAsPercentSerializer::class)
    val returnRate: Int,
    val discountTotal: Long,
    val shippingMix: Map<String, Int>,
    val messengerMix: Map<String, Int>,
    val topProducts: List<TopProductHttpResponse>,
    val dailySeries: List<DayHttpResponse>,
) {
    @Serializable
    data class TopProductHttpResponse(val product: ProductRefHttpResponse, val unitsSold: Int, val revenue: Long)

    @Serializable
    data class DayHttpResponse(val date: String, val revenue: Long, val orders: Int)

    companion object {
        fun from(report: ReportOverviewDto, urls: MediaUrls) = AdminReportOverviewHttpResponse(
            from = report.from.toString(),
            to = report.to.toString(),
            revenue = report.revenue.toman,
            ordersCount = report.ordersCount,
            averageOrderValue = report.averageOrderValue.toman,
            newCustomers = report.newCustomers,
            reservationShare = report.reservationSharePerMille,
            mediaApprovalRate = report.mediaApprovalRatePerMille,
            changeRequestRate = report.changeRequestRatePerMille,
            returnRate = report.returnRatePerMille,
            discountTotal = report.discountTotal.toman,
            shippingMix = report.shippingMix.mapKeys { it.key.name },
            messengerMix = report.messengerMix.mapKeys { it.key.name },
            topProducts = report.topProducts.map {
                TopProductHttpResponse(
                    product = ProductRefHttpResponse(
                        id = it.id,
                        slug = it.slug,
                        sku = it.sku,
                        name = it.name,
                        imageUrl = it.image?.let { photo -> urls.of(photo.pathFor(OrderItems.IMAGE_WIDTH)) },
                        colorKey = null,
                    ),
                    unitsSold = it.unitsSold,
                    revenue = it.revenue.toman,
                )
            },
            dailySeries = report.dailySeries.map { DayHttpResponse(it.date.toString(), it.revenue.toman, it.orders) },
        )
    }
}

/** Contract schema `ProductRef`: a product named somewhere else (a best seller, later a review). [colorKey] when it is about one color. */
@Serializable
data class ProductRefHttpResponse(
    val id: Long,
    val slug: String,
    val sku: String,
    val name: String,
    val imageUrl: String?,
    val colorKey: String?,
)
