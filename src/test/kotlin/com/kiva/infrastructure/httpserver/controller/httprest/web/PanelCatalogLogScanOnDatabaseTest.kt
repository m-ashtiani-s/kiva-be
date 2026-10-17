package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.read.ListAppender
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.CategoryRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.MaterialRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.InventoryAdjustmentQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step 1.10 (roadmap section 3, row 15): the panel's catalog work with every log line caught, at the app's levels and
 * with the SQL log on: an upload, a category with its photo, a product with colors, gallery and stock, its edit with
 * If-Match, a new color, a stock change, a schedule, and reading the change log.
 *
 * No line may carry the staff member's whole mobile number, the IP the requests came from (the change log keeps it,
 * the app log never), or the name of the file the client sent. Each value is chosen so it can't appear by chance.
 */
class PanelCatalogLogScanOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-04-02T09:00:00Z"))
    private val stack = PanelTestStack(clock, FakeMediaProcessor())
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) {
            val productIds = ProductQmTbl.selectAll().where { ProductQmTbl.slug like "tstlog-%" }.map { it[ProductQmTbl.productId] }
            val variantIds = ProductVariantQmTbl.selectAll().where { ProductVariantQmTbl.productId inList productIds }.map { it[ProductVariantQmTbl.variantId] }
            EventStoreTbl.deleteWhere { (aggregateName eq "PRODUCT") and (aggregateId inList productIds.map { it.toString() }) }
            EventStoreTbl.deleteWhere { (aggregateName eq "INVENTORY") and (aggregateId inList variantIds.map { it.toString() }) }
            InventoryAdjustmentQmTbl.deleteWhere { InventoryAdjustmentQmTbl.variantId inList variantIds }
            ProductVariantQmTbl.deleteWhere { productId inList productIds }
            ProductQmTbl.deleteWhere { productId inList productIds }
            CategoryTbl.deleteWhere { CategoryTbl.slug like "tstlog%" }
        }
        stack.deleteWhatWasStored()
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    @Test
    fun `an upload, a category, a product with its colors and stock, its edits and the change log leave nothing personal in the log`() {
        val manager = stack.caller(PanelTestStack.phone(77), Role.ADMIN, Role.CATALOG_MANAGER, name = "سپیده کاتالوگی")
        val phone = manager.user.phone.value
        val categoryId = transaction(db) { CategoryRepositoryImpl().findBySlug(Slug.constructFromPersisted("shoulder"))!!.id.value }
        val materialId = transaction(db) { MaterialRepositoryImpl().findAll().first().id.value }
        var productId = ""

        val log = logOf {
            // As behind our nginx, so the caller's address is the one X-Forwarded-For names.
            stack.app(trustedProxyHops = 1) {
                val asStaff: HttpRequestBuilder.() -> Unit = {
                    header(HttpHeaders.Authorization, manager.bearer)
                    header("X-Forwarded-For", IP)
                }
                fun HttpRequestBuilder.json(body: String) {
                    asStaff()
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                suspend fun upload(purpose: String): String = client.post("$API_V1/admin/media") {
                    asStaff()
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("purpose", purpose)
                                append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"$FILE_NAME\"") })
                            },
                        ),
                    )
                }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.json().getValue("id").jsonPrimitive.content

                val categoryPhoto = upload("CATEGORY")
                val category = client.post("$API_V1/admin/categories") {
                    json("""{"name":"زنبق لاگ","slug":"tstlog-cat","iconBagType":"HOBO","iconColorKey":"lilac","imageMediaId":"$categoryPhoto"}""")
                }
                assertEquals(HttpStatusCode.Created, category.status, category.bodyAsText())

                val front = upload("PRODUCT")
                val created = client.post("$API_V1/admin/products") {
                    json(
                        """{"name":"کیف لاگ","slug":"tstlog-bag","categoryId":$categoryId,"bagType":"HOBO","materialId":$materialId,"price":1890000,""" +
                            """"description":"<p>متن</p>","variants":[{"colorKey":"lilac","stockQuantity":4,"media":[{"mediaId":"$front","view":"FRONT"}]}]}""",
                    )
                }
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val body = created.json()
                productId = body.getValue("id").jsonPrimitive.content
                val variantId = body.getValue("adminVariants").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content

                val edited = client.put("$API_V1/admin/products/$productId") {
                    header(HttpHeaders.IfMatch, body.getValue("etag").jsonPrimitive.content)
                    json(
                        """{"name":"کیف لاگ دو","slug":"tstlog-bag","categoryId":$categoryId,"bagType":"HOBO","materialId":$materialId,"price":1790000}""",
                    )
                }
                assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
                assertEquals(HttpStatusCode.Created, client.post("$API_V1/admin/products/$productId/variants") { json("""{"colorKey":"black","stockQuantity":2}""") }.status)
                assertEquals(
                    HttpStatusCode.Created,
                    client.post("$API_V1/admin/inventory/adjustments") { json("""{"variantId":$variantId,"delta":-1,"reason":"DAMAGED","note":"پاره"}""") }.status,
                )
                val scheduled = client.patch("$API_V1/admin/products/$productId/status") {
                    json("""{"status":"ACTIVE","publishAt":"${clock.instant().plusSeconds(3_600)}"}""")
                }
                assertEquals(HttpStatusCode.OK, scheduled.status, scheduled.bodyAsText())

                val audit = client.get("$API_V1/admin/audit-log?entityType=PRODUCT", asStaff)
                assertEquals(HttpStatusCode.OK, audit.status, audit.bodyAsText())
                assertTrue(audit.json().getValue("items").jsonArray.isNotEmpty())
            }
        }

        assertTrue(log.any { it.loggerName == "Exposed" }, "the SQL log was caught")
        val ips = transaction(db) { AuditLogTbl.selectAll().where { AuditLogTbl.actorId eq manager.userId }.map { it[AuditLogTbl.ip] }.toSet() }
        assertEquals(setOf<String?>(IP), ips, "the IP reached the server: the change log keeps it")
        assertTrue(log.any { "product=$productId" in it.formattedMessage || "productId=$productId" in it.formattedMessage }, "the product's own lines were caught")
        val leaks = leaksIn(log, phones = listOf(phone), texts = listOf(IP, FILE_NAME, FILE_STEM))
        assertTrue(leaks.isEmpty(), leaks.joinToString("\n") { (what, line) -> "$what in: $line" })
    }

    @Test
    fun `the scan finds a whole number, an IP and a file name, so a pass means something`() {
        val log = logOf {
            val logger = LoggerFactory.getLogger(PanelCatalogLogScanOnDatabaseTest::class.java)
            logger.info("staff 0990 000 0077 from $IP")
            logger.warn("upload $FILE_NAME")
        }

        assertEquals(
            listOf("a whole phone number", "a personal text", "a personal text", "a personal text"),
            leaksIn(log, phones = listOf(PanelTestStack.phone(77)), texts = listOf(IP, FILE_NAME, FILE_STEM)).map { it.first },
        )
    }

    /** (what leaked, the line it is on) for each one found; empty when the log is clean. */
    private fun leaksIn(log: List<ILoggingEvent>, phones: List<String>, texts: List<String>): List<Pair<String, String>> {
        val lines = log.map { it.text() }
        // A phone in any form (09…, 9…, +98 9…) has these 10 digits in a row; a masked one doesn't.
        val wholeNumbers = phones.map { it.drop(1) }
        return lines.filter { line -> wholeNumbers.any { it in line.withoutDigitGaps() } }.map { "a whole phone number" to it } +
            lines.flatMap { line -> texts.filter { it.isNotEmpty() && it in line }.map { "a personal text" to line } }
    }

    private fun String.withoutDigitGaps(): String = replace(Regex("(?<=\\d)[ \\-]+(?=\\d)"), "")

    private fun ILoggingEvent.text(): String = buildString {
        append(formattedMessage)
        throwableProxy?.let { append('\n').append(ThrowableProxyUtil.asString(it)) }
    }

    /** Every line [block] logs, at the levels the app is set to, and with the SQL log on whatever logback.xml says. */
    private fun logOf(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val sql = LoggerFactory.getLogger("Exposed") as Logger
        val sqlLevel = sql.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        sql.level = Level.DEBUG
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            sql.level = sqlLevel
        }
        return appender.list.toList()
    }

    private companion object {
        const val IP = "203.0.113.77"
        const val FILE_STEM = "ketab-khane-razi-9f3k"
        const val FILE_NAME = "$FILE_STEM.jpg"
    }
}
