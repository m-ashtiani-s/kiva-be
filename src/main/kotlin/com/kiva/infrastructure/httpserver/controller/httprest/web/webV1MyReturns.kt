package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.returnrequest.usecase.command.CreateReturnUc
import com.kiva.application.usecase.returnrequest.usecase.query.GetMyReturnUc
import com.kiva.application.usecase.returnrequest.usecase.query.ListMyReturnsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CreateReturnHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReturnRequestHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The use cases of a customer's returns, as the routes need them. */
class MyReturnUseCases(
    val createReturn: CreateReturnUc,
    val listMyReturns: ListMyReturnsUc,
    val getMyReturn: GetMyReturnUc,
)

/** The path parameter of the order a return is asked for. */
private const val ORDER_CODE = "orderCode"

/**
 * A customer's returns (contract tag `Orders`): asking for one, and «مرجوعی‌های من». Mounted under `/api/v1`; a login is
 * needed, and an order is always the token's customer's (someone else's is the same 404 as none). The photos are
 * uploaded first with `POST /me/uploads` (webV1MyUploads).
 */
fun Route.webV1MyReturns(useCases: MyReturnUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
        // The answer is the return as «مرجوعی‌های من» shows it.
        post("/me/orders/{$ORDER_CODE}/returns") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<CreateReturnHttpRequest>()
            val command = CreateReturnUc.Cmd(
                userId = userId,
                orderCode = call.parameters[ORDER_CODE].orEmpty(),
                items = request.items?.map { CreateReturnUc.ItemFields(it?.orderItemId, it?.quantity) },
                reason = request.reason,
                description = request.description,
                mediaIds = request.mediaIds?.map { it.orEmpty() },
                refundMethod = request.refundMethod,
            )
            val returnId = useCases.createReturn.execute(command).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getMyReturn.execute(GetMyReturnUc.Qry(userId, returnId)), HttpStatusCode.Created) {
                ReturnRequestHttpResponse.from(it, mediaUrls)
            }
        }

        // A plain array, newest first, at most 50: not a page.
        get("/me/returns") {
            call.respondResult(useCases.listMyReturns.execute(ListMyReturnsUc.Qry(call.accessPrincipal().userId))) { returns ->
                returns.map { ReturnRequestHttpResponse.from(it, mediaUrls) }
            }
        }
    }
}
