package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.admin.usecase.command.SetUserRolesUc
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerDetailUc
import com.kiva.application.usecase.admin.usecase.query.GetCustomerUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.SetRolesHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminCustomerDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminCustomerHttpResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/** The use cases of the panel's customers page, as the routes need them. */
class AdminCustomerUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findCustomers: FindCustomersUc,
    val getCustomer: GetCustomerUc,
    val getCustomerDetail: GetCustomerDetailUc,
    val setUserRoles: SetUserRolesUc,
)

/** The panel's customers (contract tag `Admin · Support`). Mounted under `/api/v1`; each route takes its contract `x-roles`. */
fun Route.webV1AdminCustomers(useCases: AdminCustomerUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.SUPPORT) {
        get("/admin/customers") {
            val pageable = call.pageable(allowedSorts = setOf(AdminCustomerRepository.NEWEST), defaultSort = AdminCustomerRepository.NEWEST)
                .unwrapOr { return@get call.respondFailure(it) }
            val query = FindCustomersUc.Qry(pageable, q = call.request.queryParameters["q"], role = call.request.queryParameters["role"])
            call.respondResult(useCases.findCustomers.execute(query)) { page -> page.toHttpResponse(AdminCustomerHttpResponse::from) }
        }
        get("/admin/customers/{customerId}") {
            val query = GetCustomerDetailUc.Qry(call.pathParameters["customerId"].orEmpty())
            call.respondResult(useCases.getCustomerDetail.execute(query)) { AdminCustomerDetailHttpResponse.from(it) }
        }
    }

    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        // The answer is the customer as they are now, roles included.
        put("/admin/customers/{customerId}/roles") {
            val request = call.receive<SetRolesHttpRequest>()
            val command = SetUserRolesUc.Cmd(
                customerId = call.pathParameters["customerId"].orEmpty(),
                roles = request.roles,
                actor = call.panelActor(),
            )
            val customerId = useCases.setUserRoles.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getCustomer.execute(GetCustomerUc.Qry(customerId))) { AdminCustomerHttpResponse.from(it) }
        }
    }
}
