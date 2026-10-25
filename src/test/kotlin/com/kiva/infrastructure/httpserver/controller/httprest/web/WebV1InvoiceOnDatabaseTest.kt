package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.InvoiceDto
import com.kiva.application.port.InvoiceRenderer
import com.kiva.application.usecase.order.usecase.query.GetMyInvoiceUc
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.InvoiceTexts
import com.kiva.infrastructure.persistence.exposed.SiteContentRepositoryImpl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The purchase receipt (`GET /me/orders/{orderCode}/invoice`, roadmap phase 14, step 14.3) on kiva_test. */
class WebV1InvoiceOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database
    private val purchase get() = panel.purchase

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive").map { purchase.variantOf("nila-crossbody-bag", it) } }

    private suspend fun ApplicationTestBuilder.invoice(code: String, who: PanelTestStack.Caller): HttpResponse =
        client.get("$API_V1/me/orders/$code/invoice") { header(HttpHeaders.Authorization, who.bearer) }

    private suspend fun ApplicationTestBuilder.page(code: String, who: PanelTestStack.Caller): JsonObject =
        Json.parseToJsonElement(client.get("$API_V1/me/orders/$code") { header(HttpHeaders.Authorization, who.bearer) }.bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String? = Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    @Test
    fun `a paid order's receipt - a PDF of one page, named after the order, never kept on the way - and the page links it`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(801))
        val code = panel.paidOrders.paid(sara.user.id, nila[0], nila[1]).value

        val answer = invoice(code, sara)

        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertEquals(ContentType.Application.Pdf, answer.contentType()?.withoutParameters())
        assertEquals("attachment; filename=\"kiva-$code.pdf\"", answer.headers[HttpHeaders.ContentDisposition])
        assertEquals("private, no-store", answer.headers[HttpHeaders.CacheControl])
        Loader.loadPDF(answer.bodyAsBytes()).use { assertEquals(1, it.numberOfPages) }
        assertEquals("/api/v1/me/orders/$code/invoice", page(code, sara)["invoiceUrl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the receipt has the order's lines and totals as its page shows them, and who sells`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(802))
        val code = panel.paidOrders.paid(sara.user.id, nila[0], nila[1]).value
        val shown = page(code, sara)

        invoice(code, sara)

        val given = purchase.invoiceRenderer.rendered.last()
        assertEquals(shown.getValue("items").jsonArray.map { it.jsonObject["name"]?.jsonPrimitive?.content }, given.order.items.map { it.name })
        assertEquals(shown.getValue("totals").jsonObject["payable"]?.jsonPrimitive?.content?.toLong(), given.order.totals.payable.toman)
        assertEquals("https://kiva.ir", given.seller.website)
        val document = InvoiceTexts.documentOf(given)
        assertEquals(2, document.rows.size)
        assertNull(document.banner)
        assertTrue(document.facts.any { it.second == code })
    }

    @Test
    fun `a cancelled order still has its receipt, with «لغو شده» across it`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(803))
        val code = panel.paidOrders.paid(sara.user.id, nila[0]).value
        val cancelled = client.post("$API_V1/me/orders/$code/cancel") {
            header(HttpHeaders.Authorization, sara.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"reason":"CHANGED_MIND"}""")
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())

        assertEquals(HttpStatusCode.OK, invoice(code, sara).status)

        val given = purchase.invoiceRenderer.rendered.last()
        assertTrue(given.cancelled)
        assertEquals("لغو شده", InvoiceTexts.documentOf(given).banner)
    }

    @Test
    fun `an order not paid is 422 INVOICE_NOT_AVAILABLE, and its page has no invoiceUrl`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(804))
        val (code, _) = panel.paidOrders.place(sara.user.id, nila[0])

        val answer = invoice(code.value, sara)

        assertEquals(HttpStatusCode.UnprocessableEntity, answer.status)
        assertEquals("INVOICE_NOT_AVAILABLE", answer.code())
        assertEquals(JsonNull, page(code.value, sara)["invoiceUrl"])
    }

    @Test
    fun `someone else's order, one that isn't there and a code that can't be one are all 404 ORDER_NOT_FOUND - no PDF is made`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(805))
        val reza = panel.caller(PanelTestStack.phone(806))
        val code = panel.paidOrders.paid(sara.user.id, nila[0]).value
        val made = purchase.invoiceRenderer.rendered.size

        listOf(invoice(code, reza), invoice("KV-999999", sara), invoice("not-a-code", sara)).forEach {
            assertEquals(HttpStatusCode.NotFound, it.status)
            assertEquals("ORDER_NOT_FOUND", it.code())
        }
        assertEquals(made, purchase.invoiceRenderer.rendered.size)
    }

    /** The PDF is made after the transaction that read the order ended (decision 15): a renderer called inside one fails. */
    @Test
    fun `the PDF is drawn outside the transaction`() {
        val sara = panel.caller(PanelTestStack.phone(807))
        val code = panel.paidOrders.paid(sara.user.id, nila[0]).value
        val watched = WatchedTransactions(purchase.transactions)
        val renderer = object : InvoiceRenderer {
            override fun render(invoice: InvoiceDto): ByteArray {
                check(!watched.inside) { "drawn inside the transaction" }
                return byteArrayOf(1)
            }
        }
        val useCase = GetMyInvoiceUc(watched, purchase.orderByCode, purchase.orderDetails, purchase.settings, SiteContentRepositoryImpl(), renderer, "https://kiva.ir", clock)

        val result = runBlocking { useCase.execute(GetMyInvoiceUc.Qry(sara.user.id, code)) }

        assertIs<Result.Success<GetMyInvoiceUc.Invoice>>(result)
        assertEquals(1, watched.opened)
    }

    /** A transaction manager that says whether a transaction is open right now. */
    private class WatchedTransactions(private val inner: TransactionManager) : TransactionManager {
        @Volatile
        var inside = false
            private set
        var opened = 0
            private set

        override suspend fun <T, F : ResultFailure> inTransaction(block: suspend () -> Result<T, F>): Result<T, F> {
            opened++
            return inner.inTransaction {
                inside = true
                try {
                    block()
                } finally {
                    inside = false
                }
            }
        }
    }
}
