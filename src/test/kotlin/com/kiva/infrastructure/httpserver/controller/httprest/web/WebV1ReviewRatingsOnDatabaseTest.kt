package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.repository.ProductFilter
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.application.usecase.review.RatingBook
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.RatingTally
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.orFail
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * A product's rating where the shop shows it (roadmap step 8.4): the cards of the list and the related products, the
 * product's page, the panel's list, «بیشترین امتیاز»; and «نظر بده» on the customer's delivered orders.
 */
class WebV1ReviewRatingsOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val returns = AskedReturns(panel, clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(db) { exec("""DELETE FROM productrating_tbl WHERE "productId" IN (SELECT "productId" FROM productqm_tbl)""") }
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private fun productOf(variantId: Long): Long = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variantId }.single()[ProductVariantQmTbl.productId]
    }

    private val nila by lazy { listOf("black", "olive").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val nilaId by lazy { productOf(nila[0]) }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.written(productId: Long, who: PanelTestStack.Caller, stars: Int): String {
        val answer = client.post("$API_V1/products/$productId/reviews") {
            header(HttpHeaders.Authorization, returns.fresh(who).bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"rating":$stars,"text":"این کیف واقعاً همونیه که دیدم"}""")
        }
        assertEquals(HttpStatusCode.Created, answer.status, answer.bodyAsText())
        return answer.json().text("id")!!
    }

    private fun moderate(id: String, approve: Boolean) = transaction(db) {
        val review = panel.reviewRepository.lock(ReviewId.constructFromPersisted(id.toLong()))!!
        val before = RatingBook.Counted.of(review)
        if (approve) review.approve(null, UserId.SYSTEM, clock.now).getOrNull()!! else review.reject(ReviewText.constructFromPersisted("شامل تبلیغ"), UserId.SYSTEM, clock.now).getOrNull()!!
        panel.ratingBook.changed(before, review, clock.now)
        panel.reviewRepository.save(review)
    }

    private fun rated(productId: Long, vararg stars: Int) = transaction(db) {
        val id = ProductId.constructFromPersisted(productId)
        panel.productRatings.save(id, stars.fold(panel.productRatings.lock(id)) { t, s -> t.plus(StarRating.constructFromPersisted(s), false) }, clock.now)
    }

    private fun JsonObject.cardOf(productId: Long): JsonObject = getValue("items").jsonArray.map { it.jsonObject }.single { it.text("id") == productId.toString() }

    private fun JsonObject.brief(): Pair<String?, String?> = getValue("rating").jsonObject.let { it.text("average") to it.text("count") }

    @Test
    fun `an approved five-star review shows on the list's card, the page, the panel's list - turned down, back to nothing`() = panel.app {
        val id = written(nilaId, customer(1), 5)
        moderate(id, approve = true)
        val admin = panel.caller(PanelTestStack.phone(80), Role.ADMIN)

        val card = client.get("$API_V1/products?size=48").json().cardOf(nilaId)
        val page = client.get("$API_V1/products/nila-crossbody-bag").json()
        val adminCard = client.get("$API_V1/admin/products?size=48") { header(HttpHeaders.Authorization, admin.bearer) }.json().cardOf(nilaId)

        assertEquals("5.0" to "1", card.brief())
        assertEquals("5.0" to "1", page.brief())
        assertEquals(listOf("1", "5.0", "0"), page.getValue("ratingSummary").jsonObject.let { listOf(it.text("count"), it.text("average"), it.text("verifiedBuyerPercent")) })
        assertEquals("1", page.getValue("ratingSummary").jsonObject.getValue("distribution").jsonArray.first().jsonObject.text("count"))
        assertEquals("5.0" to "1", adminCard.brief())

        moderate(id, approve = false)
        assertEquals("0.0" to "0", client.get("$API_V1/products?size=48").json().cardOf(nilaId).brief())
        assertEquals(JsonNull, client.get("$API_V1/products/nila-crossbody-bag").json().getValue("ratingSummary").jsonObject["verifiedBuyerPercent"])
    }

    @Test
    fun `«بیشترین امتیاز» - 4,8 of ten above 4,8 of five, then the rest without any, a sold-out one last`() = panel.app {
        val inStock = transaction(db) { ProductQmTbl.select(ProductQmTbl.productId).where { ProductQmTbl.inStock eq true }.map { it[ProductQmTbl.productId] } }
        val (fewer, more, low, soldOut) = inStock.take(4)
        rated(fewer, 5, 5, 5, 5, 4)
        rated(more, 5, 5, 5, 5, 4, 5, 5, 5, 5, 4)
        rated(low, 3)
        rated(soldOut, 5)
        transaction(db) { ProductQmTbl.update({ ProductQmTbl.productId eq soldOut }) { it[ProductQmTbl.inStock] = false } }

        val ids = client.get("$API_V1/products?sort=rating_desc&size=48").json().getValue("items").jsonArray.map { it.jsonObject.text("id")!!.toLong() }

        assertEquals(listOf(more, fewer, low), ids.take(3))
        assertEquals(true, ids.indexOf(soldOut) > ids.indexOfLast { it in inStock && it != soldOut }, "sold out after everything that can be bought, its rating or not")
    }

    @Test
    fun `a page of cards reads the ratings in one statement - no more for 24 cards than for one`() = panel.app {
        val readModel = com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl()
        fun statementsFor(size: Int): Int = transaction(db) {
            val before = statementCount
            readModel.find(ProductFilter(), Pageable.makeNew(1, size, ProductSorts.DEFAULT, ProductSorts.names).orFail())
            statementCount - before
        }
        rated(nilaId, 4)

        assertEquals(statementsFor(1), statementsFor(24))
    }

    @Test
    fun `a delivered order shows «نظر بده» until each of its products is reviewed - each line says whether its product is`() = panel.app {
        val sara = customer(1)
        val mahak = panel.purchase.variantOf("mahak-shoulder-bag", "black")
        val code = with(returns) { delivered(sara, nila[0], mahak) }
        suspend fun page() = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, returns.fresh(sara).bearer) }.json()
        suspend fun inList() = client.get("$API_V1/me/orders") { header(HttpHeaders.Authorization, returns.fresh(sara).bearer) }.json()
            .getValue("items").jsonArray.single().jsonObject.getValue("actions").jsonObject.text("canReview")
        fun JsonObject.reviewed() = getValue("items").jsonArray.map { it.jsonObject.text("reviewed") }

        val before = page()
        written(nilaId, sara, 5)
        val half = page()
        written(productOf(mahak), sara, 4)
        val after = page()

        assertEquals("true", before.getValue("actions").jsonObject.text("canReview"))
        assertEquals(listOf("false", "false"), before.reviewed())
        assertEquals(listOf("true", "false"), half.reviewed())
        assertEquals("true", half.getValue("actions").jsonObject.text("canReview"))
        assertEquals(listOf("true", "true"), after.reviewed())
        assertEquals("false", after.getValue("actions").jsonObject.text("canReview"))
        assertEquals("false", inList())
    }

    @Test
    fun `an order not delivered yet never shows «نظر بده»`() = panel.app {
        val sara = customer(1)
        val code = panel.paidOrders.paid(sara.user.id, nila[0])

        val page = client.get("$API_V1/me/orders/${code.value}") { header(HttpHeaders.Authorization, returns.fresh(sara).bearer) }.json()

        assertEquals("false", page.getValue("actions").jsonObject.text("canReview"))
        assertEquals(RatingTally.NONE, transaction(db) { panel.productRatings.find(ProductId.constructFromPersisted(nilaId)) })
    }
}
