package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.review.usecase.command.DeleteMyReviewUc
import com.kiva.application.usecase.review.usecase.command.ModerateReviewUc
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductRatingTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The panel's reviews over kiva_test (roadmap step 8.5): the queue with its filters and `pendingCount`, approving and
 * turning down (the product's rating follows), Kiva's reply, the change log, who may; and approval racing the
 * author's own deletion, the rating always the reviews' own numbers.
 */
class WebV1AdminReviewsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private fun productOf(slug: String): Long {
        val variant = panel.purchase.variantOf(slug, "black")
        return transaction(db) { ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variant }.single()[ProductVariantQmTbl.productId] }
    }

    private val nilaId by lazy { productOf("nila-crossbody-bag") }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private val support by lazy { panel.callerWith(Role.SUPPORT) }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.problem(): Pair<String?, List<Pair<String?, String?>>> = json().let { p ->
        p.text("code") to p["errors"]?.takeUnless { it is JsonNull }?.jsonArray.orEmpty().map { it.jsonObject.text("field") to it.jsonObject.text("code") }
    }

    /** A review of [productId] by customer [n] with [stars] (and [contact], when given), written now; its id. */
    private suspend fun ApplicationTestBuilder.written(n: Int, stars: Int, productId: Long = nilaId, contact: String? = null): String {
        val answer = client.post("$API_V1/products/$productId/reviews") {
            header(HttpHeaders.Authorization, panel.caller(PanelTestStack.phone(900 + n)).bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"rating":$stars,"text":"نظر شماره‌ی $n درباره‌ی این کیف"${contact?.let { ""","contact":"$it"""" } ?: ""}}""")
        }
        assertEquals(HttpStatusCode.Created, answer.status, answer.bodyAsText())
        clock.advance(Duration.ofMinutes(1))
        return answer.json().text("id")!!
    }

    private suspend fun ApplicationTestBuilder.moderation(id: String, body: String, who: PanelTestStack.Caller = support): HttpResponse =
        client.post("$API_V1/admin/reviews/$id/moderation") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.reply(id: String, text: String, who: PanelTestStack.Caller = support): HttpResponse =
        client.put("$API_V1/admin/reviews/$id/reply") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"text":"$text"}""")
        }

    private suspend fun ApplicationTestBuilder.queue(query: String = "", who: PanelTestStack.Caller = support): HttpResponse =
        client.get("$API_V1/admin/reviews$query") { header(HttpHeaders.Authorization, who.bearer) }

    private suspend fun ApplicationTestBuilder.publicIds(): List<String> =
        client.get("$API_V1/products/$nilaId/reviews").json().getValue("items").jsonArray.map { it.jsonObject.text("id")!! }

    private suspend fun ApplicationTestBuilder.rating(): Pair<String?, String?> =
        client.get("$API_V1/products/$nilaId/reviews/summary").json().let { it.text("average") to it.text("count") }

    private fun auditOf(reviewId: String): List<Pair<String, List<String>>> = transaction(db) {
        AuditLogTbl.selectAll().where { (AuditLogTbl.entityType eq "REVIEW") and (AuditLogTbl.entityId eq reviewId) }.orderBy(AuditLogTbl.id).map { row ->
            val changes = row[AuditLogTbl.changes]?.let { Json.parseToJsonElement(it.toString()).jsonArray }.orEmpty()
            row[AuditLogTbl.action] to changes.map { it.jsonObject.let { c -> "${c.text("field")}: ${c.text("from")} -> ${c.text("to")}" } }
        }
    }

    /** Each product's rating row next to its approved reviews' own count and sum (and verified count). */
    private fun ratingsAgainstReviews(): Map<Long, Pair<List<Int>, List<Int>>> = transaction(db) {
        val stored = ProductRatingTbl.selectAll().associate { it[ProductRatingTbl.productId] to listOf(it[ProductRatingTbl.count], it[ProductRatingTbl.sum], it[ProductRatingTbl.verifiedCount]) }
        val approved = ReviewTbl.selectAll().where { ReviewTbl.status eq ReviewStatus.APPROVED }.groupBy { it[ReviewTbl.productId] }
            .mapValues { (_, rows) -> listOf(rows.size, rows.sumOf { it[ReviewTbl.rating].toInt() }, rows.count { it[ReviewTbl.orderId] != null }) }
        (stored.keys + approved.keys).associateWith { (stored[it] ?: listOf(0, 0, 0)) to (approved[it] ?: listOf(0, 0, 0)) }
    }

    @Test
    fun `approving raises the rating and is logged - again 422 - turning an approved one down lowers it and takes it off the shop`() = panel.app {
        val id = written(1, 4)

        val approved = moderation(id, """{"action":"APPROVE"}""")
        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val body = approved.json()
        assertEquals(ContractSchemas.propertiesOf("Review"), body.keys)
        assertEquals("APPROVED", body.text("status"))
        assertEquals(nilaId.toString(), body.getValue("product").jsonObject.text("id"), "the panel's answer names the product")
        assertEquals("4.0" to "1", rating())
        assertEquals(listOf(id), publicIds())
        assertEquals(listOf("REVIEW_MODERATED" to listOf("status: PENDING -> APPROVED")), auditOf(id))

        val again = moderation(id, """{"action":"APPROVE"}""")
        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
        assertEquals("INVALID_STATUS_TRANSITION", again.problem().first)

        val rejected = moderation(id, """{"action":"REJECT","rejectionReason":"شامل تبلیغ"}""")
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        assertEquals(listOf("REJECTED", "شامل تبلیغ"), rejected.json().let { listOf(it.text("status"), it.text("rejectionReason")) })
        assertEquals("0.0" to "0", rating())
        assertEquals(emptyList(), publicIds())
        assertEquals(listOf("status: APPROVED -> REJECTED"), auditOf(id).last().second)
        assertEquals(2, auditOf(id).size)
    }

    @Test
    fun `the body - no reason 400, a reply with a rejection 400, no or an unknown action 400 - an approval with a reply keeps it`() = panel.app {
        val id = written(1, 5)

        assertEquals("VALIDATION_ERROR" to listOf("rejectionReason" to "REJECTION_REASON_REQUIRED"), moderation(id, """{"action":"REJECT"}""").problem())
        assertEquals("VALIDATION_ERROR" to listOf("rejectionReason" to "REJECTION_REASON_REQUIRED"), moderation(id, """{"action":"REJECT","rejectionReason":"بد"}""").problem())
        assertEquals(
            "VALIDATION_ERROR" to listOf("reply" to "REPLY_REQUIRES_APPROVAL"),
            moderation(id, """{"action":"REJECT","rejectionReason":"شامل تبلیغ","reply":"ممنون"}""").problem(),
        )
        assertEquals("VALIDATION_ERROR" to listOf("action" to "FIELD_REQUIRED"), moderation(id, """{}""").problem())
        assertEquals("VALIDATION_ERROR" to listOf("action" to "MODERATION_ACTION_INVALID"), moderation(id, """{"action":"HIDE"}""").problem())
        assertEquals("REVIEW_NOT_FOUND", moderation("999999999", """{"action":"APPROVE"}""").problem().first)
        assertEquals("REVIEW_NOT_FOUND", moderation("abc", """{"action":"APPROVE"}""").problem().first)
        assertTrue(auditOf(id).isEmpty())

        val approved = moderation(id, """{"action":"APPROVE","reply":"ممنون از نظرت"}""")

        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val reply = approved.json().getValue("reply").jsonObject
        assertEquals(listOf("ممنون از نظرت", "کیوا"), listOf(reply.text("text"), reply.text("authorName")))
        // That a reply was written, never its text.
        assertEquals(listOf("status: PENDING -> APPROVED", "reply: null -> null"), auditOf(id).single().second)
    }

    @Test
    fun `the reply - on a pending one 422 - on an approved one shown in the shop as Kiva's - deleted twice, both 204, one log row`() = panel.app {
        val id = written(1, 5)

        val early = reply(id, "ممنون")
        assertEquals(HttpStatusCode.UnprocessableEntity, early.status)
        assertEquals("INVALID_STATUS_TRANSITION", early.problem().first)
        assertEquals("VALIDATION_ERROR" to listOf("text" to "TEXT_TOO_SHORT"), reply(id, "م").problem())

        moderation(id, """{"action":"APPROVE"}""")
        val replied = reply(id, "سمیرا جان ممنون از انرژی خوبت")
        assertEquals(HttpStatusCode.OK, replied.status, replied.bodyAsText())
        val shown = client.get("$API_V1/products/$nilaId/reviews").json().getValue("items").jsonArray.single().jsonObject.getValue("reply").jsonObject
        assertEquals(listOf("سمیرا جان ممنون از انرژی خوبت", "کیوا"), listOf(shown.text("text"), shown.text("authorName")))

        val first = client.delete("$API_V1/admin/reviews/$id/reply") { header(HttpHeaders.Authorization, support.bearer) }
        val second = client.delete("$API_V1/admin/reviews/$id/reply") { header(HttpHeaders.Authorization, support.bearer) }
        val missing = client.delete("$API_V1/admin/reviews/999999999/reply") { header(HttpHeaders.Authorization, support.bearer) }

        assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.NoContent, HttpStatusCode.NotFound), listOf(first.status, second.status, missing.status))
        assertEquals(JsonNull, client.get("$API_V1/products/$nilaId/reviews").json().getValue("items").jsonArray.single().jsonObject["reply"])
        assertEquals(listOf("REVIEW_MODERATED", "REVIEW_REPLIED", "REVIEW_REPLY_DELETED"), auditOf(id).map { it.first })
    }

    @Test
    fun `the queue - newest first, each filter, pendingCount whatever the filters, the contact given or the account's phone`() = panel.app {
        val mahakId = productOf("mahak-shoulder-bag")
        val withContact = written(1, 5, contact = "samira@example.com")
        val other = written(2, 3, productId = mahakId)
        val replied = written(3, 4)
        moderation(replied, """{"action":"APPROVE"}""")
        reply(replied, "ممنون")

        val all = queue().json()
        assertEquals(setOf("items", "meta", "pendingCount"), all.keys)
        val items = all.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(replied, other, withContact), items.map { it.text("id") })
        assertEquals(ContractSchemas.propertiesOf("Review") + setOf("customerId", "contact"), items.first().keys)
        assertEquals("2", all.text("pendingCount"))
        assertEquals("samira@example.com", items.last().text("contact"))
        assertEquals(customer(2).user.activePhone.value, items[1].text("contact"))
        assertEquals(customer(2).user.id.value.toString(), items[1].text("customerId"))
        // The panel names each review's product (contract 1.22.0), whatever its status in the shop.
        val products = items.map { it.getValue("product").jsonObject.let { p -> p.text("id") to p.text("slug") } }
        assertEquals(listOf(nilaId.toString() to "nila-crossbody-bag", mahakId.toString() to "mahak-shoulder-bag", nilaId.toString() to "nila-crossbody-bag"), products)
        assertEquals(ContractSchemas.propertiesOf("ProductRef"), items.first().getValue("product").jsonObject.keys)

        fun JsonObject.ids() = getValue("items").jsonArray.map { it.jsonObject.text("id") }
        assertEquals(listOf(other, withContact), queue("?status=PENDING").json().ids())
        assertEquals(listOf(other), queue("?productId=$mahakId").json().ids())
        assertEquals(listOf(replied), queue("?hasReply=true").json().ids())
        assertEquals(listOf(other, withContact), queue("?hasReply=false").json().ids())
        assertEquals("2", queue("?status=APPROVED").json().text("pendingCount"))
        assertEquals(listOf(withContact), queue("?size=1&page=3").json().ids())

        assertEquals("VALIDATION_ERROR" to listOf("status" to "REVIEW_STATUS_INVALID"), queue("?status=HIDDEN").problem())
        assertEquals("VALIDATION_ERROR" to listOf("hasReply" to "BOOLEAN_INVALID"), queue("?hasReply=yes").problem())
        assertEquals(HttpStatusCode.BadRequest, queue("?sort=rating").status)
    }

    @Test
    fun `OPERATOR and CATALOG_MANAGER 403 - ADMIN and SUPPORT in`() = panel.app {
        val id = written(1, 5)
        val operator = panel.callerWith(Role.OPERATOR)
        val catalog = panel.callerWith(Role.CATALOG_MANAGER)
        val admin = panel.callerWith(Role.ADMIN)

        for (who in listOf(operator, catalog)) {
            assertEquals(HttpStatusCode.Forbidden, queue(who = who).status)
            assertEquals(HttpStatusCode.Forbidden, moderation(id, """{"action":"APPROVE"}""", who).status)
            assertEquals(HttpStatusCode.Forbidden, reply(id, "ممنون", who).status)
            assertEquals(HttpStatusCode.Forbidden, client.delete("$API_V1/admin/reviews/$id/reply") { header(HttpHeaders.Authorization, who.bearer) }.status)
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get("$API_V1/admin/reviews").status)
        assertEquals(HttpStatusCode.OK, queue(who = admin).status)
        assertEquals(HttpStatusCode.OK, moderation(id, """{"action":"APPROVE"}""", admin).status)
    }

    @Test
    fun `a turned-down review approved while its author has another live one - 409, nothing changed`() = panel.app {
        val first = written(1, 2)
        moderation(first, """{"action":"REJECT","rejectionReason":"شامل تبلیغ"}""")
        val second = written(1, 5)
        moderation(second, """{"action":"APPROVE"}""")

        val back = moderation(first, """{"action":"APPROVE"}""")

        assertEquals(HttpStatusCode.Conflict, back.status, back.bodyAsText())
        assertEquals("REVIEW_ALREADY_EXISTS", back.problem().first)
        assertEquals("5.0" to "1", rating())
        assertEquals(1, auditOf(first).size)
        ratingsAgainstReviews().forEach { (product, pair) -> assertEquals(pair.second, pair.first, "product $product") }
    }

    @Test
    fun `approval and the author's deletion at the same moment, 20 times - the rating is always the reviews' own numbers`() = panel.app {
        val keep = written(99, 3)
        moderation(keep, """{"action":"APPROVE"}""")
        val actor = PanelActor(support.user.id, null)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { round ->
                val author = customer(10 + round)
                val id = written(10 + round, 1 + round % 5)
                val start = CountDownLatch(1)
                val approve = pool.submit<Result<*, *>> {
                    start.await()
                    runBlocking { panel.adminReviews.moderate.execute(ModerateReviewUc.Cmd(id, "APPROVE", null, null, actor)) }
                }
                val delete = pool.submit<Result<*, *>> {
                    start.await()
                    runBlocking { panel.myReviews.delete.execute(DeleteMyReviewUc.Cmd(author.user.id, id)) }
                }
                start.countDown()
                val approved = approve.get(30, TimeUnit.SECONDS)
                assertTrue(delete.get(30, TimeUnit.SECONDS).getOrNull() != null, "round $round: the author's deletion")
                assertTrue(approved.getOrNull() != null || approved.failureOrNull()?.code == "REVIEW_NOT_FOUND", "round $round: $approved")
                ratingsAgainstReviews().forEach { (product, pair) -> assertEquals(pair.second, pair.first, "round $round, product $product") }
            }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("3.0" to "1", rating())
        assertNull(transaction(db) { ReviewTbl.selectAll().where { ReviewTbl.userId eq customer(10).user.id.value }.singleOrNull() })
    }
}
