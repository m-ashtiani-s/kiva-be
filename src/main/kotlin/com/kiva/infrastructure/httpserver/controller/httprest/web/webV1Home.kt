package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.banner.usecase.query.ListBannersUc
import com.kiva.application.usecase.campaign.usecase.query.GetActiveCampaignUc
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

/** The use cases of the home page and what it is made of, as the routes need them. */
class HomeUseCases(
    val activeCampaign: GetActiveCampaignUc,
    val banners: ListBannersUc,
)

/**
 * The home page and its parts (contract tag `Config & Home`), public: the running campaign and the banners of a
 * placement. Mounted under `/api/v1`.
 * Nothing here reads a login, and only `/home` is cached (the countdown's `serverTime` must be fresh).
 */
fun Route.webV1Home(useCases: HomeUseCases, mediaUrls: MediaUrls) {
    get("/campaigns/active") {
        val campaign = useCases.activeCampaign.execute(GetActiveCampaignUc.Qry).unwrapOr { return@get call.respondFailure(it) }
        if (campaign == null) call.respond(HttpStatusCode.NoContent) else call.respond(CampaignHttpResponse.from(campaign, mediaUrls))
    }

    get("/banners") {
        call.respondResult(useCases.banners.execute(ListBannersUc.Qry(call.request.queryParameters["placement"]))) { list ->
            list.map { BannerHttpResponse.from(it, mediaUrls) }
        }
    }
}
