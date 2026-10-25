package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.review.RatingBook
import com.kiva.application.usecase.review.Reviews
import com.kiva.application.usecase.review.usecase.command.VoteReviewHelpfulUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
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
import kotlin.test.assertTrue

/**
 * A product's public reviews, its rating's summary and the helpful vote over kiva_test (roadmap step 8.3): reviews
 * written through the shop's own route, approved or turned down straight on the rows the way support's use case will
 * (step 8.5), every answer held against the contract.
 */
class WebV1ProductReviewsOnDatabaseTest {

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

    private val nilaId by lazy {
        val variant = panel.purchase.variantOf("nila-crossbody-bag", "black")
        transaction(db) { ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variant }.single()[ProductVariantQmTbl.productId] }
    }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** A review of Nila by customer [n] with [stars], written now; its id. */
    private suspend fun ApplicationTestBuilder.written(n: Int, stars: Int): String {
        val answer = client.post("$API_V1/products/$nilaId/reviews") {
            header(HttpHeaders.Authorization, customer(n).bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"rating":$stars,"text":"نظر شماره‌ی $n درباره‌ی این کیف"}""")
        }
        assertEquals(HttpStatusCode.Created, answer.status, answer.bodyAsText())
        clock.advance(Duration.ofMinutes(1))
        return answer.json().text("id")!!
    }

    private fun moderate(id: String, approve: Boolean, reason: String = "شامل تبلیغ") = transaction(db) {
        val review = panel.reviewRepository.lock(ReviewId.constructFromPersisted(id.toLong()))!!
        val before = RatingBook.Counted.of(review)
        if (approve) review.approve(null, UserId.SYSTEM, clock.now).getOrNull()!! else review.reject(ReviewText.constructFromPersisted(reason), UserId.SYSTEM, clock.now).getOrNull()!!
        panel.ratingBook.changed(before, review, clock.now)
        panel.reviewRepository.save(review)
    }

    private suspend fun ApplicationTestBuilder.list(query: String = "", who: PanelTestStack.Caller? = null, token: String? = null): HttpResponse =
        client.get("$API_V1/products/$nilaId/reviews$query") {
            (token ?: who?.bearer)?.let { header(HttpHeaders.Authorization, if (token != null) "Bearer $it" else it) }
        }

    private suspend fun ApplicationTestBuilder.vote(id: String, who: PanelTestStack.Caller, helpful: Boolean): HttpResponse =
        client.put("$API_V1/reviews/$id/helpful") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"helpful":$helpful}""")
        }

    private fun JsonObject.ids(): List<String> = getValue("items").jsonArray.map { it.jsonObject.text("id")!! }

    @Test
    fun `a guest sees only approved ones and the summary - its author sees their pending one first, on page 1 only, outside the count`() = panel.app {
        val approved = (1..3).map { written(it, 5 - it % 2) }
        approved.forEach { moderate(it, approve = true) }
        val pending = written(9, 2)

        val guest = list("?size=2").json()
        val authorPage1 = list("?size=2", customer(9)).json()
        val authorPage2 = list("?size=2&page=2", customer(9)).json()

        assertEquals(setOf("summary", "items", "meta"), guest.keys)
        assertEquals(approved.reversed().take(2), guest.ids())
        assertEquals(listOf(pending) + approved.reversed().take(2), authorPage1.ids())
        assertEquals("true", authorPage1.getValue("items").jsonArray.first().jsonObject.text("isMine"))
        assertTrue(pending !in authorPage2.ids())
        assertEquals("3", authorPage1.getValue("meta").jsonObject.text("totalItems"), "the pending one isn't counted")
        guest.getValue("items").jsonArray.forEach { assertEquals(ContractSchemas.propertiesOf("Review"), it.jsonObject.keys) }
        val summary = guest.getValue("summary").jsonObject
        assertEquals(listOf("3", "4.3", "0"), listOf(summary.text("count"), summary.text("average"), summary.text("verifiedBuyerPercent")))
        assertEquals(listOf("5", "4", "3", "2", "1"), summary.getValue("distribution").jsonArray.map { it.jsonObject.text("stars") })
    }

    @Test
    fun `someone else's reason to turn a review down is never in the public list - nor is a turned-down one at all`() = panel.app {
        val kept = written(1, 5)
        val turnedDown = written(2, 1)
        moderate(kept, approve = true)
        moderate(turnedDown, approve = false, reason = "دلیلی که فقط نویسنده می‌بینه")

        val asOther = list(who = customer(3)).bodyAsText()
        val asAuthor = list(who = customer(2)).json()

        assertTrue("دلیلی که فقط نویسنده" !in asOther)
        assertEquals(listOf(kept), asAuthor.ids(), "a REJECTED one only in «نظرات من»")
    }

    @Test
    fun `a turned-down review built for anyone but its author and the panel carries no reason - the wall behind the public list`() = panel.app {
        val turnedDown = written(2, 1)
        moderate(turnedDown, approve = false, reason = "دلیلی که فقط نویسنده می‌بینه")
        val review = transaction(db) { panel.reviewRepository.find(ReviewId.constructFromPersisted(turnedDown.toLong()))!! }

        val reasons = transaction(db) {
            listOf(
                Reviews.Viewer(viewer = customer(3).user.id),
                Reviews.Viewer(viewer = null),
                Reviews.Viewer(viewer = customer(2).user.id),
                Reviews.Viewer(viewer = null, panel = true),
            ).map { panel.reviews.of(review, it).rejectionReason }
        }

        assertEquals(listOf(null, null, "دلیلی که فقط نویسنده می‌بینه", "دلیلی که فقط نویسنده می‌بینه"), reasons)
    }

    @Test
    fun `every sort and filter - ties broken by the newer review, the same order page after page`() = panel.app {
        val ids = listOf(5, 3, 5, 1, 4).mapIndexed { i, stars -> written(i + 1, stars) }
        ids.forEach { moderate(it, approve = true) }
        listOf(ids[0], ids[0], ids[1]).forEachIndexed { i, id -> vote(id, customer(20 + i), true) }
        vote(ids[1], customer(30), true)

        assertEquals(ids.reversed(), list("?sort=newest").json().ids())
        assertEquals(listOf(ids[2], ids[0], ids[4], ids[1], ids[3]), list("?sort=highest").json().ids())
        assertEquals(listOf(ids[3], ids[1], ids[4], ids[2], ids[0]), list("?sort=lowest").json().ids())
        assertEquals(listOf(ids[1], ids[0], ids[4], ids[3], ids[2]), list("?sort=helpful").json().ids())
        assertEquals(listOf(ids[2], ids[0]), list("?rating=5").json().ids())
        assertEquals(listOf(ids[2], ids[0]), list("?sort=highest&size=2").json().ids())
        assertEquals(listOf(ids[4], ids[1]), list("?sort=highest&size=2&page=2").json().ids())
        assertEquals(emptyList(), list("?withMedia=true").json().ids())
        assertEquals(emptyList(), list("?verifiedOnly=true").json().ids())
        assertEquals(HttpStatusCode.BadRequest, list("?rating=6").status)
        assertEquals(HttpStatusCode.BadRequest, list("?withMedia=maybe").status)
        assertEquals(HttpStatusCode.BadRequest, list("?sort=oldest").status)
    }

    @Test
    fun `a vote, the same vote again, taken back - one's own 422, a pending one 404, no login 401`() = panel.app {
        val approved = written(1, 5)
        val pending = written(2, 4)
        moderate(approved, approve = true)

        val first = vote(approved, customer(3), true)
        val again = vote(approved, customer(3), true)
        val back = vote(approved, customer(3), false)
        val own = vote(approved, customer(1), true)
        val notApproved = vote(pending, customer(3), true)
        val anonymous = client.put("$API_V1/reviews/$approved/helpful") { contentType(ContentType.Application.Json); setBody("""{"helpful":true}""") }

        assertEquals(listOf("1", "true"), first.json().let { listOf(it.text("helpfulCount"), it.text("myVote")) })
        assertEquals(listOf("1", "true"), again.json().let { listOf(it.text("helpfulCount"), it.text("myVote")) })
        assertEquals(listOf("0", null), back.json().let { listOf(it.text("helpfulCount"), it.text("myVote")) })
        assertEquals(HttpStatusCode.UnprocessableEntity, own.status)
        assertEquals("OWN_REVIEW_VOTE", own.json().text("code"))
        assertEquals(HttpStatusCode.NotFound, notApproved.status)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        vote(approved, customer(3), true)
        assertEquals("true", list(who = customer(3)).json().getValue("items").jsonArray.single().jsonObject.text("myHelpfulVote"))
        assertEquals(JsonNull, list().json().getValue("items").jsonArray.single().jsonObject["myHelpfulVote"], "a guest has no vote")
    }

    @Test
    fun `20 votes from 20 customers at the same moment - helpfulCount 20`() = panel.app {
        val approved = written(1, 5)
        moderate(approved, approve = true)
        val voters = (1..20).map { customer(100 + it) }
        val pool = Executors.newFixedThreadPool(20)
        try {
            val start = CountDownLatch(1)
            val results = voters.map { who ->
                pool.submit<Any> {
                    start.await()
                    runBlocking { panel.reviewUseCases.vote.execute(VoteReviewHelpfulUc.Cmd(who.user.id, approved, true)) }
                }
            }
            start.countDown()
            results.forEach { assertTrue((it.get(30, TimeUnit.SECONDS) as Result<*, *>).getOrNull() != null) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals("20", list().json().getValue("items").jsonArray.single().jsonObject.text("helpfulCount"))
    }

    @Test
    fun `the summary alone - the same numbers - a product not in the shop 404 - an expired token on the list 401, not a guest`() = panel.app {
        moderate(written(1, 4), approve = true)

        val summary = client.get("$API_V1/products/$nilaId/reviews/summary")
        val missing = client.get("$API_V1/products/999999999/reviews/summary")
        val expired = list(token = "not.a.token")

        assertEquals(HttpStatusCode.OK, summary.status)
        assertEquals(ContractSchemas.propertiesOf("RatingSummary"), summary.json().keys)
        assertEquals(list().json().getValue("summary").jsonObject, summary.json())
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
    }
}
