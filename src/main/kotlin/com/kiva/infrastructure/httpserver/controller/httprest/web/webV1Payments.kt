package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentResultUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PaymentResultHttpResponse
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlin.coroutines.cancellation.CancellationException

/** The use cases of payments, as the routes need them. */
class PaymentUseCases(
    val handleGatewayCallback: HandleGatewayCallbackUc,
    val getPaymentResult: GetPaymentResultUc,
)

/** The path parameters. */
private const val GATEWAY = "gateway"
private const val PAYMENT_ID = "paymentId"

/** Only for the one log line of a callback that broke; the routes are functions, not a class. */
private object PaymentRoutes

private val logger = PaymentRoutes.getLogger()

/**
 * Payments (contract tag `Checkout & Payment`). Mounted under `/api/v1`.
 *
 * - `GET` and `POST /payment-callbacks/{gateway}`: the gateway sends the shopper's browser back here. No login (it
 *   comes from the bank's page), and **always** a 302 to the frontend's result page ([resultUrl], from the config, never
 *   from the request), with `?paymentId=` when the attempt was found and without it otherwise. Never JSON, never an
 *   error page: even a crash sends the shopper to the result page, where «سفارش‌های من» shows how it went.
 * - `GET /payments/{paymentId}`: how the attempt went, for that page; the customer's own only.
 */
fun Route.webV1Payments(useCases: PaymentUseCases, resultUrl: String) {
    route("/payment-callbacks/{$GATEWAY}") {
        get { call.backFromGateway(useCases, resultUrl, call.request.queryParameters) }
        // Saman and Mellat post a form; its fields come on top of the address's query.
        post {
            val form = try {
                call.receiveParameters()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Parameters.Empty
            }
            call.backFromGateway(useCases, resultUrl, Parameters.build { appendAll(call.request.queryParameters); appendAll(form) })
        }
    }

    authenticate(ACCESS) {
        get("/payments/{$PAYMENT_ID}") {
            val query = GetPaymentResultUc.Qry(call.accessPrincipal().userId, call.parameters[PAYMENT_ID].orEmpty())
            call.respondResult(useCases.getPaymentResult.execute(query)) { PaymentResultHttpResponse.from(it) }
        }
    }
}

private suspend fun ApplicationCall.backFromGateway(useCases: PaymentUseCases, resultUrl: String, params: Parameters) {
    // Each parameter once: a gateway sends each once, and a repeated one is someone else's doing.
    val command = HandleGatewayCallbackUc.Cmd(parameters[GATEWAY].orEmpty(), params.names().associateWith { params[it].orEmpty() })
    val paymentId = try {
        useCases.handleGatewayCallback.execute(command).unwrapOr { it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("A payment callback broke; the shopper is sent to the result page without the payment's id", e)
        null
    }
    respondRedirect(resultUrlOf(resultUrl, paymentId), permanent = false)
}

/** The frontend's result page, with the attempt's id (ours, so of a known format) when there is one. */
private fun resultUrlOf(resultUrl: String, paymentId: PaymentPublicId?): String =
    URLBuilder(resultUrl).apply { paymentId?.let { parameters.append(PAYMENT_ID, it.value) } }.buildString()
