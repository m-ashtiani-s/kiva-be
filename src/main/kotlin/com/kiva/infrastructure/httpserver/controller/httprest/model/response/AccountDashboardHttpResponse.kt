package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.usecase.account.usecase.query.GetMyDashboardUc
import kotlinx.serialization.Serializable

/** The Persian words of the account's header (roadmap section 14.5). */
object AccountTexts {
    /** «سلام سارا», or «سلام دوست عزیز» before the customer says what to call them. */
    fun greeting(firstName: String?): String = "سلام ${firstName ?: UserHttpResponse.NAMELESS}"
}

/** Contract schema `AccountDashboard`: every field always there (decision 16). */
@Serializable
data class AccountDashboardHttpResponse(
    val greeting: String,
    val user: UserHttpResponse,
    val stats: Stats,
    val navCounts: NavCounts,
    val activeReservation: ReservationInfoHttpResponse?,
    val pendingReviewCount: Int,
) {
    @Serializable
    data class Stats(val ordersTotal: Int, val ordersActive: Int, val mediaReceived: Int, val wishlistCount: Int)

    @Serializable
    data class NavCounts(val orders: Int, val addresses: Int, val wishlist: Int, val reviews: Int)

    companion object {
        fun from(dashboard: GetMyDashboardUc.Dashboard) = AccountDashboardHttpResponse(
            greeting = AccountTexts.greeting(dashboard.user.firstName),
            user = UserHttpResponse.from(dashboard.user),
            stats = Stats(dashboard.ordersTotal, dashboard.ordersActive, dashboard.mediaReceived, dashboard.wishlistCount),
            navCounts = NavCounts(dashboard.ordersTotal, dashboard.addressCount, dashboard.wishlistCount, dashboard.reviewCount),
            activeReservation = dashboard.activeReservation?.let(ReservationInfoHttpResponse::from),
            pendingReviewCount = dashboard.pendingReviewCount,
        )
    }
}
