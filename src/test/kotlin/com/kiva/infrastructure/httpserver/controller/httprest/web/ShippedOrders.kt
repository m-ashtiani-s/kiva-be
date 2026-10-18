package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals

/**
 * Orders taken from paid to shipped the way the panel does it, over [panel]'s routes: photos drafted and sent, the
 * customer's «همونه!», and `PUT …/shipment`. For the tests after shipping (tracking, «سفارش‌های من»).
 */
class ShippedOrders(private val panel: PanelTestStack) {

    /** The operator, with a session started now (a session from before a jump of the clock is over). */
    fun operator(): PanelTestStack.Caller = panel.caller(PanelTestStack.phone(90), Role.OPERATOR)

    /** One photo per line ([lines] of them) drafted and sent by the operator. */
    suspend fun ApplicationTestBuilder.photographed(code: OrderCode, lines: Int = 1) {
        val staff = operator()
        val items = (1..lines).map { line ->
            val photo = client.post("$API_V1/admin/media") {
                header(HttpHeaders.Authorization, staff.bearer)
                setBody(
                    MultiPartFormDataContent(
                        formData {
                            append("purpose", "PRE_SHIPMENT")
                            append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                        },
                    ),
                )
            }.let { Json.parseToJsonElement(it.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content }
            if (lines == 1) """{"mediaId":"$photo"}""" else """{"mediaId":"$photo","orderItemId":$line}"""
        }
        val set = client.put("$API_V1/admin/orders/${code.value}/pre-shipment-media") {
            header(HttpHeaders.Authorization, staff.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"items":$items}""")
        }
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        val sent = client.post("$API_V1/admin/orders/${code.value}/pre-shipment-media/send") { header(HttpHeaders.Authorization, staff.bearer) }
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
    }

    /** The customer's «همونه!». */
    suspend fun ApplicationTestBuilder.approved(code: OrderCode, who: PanelTestStack.Caller) {
        val answered = client.post("$API_V1/me/orders/${code.value}/media-feedback") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"decision":"APPROVE"}""")
        }
        assertEquals(HttpStatusCode.OK, answered.status, answered.bodyAsText())
    }

    /** `PUT /admin/orders/{code}/shipment` with [body]. */
    suspend fun ApplicationTestBuilder.ship(code: OrderCode, body: String): HttpResponse =
        client.put("$API_V1/admin/orders/${code.value}/shipment") {
            header(HttpHeaders.Authorization, operator().bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /** [code] (of [who], one line per bag) photographed, approved and shipped under [trackingCode]. */
    suspend fun ApplicationTestBuilder.shipped(code: OrderCode, who: PanelTestStack.Caller, trackingCode: String, lines: Int = 1, showInDailyList: Boolean = true) {
        photographed(code, lines)
        approved(code, who)
        val shipped = ship(code, """{"carrier":"POST","trackingCode":"$trackingCode","showInDailyList":$showInDailyList}""")
        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
    }
}
