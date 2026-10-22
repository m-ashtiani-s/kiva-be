package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.tbl.ProductRatingTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewVoteTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The product's rating is never anything but its approved reviews' own numbers (roadmap step 8.6): 200 steps drawn at
 * random (seeded, so a red run is the same run again) through the real routes, writing, approving, turning down,
 * editing, deleting, replying and voting on three products by eight customers; after every step each product's
 * `productrating_tbl` row is held against `COUNT`, `SUM`, the stars and the buyers of its APPROVED reviews, and each
 * review's `helpfulCount` against its votes.
 */
class ReviewsConsistencyOnDatabaseTest {

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

    /** A fresh token for customer [n], the clock having moved on. */
    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private suspend fun HttpResponse.idOrNull(): String? =
        if (status == HttpStatusCode.Created) Json.parseToJsonElement(bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content else null

    /** Every review that is stored now: its id and its author's number. */
    private fun reviews(): List<Pair<String, Long>> = transaction(db) {
        ReviewTbl.selectAll().map { it[ReviewTbl.reviewId].toString() to it[ReviewTbl.userId] }
    }

    /** Each product's stored numbers next to its approved reviews' own: count, sum, stars 1 to 5, buyers. */
    private fun ratingsAgainstReviews(): Map<Long, Pair<List<Int>, List<Int>>> = transaction(db) {
        val stored = ProductRatingTbl.selectAll().associate { row ->
            row[ProductRatingTbl.productId] to listOf(row[ProductRatingTbl.count], row[ProductRatingTbl.sum]) + row.let { r -> ProductRatingTbl.starColumns.map { r[it] } } +
                row[ProductRatingTbl.verifiedCount]
        }
        val approved = ReviewTbl.selectAll().where { ReviewTbl.status eq ReviewStatus.APPROVED }.groupBy { it[ReviewTbl.productId] }.mapValues { (_, rows) ->
            listOf(rows.size, rows.sumOf { it[ReviewTbl.rating].toInt() }) + (1..5).map { s -> rows.count { it[ReviewTbl.rating].toInt() == s } } +
                rows.count { it[ReviewTbl.orderId] != null }
        }
        val none = List(8) { 0 }
        (stored.keys + approved.keys).associateWith { (stored[it] ?: none) to (approved[it] ?: none) }
    }

    /** Each review's helpfulCount next to the votes it has. */
    private fun helpfulAgainstVotes(): Map<Long, Pair<Int, Int>> = transaction(db) {
        val votes = ReviewVoteTbl.selectAll().groupingBy { it[ReviewVoteTbl.reviewId] }.eachCount()
        ReviewTbl.selectAll().associate { it[ReviewTbl.reviewId] to (it[ReviewTbl.helpfulCount] to (votes[it[ReviewTbl.reviewId]] ?: 0)) }
    }

    private suspend fun ApplicationTestBuilder.step(random: Random, products: List<Long>, support: () -> PanelTestStack.Caller): Pair<String, HttpStatusCode> {
        val stored = reviews()
        val n = random.nextInt(1, 9)
        val anyReview = stored.randomOrNull(random)?.first
        val theirs = stored.filter { it.second == customer(n).user.id.value }.randomOrNull(random)?.first
        val stars = random.nextInt(1, 6)
        return when (random.nextInt(9)) {
            0, 1 -> "write" to client.post("$API_V1/products/${products.random(random)}/reviews") {
                header(HttpHeaders.Authorization, customer(n).bearer)
                contentType(ContentType.Application.Json)
                setBody("""{"rating":$stars,"text":"نظر مشتری $n با $stars ستاره"}""")
            }.status
            2, 3 -> "approve" to moderate(anyReview ?: "1", """{"action":"APPROVE"${if (random.nextBoolean()) ""","reply":"ممنون از نظرت"""" else ""}}""", support())
            4 -> "reject" to moderate(anyReview ?: "1", """{"action":"REJECT","rejectionReason":"شامل تبلیغ"}""", support())
            5 -> "edit" to client.patch("$API_V1/me/reviews/${theirs ?: "1"}") {
                header(HttpHeaders.Authorization, customer(n).bearer)
                contentType(ContentType.Application.Json)
                setBody("""{"rating":$stars}""")
            }.status
            6 -> "delete" to client.delete("$API_V1/me/reviews/${theirs ?: "1"}") { header(HttpHeaders.Authorization, customer(n).bearer) }.status
            7 -> "vote" to client.put("$API_V1/reviews/${anyReview ?: "1"}/helpful") {
                header(HttpHeaders.Authorization, customer(n).bearer)
                contentType(ContentType.Application.Json)
                setBody("""{"helpful":${random.nextBoolean()}}""")
            }.status
            else -> "reply" to if (random.nextBoolean()) {
                client.put("$API_V1/admin/reviews/${anyReview ?: "1"}/reply") {
                    header(HttpHeaders.Authorization, support().bearer)
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"پاسخ کیوا"}""")
                }.status
            } else {
                client.delete("$API_V1/admin/reviews/${anyReview ?: "1"}/reply") { header(HttpHeaders.Authorization, support().bearer) }.status
            }
        }
    }

    private suspend fun ApplicationTestBuilder.moderate(id: String, body: String, who: PanelTestStack.Caller): HttpStatusCode =
        client.post("$API_V1/admin/reviews/$id/moderation") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status

    @Test
    fun `200 random steps through the routes - after each, every product's rating is its approved reviews' own numbers`() = panel.app {
        val products = listOf("nila-crossbody-bag", "mahak-shoulder-bag", "aida-handbag").map(::productOf)
        val supportPhone = PanelTestStack.phone(70)
        val support = { panel.caller(supportPhone, Role.SUPPORT) }
        val random = Random(20261012)
        val seen = mutableMapOf<String, MutableSet<HttpStatusCode>>()

        repeat(200) { i ->
            val (kind, status) = step(random, products, support)
            seen.getOrPut(kind) { mutableSetOf() } += status
            assertTrue(status.value < 500, "step $i ($kind): $status")
            ratingsAgainstReviews().forEach { (product, pair) -> assertEquals(pair.second, pair.first, "step $i ($kind), product $product") }
            helpfulAgainstVotes().forEach { (review, pair) -> assertEquals(pair.second, pair.first, "step $i ($kind), review $review") }
            clock.advance(Duration.ofMinutes(10))
        }

        // The run reached every kind of step, and both what went through and what was refused.
        assertEquals(setOf("write", "approve", "reject", "edit", "delete", "vote", "reply"), seen.keys)
        assertTrue(HttpStatusCode.Created in seen.getValue("write"), "$seen")
        assertTrue(HttpStatusCode.OK in seen.getValue("approve") && HttpStatusCode.UnprocessableEntity in seen.getValue("approve"), "$seen")
        assertTrue(HttpStatusCode.NoContent in seen.getValue("delete"), "$seen")
        assertTrue(transaction(db) { ReviewTbl.selectAll().where { ReviewTbl.status eq ReviewStatus.APPROVED }.count() } > 0)
    }
}
