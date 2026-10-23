package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.banner.usecase.command.CreateBannerUc
import com.kiva.application.usecase.banner.usecase.command.DeleteBannerUc
import com.kiva.application.usecase.banner.usecase.command.UpdateBannerUc
import com.kiva.application.usecase.banner.usecase.query.GetAdminBannerUc
import com.kiva.application.usecase.banner.usecase.query.ListAdminBannersUc
import com.kiva.application.usecase.campaign.usecase.command.CreateCampaignUc
import com.kiva.application.usecase.testimonial.usecase.command.CreateTestimonialUc
import com.kiva.application.usecase.testimonial.usecase.command.DeleteTestimonialUc
import com.kiva.application.usecase.testimonial.usecase.command.UpdateTestimonialUc
import com.kiva.application.usecase.testimonial.usecase.query.GetAdminTestimonialUc
import com.kiva.application.usecase.testimonial.usecase.query.ListAdminTestimonialsUc
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.TestimonialUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminTestimonialHttpResponse
import com.kiva.application.usecase.campaign.usecase.command.DeleteCampaignUc
import com.kiva.application.usecase.campaign.usecase.command.UpdateCampaignUc
import com.kiva.application.usecase.campaign.usecase.query.GetAdminCampaignUc
import com.kiva.application.usecase.campaign.usecase.query.ListAdminCampaignsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.BannerUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CampaignUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.BannerHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminCampaignHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's marketing, as the routes need them. */
class AdminMarketingUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val listCampaigns: ListAdminCampaignsUc,
    val getCampaign: GetAdminCampaignUc,
    val createCampaign: CreateCampaignUc,
    val updateCampaign: UpdateCampaignUc,
    val deleteCampaign: DeleteCampaignUc,
    val listBanners: ListAdminBannersUc,
    val getBanner: GetAdminBannerUc,
    val createBanner: CreateBannerUc,
    val updateBanner: UpdateBannerUc,
    val deleteBanner: DeleteBannerUc,
    val listTestimonials: ListAdminTestimonialsUc,
    val getTestimonial: GetAdminTestimonialUc,
    val createTestimonial: CreateTestimonialUc,
    val updateTestimonial: UpdateTestimonialUc,
    val deleteTestimonial: DeleteTestimonialUc,
)

/** The path parameter of a campaign. */
private const val CAMPAIGN_ID = "campaignId"

/** The path parameter of a banner. */
private const val BANNER_ID = "bannerId"

/** The path parameter of a testimonial. */
private const val TESTIMONIAL_ID = "testimonialId"

/** The one order of the testimonials (the contract has no `sort`). */
private const val PANEL_ORDER = "sortOrder"

/**
 * The panel's marketing (contract tag `Admin · Marketing`, `[ADMIN, CONTENT_EDITOR]`): campaigns, banners and testimonials. Mounted under
 * `/api/v1`. An id that can't be one, or nobody has, is 404. Making and replacing answer with the thing as it is now.
 */
fun Route.webV1AdminMarketing(useCases: AdminMarketingUseCases, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CONTENT_EDITOR) {
        get("/admin/campaigns") {
            call.respondResult(useCases.listCampaigns.execute(ListAdminCampaignsUc.Qry)) { list -> list.map { AdminCampaignHttpResponse.from(it, mediaUrls) } }
        }
        post("/admin/campaigns") {
            val request = call.receive<CampaignUpsertHttpRequest>()
            val id = useCases.createCampaign.execute(CreateCampaignUc.Cmd(request.toFields(), call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getCampaign.execute(GetAdminCampaignUc.Qry(id)), HttpStatusCode.Created) { AdminCampaignHttpResponse.from(it, mediaUrls) }
        }
        put("/admin/campaigns/{$CAMPAIGN_ID}") {
            val request = call.receive<CampaignUpsertHttpRequest>()
            val command = UpdateCampaignUc.Cmd(call.parameters[CAMPAIGN_ID].orEmpty(), request.toFields(), call.panelActor())
            val id = useCases.updateCampaign.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getCampaign.execute(GetAdminCampaignUc.Qry(id))) { AdminCampaignHttpResponse.from(it, mediaUrls) }
        }
        delete("/admin/campaigns/{$CAMPAIGN_ID}") {
            call.respondResult(useCases.deleteCampaign.execute(DeleteCampaignUc.Cmd(call.parameters[CAMPAIGN_ID].orEmpty(), call.panelActor())))
        }

        get("/admin/banners") {
            call.respondResult(useCases.listBanners.execute(ListAdminBannersUc.Qry)) { list -> list.map { BannerHttpResponse.from(it, mediaUrls) } }
        }
        post("/admin/banners") {
            val request = call.receive<BannerUpsertHttpRequest>()
            val id = useCases.createBanner.execute(CreateBannerUc.Cmd(request.toFields(), call.panelActor())).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getBanner.execute(GetAdminBannerUc.Qry(id)), HttpStatusCode.Created) { BannerHttpResponse.from(it, mediaUrls) }
        }
        put("/admin/banners/{$BANNER_ID}") {
            val request = call.receive<BannerUpsertHttpRequest>()
            val command = UpdateBannerUc.Cmd(call.parameters[BANNER_ID].orEmpty(), request.toFields(), call.panelActor())
            val id = useCases.updateBanner.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getBanner.execute(GetAdminBannerUc.Qry(id))) { BannerHttpResponse.from(it, mediaUrls) }
        }
        delete("/admin/banners/{$BANNER_ID}") {
            call.respondResult(useCases.deleteBanner.execute(DeleteBannerUc.Cmd(call.parameters[BANNER_ID].orEmpty(), call.panelActor())))
        }

        get("/admin/testimonials") {
            val pageable = call.pageable(allowedSorts = setOf(PANEL_ORDER), defaultSort = PANEL_ORDER, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val query = ListAdminTestimonialsUc.Qry(pageable, call.request.queryParameters["published"])
            call.respondResult(useCases.listTestimonials.execute(query)) { page -> page.toHttpResponse { AdminTestimonialHttpResponse.from(it, mediaUrls) } }
        }
        post("/admin/testimonials") {
            val request = call.receive<TestimonialUpsertHttpRequest>()
            val id = useCases.createTestimonial.execute(CreateTestimonialUc.Cmd(request.toFields(), call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getTestimonial.execute(GetAdminTestimonialUc.Qry(id)), HttpStatusCode.Created) { AdminTestimonialHttpResponse.from(it, mediaUrls) }
        }
        put("/admin/testimonials/{$TESTIMONIAL_ID}") {
            val request = call.receive<TestimonialUpsertHttpRequest>()
            val command = UpdateTestimonialUc.Cmd(call.parameters[TESTIMONIAL_ID].orEmpty(), request.toFields(), call.panelActor())
            val id = useCases.updateTestimonial.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getTestimonial.execute(GetAdminTestimonialUc.Qry(id))) { AdminTestimonialHttpResponse.from(it, mediaUrls) }
        }
        delete("/admin/testimonials/{$TESTIMONIAL_ID}") {
            call.respondResult(useCases.deleteTestimonial.execute(DeleteTestimonialUc.Cmd(call.parameters[TESTIMONIAL_ID].orEmpty(), call.panelActor())))
        }
    }
}
