package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.banner.usecase.query.ListBannersUc
import com.kiva.application.usecase.campaign.usecase.query.GetActiveCampaignUc
import com.kiva.application.usecase.content.usecase.query.GetSiteStatsUc
import com.kiva.application.usecase.content.usecase.query.GetHomePageUc
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.HomePageHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SiteStatsHttpResponse
import com.kiva.application.usecase.testimonial.usecase.query.ListTestimonialsUc
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.TestimonialListHttpResponse
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BannerHttpResponse
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CampaignHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The one order of the testimonials (the contract has no `sort`). */
private const val TESTIMONIAL_ORDER = "sortOrder"

/** The use cases of the home page and what it is made of, as the routes need them. */
class HomeUseCases(
    val activeCampaign: GetActiveCampaignUc,
    val banners: ListBannersUc,
    val testimonials: ListTestimonialsUc,
    val siteStats: GetSiteStatsUc,
    val home: GetHomePageUc,
)

/**
 * The home page and its parts (contract tag `Config & Home`), public: the home page in one answer, the running
 * campaign, the banners of a placement, the testimonials and the site's numbers. Mounted under `/api/v1`.
 * Nothing here reads a login, and only `/home` is cached (5 minutes, ETag; the countdown's `serverTime` must be fresh).
 */
fun Route.webV1Home(useCases: HomeUseCases, mediaUrls: MediaUrls, siteUrl: String) {
    // A login token is not even read: the answer is the same for everyone, so no heart is ever on it.
    get("/home") {
        call.respondResultCacheable(useCases.home.execute(GetHomePageUc.Qry)) { HomePageHttpResponse.from(it, mediaUrls, siteUrl) }
    }

    get("/campaigns/active") {
        val campaign = useCases.activeCampaign.execute(GetActiveCampaignUc.Qry).unwrapOr { return@get call.respondFailure(it) }
        if (campaign == null) call.respond(HttpStatusCode.NoContent) else call.respond(CampaignHttpResponse.from(campaign, mediaUrls))
    }

    get("/banners") {
        call.respondResult(useCases.banners.execute(ListBannersUc.Qry(call.request.queryParameters["placement"]))) { list ->
            list.map { BannerHttpResponse.from(it, mediaUrls) }
        }
    }

    get("/testimonials") {
        val pageable = call.pageable(allowedSorts = setOf(TESTIMONIAL_ORDER), defaultSort = TESTIMONIAL_ORDER, defaultSize = 20, maxSize = 100)
            .unwrapOr { return@get call.respondFailure(it) }
        call.respondResult(useCases.testimonials.execute(ListTestimonialsUc.Qry(pageable))) { TestimonialListHttpResponse.from(it, mediaUrls) }
    }

    get("/site/stats") {
        call.respondResult(useCases.siteStats.execute(GetSiteStatsUc.Qry)) { SiteStatsHttpResponse.from(it) }
    }
}
