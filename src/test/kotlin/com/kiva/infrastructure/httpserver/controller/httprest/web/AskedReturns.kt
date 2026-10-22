package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import java.time.Duration
import kotlin.test.assertEquals

/**
 * Orders taken to DELIVERED and returns asked for them, the way the shop and the panel do it, over [panel]'s routes: for
 * the tests of the panel's returns (phase 7).
 */
class AskedReturns(private val panel: PanelTestStack, private val clock: MutableClock) {

    val shippedOrders = ShippedOrders(panel)

    private var parcels = 0

    /** The operator, with a session started now. */
    fun operator(): PanelTestStack.Caller = shippedOrders.operator()

    /** [who] with a session started now: one from before a jump of the clock is over. */
    fun fresh(who: PanelTestStack.Caller): PanelTestStack.Caller = panel.caller(who.user.phone.value)

    /** An order of [who] of [variants] (one each), taken to DELIVERED by the panel: returnable for 7 days from now. */
    suspend fun ApplicationTestBuilder.delivered(who: PanelTestStack.Caller, vararg variants: Long): OrderCode {
        val code = panel.paidOrders.paid(who.user.id, *variants)
        with(shippedOrders) { shipped(code, fresh(who), "PT-${7_000_000 + ++parcels}", lines = variants.size) }
        clock.advance(Duration.ofDays(2))
        val delivered = client.post("$API_V1/admin/orders/${code.value}/status") {
            header(HttpHeaders.Authorization, operator().bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"status":"DELIVERED"}""")
        }
        assertEquals(HttpStatusCode.OK, delivered.status, delivered.bodyAsText())
        return code
    }

    /** A photo of the problem, uploaded by [who] for a return; its id. */
    suspend fun ApplicationTestBuilder.photo(who: PanelTestStack.Caller): String {
        val uploaded = client.post("$API_V1/me/uploads") {
            header(HttpHeaders.Authorization, fresh(who).bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "RETURN_EVIDENCE")
                        append("file", FakeMediaProcessor.photo(1200, 900), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        return Json.parseToJsonElement(uploaded.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content
    }

    /** `POST /me/orders/{code}/returns` by [who] with [body]. */
    suspend fun ApplicationTestBuilder.ask(code: OrderCode, who: PanelTestStack.Caller, body: String): HttpResponse =
        client.post("$API_V1/me/orders/${code.value}/returns") {
            header(HttpHeaders.Authorization, fresh(who).bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    /**
     * A return of [who]'s delivered [code] asked for: [items] (`orderItemId` to quantity) back for [reason] (CHANGED_MIND
     * needs no photo; any other reason gets one of [who]'s). Its id.
     */
    suspend fun ApplicationTestBuilder.asked(
        code: OrderCode,
        who: PanelTestStack.Caller,
        reason: String = "CHANGED_MIND",
        items: List<Pair<Int, Int>> = listOf(1 to 1),
        refundMethod: String? = null,
    ): Long {
        val media = if (reason == "CHANGED_MIND" || reason == "OTHER") "[]" else """["${photo(who)}"]"""
        val lines = items.joinToString(",", "[", "]") { (line, quantity) -> """{"orderItemId":$line,"quantity":$quantity}""" }
        val asked = ask(code, who, """{"items":$lines,"reason":"$reason","mediaIds":$media,"description":"توضیح","refundMethod":${refundMethod?.let { "\"$it\"" } ?: "null"}}""")
        assertEquals(HttpStatusCode.Created, asked.status, asked.bodyAsText())
        return Json.parseToJsonElement(asked.bodyAsText()).jsonObject.getValue("id").jsonPrimitive.content.toLong()
    }
}
