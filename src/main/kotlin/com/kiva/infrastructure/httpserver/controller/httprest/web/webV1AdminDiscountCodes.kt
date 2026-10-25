package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.discount.usecase.command.CreateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.command.DeactivateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.command.UpdateDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.query.GetDiscountCodeUc
import com.kiva.application.usecase.discount.usecase.query.ListDiscountCodesUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.DiscountCodeUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.DiscountCodeHttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's discount codes, as the routes need them. */
class AdminDiscountCodeUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val listCodes: ListDiscountCodesUc,
    val getCode: GetDiscountCodeUc,
    val createCode: CreateDiscountCodeUc,
    val updateCode: UpdateDiscountCodeUc,
    val deactivateCode: DeactivateDiscountCodeUc,
)

/** The path parameter of a discount code. */
private const val DISCOUNT_ID = "discountId"

/**
 * Discount codes in the panel (contract tag `Admin · Marketing`, `[ADMIN]`). Mounted under `/api/v1`. A code id that
 * isn't a positive number, or that nobody has, is 404. Making and editing answer with the code as it is now.
 */
fun Route.webV1AdminDiscountCodes(useCases: AdminDiscountCodeUseCases) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN) {
        get("/admin/discount-codes") {
            val pageable = call.pageable(allowedSorts = setOf(DiscountCodeRepository.NEWEST), defaultSort = DiscountCodeRepository.NEWEST)
                .unwrapOr { return@get call.respondFailure(it) }
            val query = ListDiscountCodesUc.Qry(pageable, active = call.request.queryParameters["active"])
            call.respondResult(useCases.listCodes.execute(query)) { page -> page.toHttpResponse(DiscountCodeHttpResponse::from) }
        }
        post("/admin/discount-codes") {
            val request = call.receive<DiscountCodeUpsertHttpRequest>()
            val id = useCases.createCode.execute(CreateDiscountCodeUc.Cmd(request.toFields(), call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getCode.execute(GetDiscountCodeUc.Qry(id)), HttpStatusCode.Created) { DiscountCodeHttpResponse.from(it) }
        }
        put("/admin/discount-codes/{$DISCOUNT_ID}") {
            val request = call.receive<DiscountCodeUpsertHttpRequest>()
            val command = UpdateDiscountCodeUc.Cmd(call.parameters[DISCOUNT_ID].orEmpty(), request.toFields(), call.panelActor())
            val id = useCases.updateCode.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getCode.execute(GetDiscountCodeUc.Qry(id))) { DiscountCodeHttpResponse.from(it) }
        }
        delete("/admin/discount-codes/{$DISCOUNT_ID}") {
            val command = DeactivateDiscountCodeUc.Cmd(call.parameters[DISCOUNT_ID].orEmpty(), call.panelActor())
            call.respondResult(useCases.deactivateCode.execute(command))
        }
    }
}
