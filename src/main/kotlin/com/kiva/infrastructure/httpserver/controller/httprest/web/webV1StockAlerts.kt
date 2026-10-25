package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.stockalert.usecase.command.CancelMyStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.command.CreateStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.query.ListMyStockAlertsUc
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CreateStockAlertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.StockAlertHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The use cases of «موجود شد خبرم کن», as the routes need them. */
class StockAlertUseCases(
    val create: CreateStockAlertUc,
    val listMine: ListMyStockAlertsUc,
    val cancel: CancelMyStockAlertUc,
)

private const val PRODUCT_ID = "productId"
private const val ALERT_ID = "alertId"

/**
 * «موجود شد خبرم کن» (contract tag `Product`): subscribing, «اشتراک‌های من» and calling one off. Mounted under
 * `/api/v1`; a login is needed everywhere (decision 1): the SMS goes to the account's own phone.
 */
fun Route.webV1StockAlerts(useCases: StockAlertUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
        // The body is optional: none, `{}` or `{"variantId": 501}`.
        post("/products/{$PRODUCT_ID}/stock-alerts") {
            val request = call.optionalBody(CreateStockAlertHttpRequest.serializer()) ?: CreateStockAlertHttpRequest()
            val command = CreateStockAlertUc.Cmd(call.accessPrincipal().userId, call.parameters[PRODUCT_ID].orEmpty(), request.variantId, request.channel)
            call.respondResult(useCases.create.execute(command), HttpStatusCode.Created) { StockAlertHttpResponse.from(it, mediaUrls) }
        }

        get("/me/stock-alerts") {
            call.respondResult(useCases.listMine.execute(ListMyStockAlertsUc.Qry(call.accessPrincipal().userId))) { alerts ->
                alerts.map { StockAlertHttpResponse.from(it, mediaUrls) }
            }
        }

        delete("/me/stock-alerts/{$ALERT_ID}") {
            call.respondResult(useCases.cancel.execute(CancelMyStockAlertUc.Cmd(call.accessPrincipal().userId, call.parameters[ALERT_ID].orEmpty())))
        }
    }
}
