package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.campaign.usecase.command.CreateCampaignUc
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
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CampaignUpsertHttpRequest
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
)

/** The path parameter of a campaign. */
private const val CAMPAIGN_ID = "campaignId"

/**
 * The panel's marketing (contract tag `Admin · Marketing`, `[ADMIN, CONTENT_EDITOR]`): campaigns. Mounted under
 * `/api/v1`. An id that can't be one, or nobody has, is 404. Making and replacing answer with the campaign as it is now.
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
    }
}
