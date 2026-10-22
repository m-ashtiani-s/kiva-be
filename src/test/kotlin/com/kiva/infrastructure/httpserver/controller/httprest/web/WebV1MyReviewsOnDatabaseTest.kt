package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.review.RatingBook
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewId
import com.kiva.domain.aggregate.review.model.valueobject.ReviewText
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Writing, editing and deleting a review, «نظرات من» and «منتظر نظر تو» over kiva_test (roadmap step 8.2): orders
 * delivered the way the shop does it, the customer's own photos uploaded with `POST /me/uploads`, every answer held
 * against the contract's Review. Support's moderation (step 8.5) is played here straight on the rows, the way its use
 * case will do it (RatingBook included).
 */
class WebV1MyReviewsOnDatabaseTest {

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
        transaction(db) { exec("""UPDATE shopsettings_tbl SET features = jsonb_set(features, '{reviewMedia}', 'false')""") }
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val nilaId by lazy { productOf(nila[0]) }

    private fun productOf(variantId: Long): Long = transaction(db) {
        ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variantId }.single()[ProductVariantQmTbl.productId]
    }

    /** Products in the shop, any [n] of them. */
    private fun activeProducts(n: Int): List<Long> = transaction(db) {
        ProductQmTbl.select(ProductQmTbl.productId).where { (ProductQmTbl.status eq com.kiva.domain.aggregate.product.model.valueobject.ProductStatus.ACTIVE) and (ProductQmTbl.inStock eq true) }
            .map { it[ProductQmTbl.productId] }.take(n)
    }

    private fun customer(n: Int) = panel.caller(PanelTestStack.phone(900 + n))

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private suspend fun ApplicationTestBuilder.write(productId: Long, who: PanelTestStack.Caller, body: String = REVIEW): HttpResponse =
        client.post("$API_V1/products/$productId/reviews") {
            header(HttpHeaders.Authorization, returns.fresh(who).bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.edit(reviewId: String, who: PanelTestStack.Caller, body: String): HttpResponse =
        client.patch("$API_V1/me/reviews/$reviewId") {
            header(HttpHeaders.Authorization, returns.fresh(who).bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.mine(who: PanelTestStack.Caller): JsonObject =
        client.get("$API_V1/me/reviews") { header(HttpHeaders.Authorization, returns.fresh(who).bearer) }.json()

    private suspend fun ApplicationTestBuilder.awaiting(who: PanelTestStack.Caller): JsonArray =
        Json.parseToJsonElement(client.get("$API_V1/me/reviews/pending-products") { header(HttpHeaders.Authorization, returns.fresh(who).bearer) }.bodyAsText()) as JsonArray

    private suspend fun ApplicationTestBuilder.photo(who: PanelTestStack.Caller): String {
        val uploaded = client.post("$API_V1/me/uploads") {
            header(HttpHeaders.Authorization, returns.fresh(who).bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "REVIEW_PHOTO")
                        append("file", FakeMediaProcessor.photo(1200, 900), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        return uploaded.json().text("id")!!
    }

    /** Support approves or turns down review [id] the way ModerateReviewUc will (step 8.5): under its lock, the rating with it. */
    private fun moderate(id: String, approve: Boolean) = transaction(db) {
        val review = panel.reviewRepository.lock(ReviewId.constructFromPersisted(id.toLong()))!!
        val before = RatingBook.Counted.of(review)
        if (approve) review.approve(null, UserId.SYSTEM, clock.now).getOrNull()!! else review.reject(ReviewText.constructFromPersisted("شامل تبلیغ"), UserId.SYSTEM, clock.now).getOrNull()!!
        panel.ratingBook.changed(before, review, clock.now)
        panel.reviewRepository.save(review)
    }

    private fun ratingOf(productId: Long) = transaction(db) { panel.productRatings.find(ProductId.constructFromPersisted(productId)) }

    private fun setReviewMedia(on: Boolean) = transaction(db) { exec("""UPDATE shopsettings_tbl SET features = jsonb_set(features, '{reviewMedia}', '$on')""") }

    @Test
    fun `written - 201 PENDING, its author's, held against Review - again 409 - after it is turned down 201`() = panel.app {
        val sara = customer(1)

        val written = write(nilaId, sara)
        val again = write(nilaId, sara)

        assertEquals(HttpStatusCode.Created, written.status, written.bodyAsText())
        val review = written.json()
        assertEquals(ContractSchemas.propertiesOf("Review"), review.keys)
        assertEquals(listOf("PENDING", "در انتظار تأیید", "true", "5", "سارا"), listOf("status", "statusLabel", "isMine", "rating", "authorName").map { review.text(it) })
        assertEquals("خیلی خوبه\n\nبندش هم بلنده", review.text("text"))
        listOf("product", "title", "rejectionReason", "purchasedColor", "reply", "myHelpfulVote").forEach { assertEquals(JsonNull, review[it], it) }
        assertEquals("false", review.text("isVerifiedBuyer"))
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("REVIEW_ALREADY_EXISTS", again.json().text("code"))
        moderate(review.text("id")!!, approve = false)
        assertEquals(HttpStatusCode.Created, write(nilaId, sara).status)
    }

    @Test
    fun `a buyer's review is linked by itself to their latest delivered line, its color as bought - someone who didn't buy it isn't a buyer`() = panel.app {
        val sara = customer(1)
        val reza = customer(2)
        with(returns) { delivered(sara, nila[1]) }

        val bought = write(nilaId, sara).json()
        val notBought = write(nilaId, reza).json()

        assertEquals("true", bought.text("isVerifiedBuyer"))
        assertEquals("olive", bought.getValue("purchasedColor").jsonObject.text("key"))
        assertEquals("false", notBought.text("isVerifiedBuyer"))
        assertEquals(JsonNull, notBought["purchasedColor"])
    }

    @Test
    fun `linked by name - someone else's order 404, a line of another product 400, an order shipped but not delivered 422, both or neither 400`() = panel.app {
        val sara = customer(1)
        val reza = customer(2)
        val mahak = panel.purchase.variantOf("mahak-shoulder-bag", "black")
        val rezas = with(returns) { delivered(reza, nila[0]) }
        val two = with(returns) { delivered(sara, nila[0], mahak) }
        val shipped = panel.paidOrders.paid(sara.user.id, nila[1])
        with(returns.shippedOrders) { shipped(shipped, returns.fresh(sara), "PT-8100001") }

        fun linked(code: OrderCode, line: Int) = """{"rating":4,"text":"$TEXT","orderCode":"${code.value}","orderItemId":$line}"""
        val othersOrder = write(nilaId, sara, linked(rezas, 1))
        val otherProduct = write(nilaId, sara, linked(two, 2))
        val notDelivered = write(nilaId, sara, linked(shipped, 1))
        val halfLinked = write(nilaId, sara, """{"rating":4,"text":"$TEXT","orderCode":"${two.value}"}""")
        val linkedRight = write(nilaId, sara, linked(two, 1))

        assertEquals(HttpStatusCode.NotFound, othersOrder.status)
        assertEquals("ORDER_NOT_FOUND", othersOrder.json().text("code"))
        assertEquals(listOf("orderItemId ORDER_ITEM_PRODUCT_MISMATCH"), otherProduct.errors())
        assertEquals(HttpStatusCode.UnprocessableEntity, notDelivered.status)
        assertEquals("REVIEW_PURCHASE_NOT_DELIVERED", notDelivered.json().text("code"))
        assertEquals(listOf("orderItemId FIELD_REQUIRED"), halfLinked.errors())
        assertEquals(HttpStatusCode.Created, linkedRight.status, linkedRight.bodyAsText())
        assertEquals("true", linkedRight.json().text("isVerifiedBuyer"))
    }

    @Test
    fun `photos - with the flag off 400 REVIEW_MEDIA_DISABLED - on, someone else's is MEDIA_NOT_FOUND and one's own goes in`() = panel.app {
        val sara = customer(1)
        val reza = customer(2)
        val saras = photo(sara)
        val rezas = photo(reza)
        fun withPhoto(id: String) = """{"rating":5,"text":"$TEXT","mediaIds":["$id"]}"""

        val off = write(nilaId, sara, withPhoto(saras))
        setReviewMedia(true)
        val others = write(nilaId, sara, withPhoto(rezas))
        val own = write(nilaId, sara, withPhoto(saras))

        assertEquals(listOf("mediaIds REVIEW_MEDIA_DISABLED"), off.errors())
        assertEquals(listOf("mediaIds.0 MEDIA_NOT_FOUND"), others.errors())
        assertEquals(HttpStatusCode.Created, own.status, own.bodyAsText())
        assertEquals(saras, own.json().getValue("media").jsonArray.single().jsonObject.text("id"))
    }

    @Test
    fun `the shape, every problem at once - no login 401 - a product not in the shop 404`() = panel.app {
        val sara = customer(1)

        val wrong = write(nilaId, sara, """{"rating":6,"text":"کوتاه","contact":"سارا","authorName":"${"ا".repeat(41)}","orderItemId":1,"mediaIds":["nope"]}""")
        val nothing = write(nilaId, sara, "{}")
        val anonymous = client.post("$API_V1/products/$nilaId/reviews") { contentType(ContentType.Application.Json); setBody(REVIEW) }
        val noProduct = write(999_999_999, sara)

        assertEquals(
            listOf("rating RATING_INVALID", "authorName TEXT_TOO_LONG", "contact CONTACT_INVALID", "text TEXT_TOO_SHORT", "orderCode FIELD_REQUIRED", "mediaIds.0 MEDIA_ID_INVALID"),
            wrong.errors(),
        )
        assertEquals(listOf("rating FIELD_REQUIRED", "text FIELD_REQUIRED"), nothing.errors())
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        assertEquals(HttpStatusCode.NotFound, noProduct.status)
        assertEquals("PRODUCT_NOT_FOUND", noProduct.json().text("code"))
    }

    @Test
    fun `the sixth review in 24 hours is 429 RATE_LIMITED with Retry-After`() = panel.app {
        val sara = customer(1)
        val products = activeProducts(6)
        assertEquals(6, products.size, "the seed has six products in the shop")

        products.take(5).forEach { assertEquals(HttpStatusCode.Created, write(it, sara).status) }
        val sixth = write(products[5], sara)

        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertTrue(sixth.headers[HttpHeaders.RetryAfter]!!.toLong() > 0)
    }

    @Test
    fun `edits - an APPROVED one 422 - a REJECTED one back to PENDING, its reason gone - {} changes nothing - someone else's 404`() = panel.app {
        val sara = customer(1)
        val mahak = productOf(panel.purchase.variantOf("mahak-shoulder-bag", "black"))
        val approved = write(nilaId, sara).json().text("id")!!
        val rejected = write(mahak, sara).json().text("id")!!
        moderate(approved, approve = true)
        moderate(rejected, approve = false)

        val approvedEdit = edit(approved, sara, """{"rating":1}""")
        val unchanged = edit(rejected, sara, "{}")
        val rejectedEdit = edit(rejected, sara, """{"text":"دوباره نوشتم، بدون تبلیغ","authorName":""}""")
        val others = edit(rejected, customer(2), """{"rating":1}""")

        assertEquals(HttpStatusCode.UnprocessableEntity, approvedEdit.status)
        assertEquals("REVIEW_NOT_EDITABLE", approvedEdit.json().text("code"))
        assertEquals(HttpStatusCode.OK, unchanged.status)
        assertEquals(listOf("REJECTED", "شامل تبلیغ"), listOf(unchanged.json().text("status"), unchanged.json().text("rejectionReason")))
        assertEquals(HttpStatusCode.OK, rejectedEdit.status, rejectedEdit.bodyAsText())
        val review = rejectedEdit.json()
        assertEquals(listOf("PENDING", null, "کاربر کیوا", "دوباره نوشتم، بدون تبلیغ"), listOf("status", "rejectionReason", "authorName", "text").map { review.text(it) })
        assertEquals(HttpStatusCode.NotFound, others.status)
        val stored = transaction(db) { panel.reviewRepository.find(ReviewId.constructFromPersisted(rejected.toLong()))!! }
        assertEquals(5, stored.rating.value, "someone else's edit changed nothing")
    }

    @Test
    fun `an approved review deleted takes its stars out of the product's rating - a pending one deleted changes nothing`() = panel.app {
        val sara = customer(1)
        val reza = customer(2)
        val saras = write(nilaId, sara).json().text("id")!!
        val rezas = write(nilaId, reza, """{"rating":3,"text":"$TEXT"}""").json().text("id")!!
        moderate(saras, approve = true)
        assertEquals(1 to 5, ratingOf(nilaId).let { it.count to it.sum })

        val byOther = client.delete("$API_V1/me/reviews/$saras") { header(HttpHeaders.Authorization, returns.fresh(reza).bearer) }
        assertEquals(HttpStatusCode.NotFound, byOther.status)
        assertEquals(1 to 5, ratingOf(nilaId).let { it.count to it.sum }, "someone else can't delete it")
        val deleted = client.delete("$API_V1/me/reviews/$saras") { header(HttpHeaders.Authorization, returns.fresh(sara).bearer) }
        val pendingDeleted = client.delete("$API_V1/me/reviews/$rezas") { header(HttpHeaders.Authorization, returns.fresh(reza).bearer) }
        val again = client.delete("$API_V1/me/reviews/$saras") { header(HttpHeaders.Authorization, returns.fresh(sara).bearer) }

        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.NoContent, pendingDeleted.status)
        assertEquals(HttpStatusCode.NotFound, again.status)
        assertEquals(0 to 0, ratingOf(nilaId).let { it.count to it.sum })
    }

    @Test
    fun `«نظرات من» - every status, newest first, each with its product, the counts with rejected`() = panel.app {
        val sara = customer(1)
        val mahak = productOf(panel.purchase.variantOf("mahak-shoulder-bag", "black"))
        val first = write(nilaId, sara).json().text("id")!!
        clock.advance(java.time.Duration.ofMinutes(1))
        write(mahak, sara)
        moderate(first, approve = false)

        val list = mine(sara)

        val items = list.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(mahak.toString(), nilaId.toString()), items.map { it.text("productId") })
        assertEquals(listOf("nila-crossbody-bag"), items.drop(1).map { it.getValue("product").jsonObject.text("slug") })
        assertEquals("شامل تبلیغ", items[1].text("rejectionReason"), "the author sees why")
        val counts = list.getValue("counts").jsonObject
        assertEquals(listOf("2", "1", "0", "1"), listOf("total", "pending", "approved", "rejected").map { counts.text(it) })
    }

    @Test
    fun `«منتظر نظر تو» - one row for a product bought twice, the latest delivery - gone once reviewed`() = panel.app {
        val sara = customer(1)
        with(returns) { delivered(sara, nila[0]) }
        val latest = with(returns) { delivered(sara, nila[1]) }

        val before = awaiting(sara)
        write(nilaId, sara)
        val after = awaiting(sara)

        val row = before.single().jsonObject
        assertEquals(setOf("orderCode", "orderItemId", "product", "color", "deliveredAt"), row.keys)
        assertEquals(listOf(latest.value, "olive"), listOf(row.text("orderCode"), row.getValue("color").jsonObject.text("key")))
        assertEquals(nilaId.toString(), row.getValue("product").jsonObject.text("id"))
        assertEquals(0, after.size)
    }

    private companion object {
        const val TEXT = "کیف خوبیه، رنگش همونه"
        const val REVIEW = """{"rating":5,"authorName":"سارا","contact":"09121234567","text":"خیلی خوبه\n\n\n\nبندش هم بلنده"}"""
    }
}
