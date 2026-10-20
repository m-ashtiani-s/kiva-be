package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.settings.usecase.command.UpdateSettingsUc
import com.kiva.application.usecase.settings.usecase.query.GetAdminSettingsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.AdminSettingsHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminSettingsHttpResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** The use cases of the panel's settings page, as the routes need them. */
class AdminSettingsUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val getSettings: GetAdminSettingsUc,
    val updateSettings: UpdateSettingsUc,
)

/**
 * The shop's settings (contract tag `Admin · Content`, `[ADMIN]`). Mounted under `/api/v1`. `PUT` takes the whole set and
 * answers with the whole set as it is now, the same document `GET` gives.
 */
fun Route.webV1AdminSettings(useCases: AdminSettingsUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        get("/admin/settings") {
            val query = GetAdminSettingsUc.Qry(call.panelActor().userId)
            call.respondResult(useCases.getSettings.execute(query)) { AdminSettingsHttpResponse.from(it) }
        }
        put("/admin/settings") {
            val request = call.receive<AdminSettingsHttpRequest>()
            val actor = call.panelActor()
            useCases.updateSettings.execute(UpdateSettingsUc.Cmd(request.toFields(), actor)).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getSettings.execute(GetAdminSettingsUc.Qry(actor.userId))) { AdminSettingsHttpResponse.from(it) }
        }
    }
}
