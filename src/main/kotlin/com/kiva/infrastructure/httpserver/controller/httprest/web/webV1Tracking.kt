package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.tracking.usecase.query.ListDailyShipmentDaysUc
import com.kiva.application.usecase.tracking.usecase.query.ListDailyShipmentsUc
import com.kiva.application.usecase.tracking.usecase.query.LookupGuestTrackingUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.GuestTrackingHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.DailyShipmentDayHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.DailyShipmentListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.GuestTrackingHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The limit per address of `POST /tracking/lookup` (registered in configureHttp): a code and a phone can't be guessed. */
val TRACKING_LOOKUP_RATE_LIMIT = RateLimitName("tracking-lookup")

/** The use cases of the public tracking pages, as the routes need them. */
class TrackingUseCases(
    val lookupGuestTracking: LookupGuestTrackingUc,
    val listDailyShipmentDays: ListDailyShipmentDaysUc,
    val listDailyShipments: ListDailyShipmentsUc,
)

/**
 * Tracking without logging in (contract tag `Tracking`), public, mounted under `/api/v1`: a guest's look at one order,
 * and the daily list of parcels, their recipients masked.
 */
fun Route.webV1Tracking(useCases: TrackingUseCases, mediaUrls: MediaUrls) {
    // The phone goes in the body, never in the URL.
    rateLimit(TRACKING_LOOKUP_RATE_LIMIT) {
        post("/tracking/lookup") {
            val request = call.receive<GuestTrackingHttpRequest>()
            val query = LookupGuestTrackingUc.Qry(request.orderCode, request.phone)
            call.respondResult(useCases.lookupGuestTracking.execute(query)) { GuestTrackingHttpResponse.from(it, mediaUrls) }
        }
    }

    get("/tracking/daily/days") {
        val query = ListDailyShipmentDaysUc.Qry(call.request.queryParameters["days"])
        // The first day is always today.
        call.respondResult(useCases.listDailyShipmentDays.execute(query)) { days -> days.map { DailyShipmentDayHttpResponse.from(it, days.first().date) } }
    }

    get("/tracking/daily") {
        val pageable = call.pageable(defaultSize = 100, maxSize = 200).unwrapOr { return@get call.respondFailure(it) }
        val params = call.request.queryParameters
        call.respondResult(useCases.listDailyShipments.execute(ListDailyShipmentsUc.Qry(params["date"], params["q"], pageable))) {
            DailyShipmentListHttpResponse.from(it)
        }
    }
}
