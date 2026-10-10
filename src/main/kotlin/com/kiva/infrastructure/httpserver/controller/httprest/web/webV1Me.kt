package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.UpdateProfileHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.UserHttpResponse
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch

/**
 * The logged-in user's own account (contract tag `Account`). Mounted under `/api/v1`; every route here needs a login,
 * and the user is always the token's, never one named in the request.
 */
fun Route.webV1Me(getMeUc: GetMeUc, updateMyProfileUc: UpdateMyProfileUc) {
    authenticate(ACCESS) {
        get("/me") {
            call.respondResult(getMeUc.execute(GetMeUc.Qry(call.accessPrincipal().userId))) { UserHttpResponse.from(it) }
        }

        // Only the fields sent change; the answer is the whole profile as it is now.
        patch("/me") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<UpdateProfileHttpRequest>()
            val command = UpdateMyProfileUc.Cmd(
                userId = userId,
                firstName = request.firstName,
                lastName = request.lastName,
                email = request.email,
                birthDate = request.birthDate,
                gender = request.gender,
                defaultMessenger = request.defaultMessenger,
                defaultMessengerPhone = request.defaultMessengerPhone,
                marketingSmsOptIn = request.marketingSmsOptIn,
            )
            updateMyProfileUc.execute(command).unwrapOr { return@patch call.respondFailure(it) }
            call.respondResult(getMeUc.execute(GetMeUc.Qry(userId))) { UserHttpResponse.from(it) }
        }
    }
}
