package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AccountDashboardHttpResponse
import com.kiva.application.usecase.account.usecase.query.GetMyDashboardUc
import com.kiva.application.usecase.account.usecase.command.ConfirmPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.DeleteMyAccountUc
import com.kiva.application.usecase.account.usecase.command.RequestAccountDeletionUc
import com.kiva.application.usecase.account.usecase.command.RequestPhoneChangeUc
import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.auth.clearRefreshCookie
import com.kiva.infrastructure.httpserver.auth.setRefreshCookie
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.DeleteAccountHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.PhoneChangeRequestHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.PhoneChangeVerifyHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.UpdateProfileHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PhoneChangeHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PhoneChangeOtpHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.SendOtpHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.UserHttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.userAgent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/** The use cases of the account pages, as the routes need them. */
class MeUseCases(
    val getMe: GetMeUc,
    val updateMyProfile: UpdateMyProfileUc,
    val requestPhoneChange: RequestPhoneChangeUc,
    val confirmPhoneChange: ConfirmPhoneChangeUc,
    val requestAccountDeletion: RequestAccountDeletionUc,
    val deleteMyAccount: DeleteMyAccountUc,
)

/**
 * The logged-in user's own account (contract tag `Account`). Mounted under `/api/v1`; every route here needs a login,
 * and the user is always the token's, never one named in the request. Deleting the account is two steps: a code
 * (`POST /me/deletion/request`), then `DELETE /me` with it.
 */
fun Route.webV1Me(useCases: MeUseCases) {
    authenticate(ACCESS) {
        get("/me") {
            call.respondResult(useCases.getMe.execute(GetMeUc.Qry(call.accessPrincipal().userId))) { UserHttpResponse.from(it) }
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
            useCases.updateMyProfile.execute(command).unwrapOr { return@patch call.respondFailure(it) }
            call.respondResult(useCases.getMe.execute(GetMeUc.Qry(userId))) { UserHttpResponse.from(it) }
        }

        // Sends codes, so it shares the limit per address of the login codes.
        rateLimit(OTP_SEND_RATE_LIMIT) {
            post("/me/phone-change/request") {
                val caller = call.accessPrincipal()
                val request = call.receive<PhoneChangeRequestHttpRequest>()
                val command = RequestPhoneChangeUc.Cmd(caller.userId, caller.sessionId, request.newPhone, ip = call.request.origin.remoteAddress)
                call.respondResult(useCases.requestPhoneChange.execute(command)) { PhoneChangeOtpHttpResponse.of(it) }
            }
        }

        // Deleting the account (phase 14): a code to the account's own phone, after what would stop it was checked.
        // It sends a code, so it shares the limit per address of the login codes.
        rateLimit(OTP_SEND_RATE_LIMIT) {
            post("/me/deletion/request") {
                val command = RequestAccountDeletionUc.Cmd(call.accessPrincipal().userId, ip = call.request.origin.remoteAddress)
                call.respondResult(useCases.requestAccountDeletion.execute(command)) { SendOtpHttpResponse.of(it) }
            }
        }

        // Every session ends, and this browser forgets its refresh token: 204 with the cookie cleared.
        delete("/me") {
            val request = call.receive<DeleteAccountHttpRequest>()
            val command = DeleteMyAccountUc.Cmd(call.accessPrincipal().userId, request.otpCode, request.reason, ip = call.request.origin.remoteAddress)
            useCases.deleteMyAccount.execute(command).unwrapOr { return@delete call.respondFailure(it) }
            call.response.clearRefreshCookie()
            call.respond(HttpStatusCode.NoContent)
        }

        // Every session ends; this device gets a new one, its refresh token in the cookie.
        post("/me/phone-change/verify") {
            val caller = call.accessPrincipal()
            val request = call.receive<PhoneChangeVerifyHttpRequest>()
            val command = ConfirmPhoneChangeUc.Cmd(
                userId = caller.userId,
                sessionId = caller.sessionId,
                newPhone = request.newPhone,
                currentPhoneCode = request.currentPhoneCode,
                newPhoneCode = request.newPhoneCode,
                deviceName = request.device?.name,
                userAgent = call.request.userAgent(),
                ip = call.request.origin.remoteAddress,
            )
            val session = useCases.confirmPhoneChange.execute(command).unwrapOr { return@post call.respondFailure(it) }
            val user = useCases.getMe.execute(GetMeUc.Qry(session.userId)).unwrapOr { return@post call.respondFailure(it) }

            session.refreshToken?.let { call.response.setRefreshCookie(it, session.refreshTokenMaxAge) }
            call.respond(PhoneChangeHttpResponse.of(session, user))
        }
    }
}

/**
 * The account's header (`GET /me/dashboard`): the greeting, the four stat cards and the side menu's counts, each the
 * same number as the page it links to (roadmap section 14.2, decision 16). A login is needed.
 */
fun Route.webV1MyDashboard(getMyDashboard: GetMyDashboardUc) {
    authenticate(ACCESS) {
        get("/me/dashboard") {
            call.respondResult(getMyDashboard.execute(GetMyDashboardUc.Qry(call.accessPrincipal().userId))) { AccountDashboardHttpResponse.from(it) }
        }
    }
}
