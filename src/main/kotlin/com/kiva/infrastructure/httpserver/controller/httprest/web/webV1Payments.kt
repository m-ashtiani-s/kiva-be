package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentAttemptUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentInitUc
import com.kiva.application.usecase.checkout.usecase.query.GetPaymentResultUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.PayAgainHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PaymentInitHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.PaymentResultHttpResponse
import com.kiva.infrastructure.httpserver.idempotency.RequestFingerprint
import com.kiva.infrastructure.httpserver.idempotency.idempotencyKeyHeader
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
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
    val startPaymentAttempt: StartPaymentAttemptUc,
    val startPayment: StartPaymentUc,
    val getPaymentInit: GetPaymentInitUc,
)

/** The path parameters. */
private const val GATEWAY = "gateway"
private const val PAYMENT_ID = "paymentId"
private const val ORDER_CODE = "orderCode"

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
 * - `POST /payments/{paymentId}/retry` and `POST /me/orders/{orderCode}/payments`: another attempt to pay an unpaid
 *   order, with an `Idempotency-Key`. Like placing an order, in steps, so the gateway is never called inside a
 *   transaction: StartPaymentAttemptUc makes the attempt, StartPaymentUc opens its session, a query builds the 201
 *   PaymentInit; or 422 PAYMENT_GATEWAY_UNAVAILABLE with the order and the attempt when the gateway didn't answer.
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

        post("/payments/{$PAYMENT_ID}/retry") {
            val paymentId = call.parameters[PAYMENT_ID].orEmpty()
            call.payAgain(useCases, StartPaymentAttemptUc.Target.Attempt(paymentId), PAYMENT_ID to paymentId)
        }

        post("/me/orders/{$ORDER_CODE}/payments") {
            val orderCode = call.parameters[ORDER_CODE].orEmpty()
            call.payAgain(useCases, StartPaymentAttemptUc.Target.Order(orderCode), ORDER_CODE to orderCode)
        }
    }
}

/** Another attempt, its session opened, and its PaymentInit (201); a repeat with the same key answers the same attempt. */
private suspend fun ApplicationCall.payAgain(useCases: PaymentUseCases, target: StartPaymentAttemptUc.Target, path: Pair<String, String>) {
    val userId = accessPrincipal().userId
    // The body is optional (contract): none at all keeps the gateway of before, exactly like `{}`.
    val hasBody = (request.contentLength() ?: 0L) > 0L || request.headers.contains(HttpHeaders.TransferEncoding)
    val body = if (hasBody) receive<PayAgainHttpRequest>() else PayAgainHttpRequest()
    val fingerprint = RequestFingerprint.of(PayAgainHttpRequest.serializer(), body, mapOf(path))
    val command = StartPaymentAttemptUc.Cmd(userId, target, idempotencyKeyHeader(), fingerprint, body.gateway)
    val paymentId = useCases.startPaymentAttempt.execute(command).unwrapOr { return respondFailure(it) }
    // The gateway refused or didn't answer: 422 with the order and the attempt, as after placing an order.
    useCases.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)).unwrapOr { return respondFailure(it) }
    val init = useCases.getPaymentInit.execute(GetPaymentInitUc.Qry(userId, paymentId)).unwrapOr { return respondFailure(it) }
    respond(HttpStatusCode.Created, PaymentInitHttpResponse.from(init))
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
