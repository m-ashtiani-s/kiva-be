package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.tbl.BlogFeedbackTbl
import com.kiva.infrastructure.persistence.exposed.tbl.AccountDeletionReasonTbl
import com.kiva.infrastructure.persistence.exposed.tbl.AddressTbl
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ContactMessageTbl
import com.kiva.infrastructure.persistence.exposed.tbl.IdempotencyKeyTbl
import com.kiva.infrastructure.persistence.exposed.tbl.NewsletterSubscriptionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefundTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReturnRequestTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReviewTbl
import com.kiva.infrastructure.persistence.exposed.tbl.StockAlertTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistItemTbl
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistShareTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.get
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DELETE /me` with everything the later phases keep of a customer (roadmap phase 14, step 14.2, decision 4): Sara has
 * an address, a cart, a wishlist with its share link, a «موجود شد خبرم کن», an approved review, a message of the contact
 * form, a newsletter subscription with her account's email, and a delivered order with a return turned down and a
 * refund completed. After the deletion every table that is only hers has nothing of her; the review, the order, the
 * return and the refund stay, without her name, phone or address.
 *
 * **Every table added later that keeps a user's own data gets a row in this test** (roadmap section 4.10).
 */
class AccountErasureOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val returns = AskedReturns(panel, clock)
    private val db = TestDatabase.database

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
        shareFlag(true)
    }

    @AfterEach
    fun deleteWhatWasStored() {
        shareFlag(false)
        transaction(db) { AccountDeletionReasonTbl.deleteWhere { AccountDeletionReasonTbl.reason eq REASON } }
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive", "red").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }
    private val nilaId by lazy {
        transaction(db) { ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq nila[0] }.single()[ProductVariantQmTbl.productId] }
    }
    private val admin by lazy { panel.callerWith(Role.ADMIN) }
    private val support by lazy { panel.callerWith(Role.SUPPORT) }

    /** The wishlist's share link is behind a flag of the settings (phase 9). */
    private fun shareFlag(on: Boolean) = transaction(db) { exec("""UPDATE shopsettings_tbl SET features = jsonb_set(features, '{wishlistShare}', '$on')""") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.call(method: String, path: String, who: PanelTestStack.Caller, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Authorization, who.bearer)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> client.get("$API_V1$path", build)
            "POST" -> client.post("$API_V1$path", build)
            "PUT" -> client.put("$API_V1$path", build)
            "PATCH" -> client.patch("$API_V1$path", build)
            "DELETE" -> client.delete("$API_V1$path", build)
            else -> error(method)
        }
    }

    private fun countOf(table: Table, where: () -> org.jetbrains.exposed.v1.core.Op<Boolean>): Long = transaction(db) { table.selectAll().where(where).count() }

    /** Sara, with something in every table of the later phases, and her delivered [Sara.order]. */
    private class Sara(val caller: PanelTestStack.Caller, val order: OrderCode, val refundId: Long, val returnId: Long, val reviewId: String)

    private suspend fun ApplicationTestBuilder.saraWithEverything(): Sara {
        val sara = panel.caller(PanelTestStack.phone(501), name = "سارا محمدی")
        val userId = sara.user.id

        assertEquals(HttpStatusCode.OK, call("PATCH", "/me", sara, """{"email":"$EMAIL"}""").status)
        panel.paidOrders.savedAddress(userId)

        val code = with(returns) { delivered(sara, nila[0]) }
        val returnId = with(returns) { asked(code, sara) }
        val rejected = call("POST", "/admin/returns/$returnId/decision", returns.operator(), """{"approve":false,"rejectionReason":"کیف استفاده شده بود"}""")
        assertEquals(HttpStatusCode.OK, rejected.status, rejected.bodyAsText())
        val opened = call("POST", "/admin/orders/${code.value}/refunds", admin, """{"amount":100000,"reason":"جبران تأخیر","method":"BANK_TRANSFER"}""")
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val refundId = opened.json().text("id")!!.toLong()
        assertEquals(HttpStatusCode.OK, call("PATCH", "/admin/refunds/$refundId", admin, """{"status":"COMPLETED","referenceId":"140507181234567"}""").status)

        val fresh = returns.fresh(sara)
        runBlocking { panel.purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), nila[1], 1)) }.getOrNull() ?: error("not added")
        val saved = call("PUT", "/me/wishlist/items/$nilaId", fresh)
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(HttpStatusCode.OK, call("POST", "/me/wishlist/share", fresh).status)
        transaction(db) {
            StockAlertTbl.insert {
                it[alertId] = 9_500_000L + userId.value
                it[StockAlertTbl.userId] = userId.value
                it[productId] = nilaId
                it[variantId] = null
                it[status] = StockAlertStatus.ACTIVE
                it[createdOn] = clock.now.atOffset(ZoneOffset.UTC)
            }
        }
        val review = call("POST", "/products/$nilaId/reviews", fresh, """{"rating":5,"authorName":"سارا","contact":"$EMAIL","text":"کیف خیلی خوبیه، دوخت تمیزی داره"}""")
        assertEquals(HttpStatusCode.Created, review.status, review.bodyAsText())
        val reviewId = review.json().text("id")!!
        assertEquals(HttpStatusCode.OK, call("POST", "/admin/reviews/$reviewId/moderation", support, """{"action":"APPROVE"}""").status)
        val message = call(
            "POST", "/contact/messages", fresh,
            """{"topic":"OTHER","fullName":"سارا محمدی","phone":"${sara.user.activePhone.value}","email":"$EMAIL","message":"سلام، یه سؤال درباره‌ی کیف‌ها داشتم."}""",
        )
        assertEquals(HttpStatusCode.Created, message.status, message.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.post("$API_V1/newsletter/subscriptions") { contentType(ContentType.Application.Json); setBody("""{"email":"$EMAIL"}""") }.status)
        val style = transaction(db) { exec("SELECT \"pId\" FROM blogcategory_tbl WHERE slug = 'style'") { it.next(); it.getLong(1) }!! }
        val post = call(
            "POST", "/admin/blog/posts", panel.callerWith(Role.CONTENT_EDITOR),
            """{"title":"راهنمای انتخاب کیف","slug":"erasure-post","categoryId":$style,"blocks":[{"type":"PARAGRAPH","text":"یک"}],"status":"PUBLISHED"}""",
        )
        assertEquals(HttpStatusCode.Created, post.status, post.bodyAsText())
        val vote = call("POST", "/blog/posts/${post.json().text("id")}/feedback", fresh, """{"helpful":false,"comment":"کوتاه بود"}""")
        assertEquals(HttpStatusCode.NoContent, vote.status, vote.bodyAsText())
        return Sara(fresh, code, refundId, returnId, reviewId)
    }

    private suspend fun ApplicationTestBuilder.deleted(sara: PanelTestStack.Caller): HttpResponse {
        assertEquals(HttpStatusCode.OK, call("POST", "/me/deletion/request", sara).status)
        val code = panel.purchase.auth.lastCodeSentTo(sara.user.activePhone.value)
        return call("DELETE", "/me", sara, """{"otpCode":"${code.value}","reason":"$REASON"}""")
    }

    @Test
    fun `everything only hers is gone - addresses, cart, wishlist and its link, stock alerts, keys, contact messages, the newsletter, blog votes`() = panel.app {
        val sara = saraWithEverything()
        val id = sara.caller.userId
        // Before: something in every one of them, or the test proves nothing.
        val tables = mapOf(
            "address_tbl" to { countOf(AddressTbl) { AddressTbl.userId eq id } },
            "cart_tbl" to { countOf(CartTbl) { CartTbl.userId eq id } },
            "wishlistitem_tbl" to { countOf(WishlistItemTbl) { WishlistItemTbl.userId eq id } },
            "wishlistshare_tbl" to { countOf(WishlistShareTbl) { WishlistShareTbl.userId eq id } },
            "stockalert_tbl" to { countOf(StockAlertTbl) { StockAlertTbl.userId eq id } },
            "idempotencykey_tbl" to { countOf(IdempotencyKeyTbl) { IdempotencyKeyTbl.userId eq id } },
            "contactmessage_tbl" to { countOf(ContactMessageTbl) { ContactMessageTbl.userId eq id } },
            "newslettersubscription_tbl" to { countOf(NewsletterSubscriptionTbl) { NewsletterSubscriptionTbl.email eq EMAIL } },
            "blogfeedback_tbl" to { countOf(BlogFeedbackTbl) { BlogFeedbackTbl.voterKey eq "u:$id" } },
        )
        tables.forEach { (name, count) -> assertTrue(count() > 0, "$name has nothing of Sara before") }

        val answer = deleted(sara.caller)

        assertEquals(HttpStatusCode.NoContent, answer.status, answer.bodyAsText())
        tables.forEach { (name, count) -> assertEquals(0, count(), "$name still has something of Sara") }
    }

    @Test
    fun `her review stays on the product's page as «کاربر کیوا», without its contact`() = panel.app {
        val sara = saraWithEverything()

        deleted(sara.caller)

        val shown = client.get("$API_V1/products/$nilaId/reviews").json().getValue("items").jsonArray.map { it.jsonObject }.single { it.text("id") == sara.reviewId }
        assertEquals("کاربر کیوا", shown.text("authorName"))
        val row = transaction(db) { ReviewTbl.selectAll().where { ReviewTbl.userId eq sara.caller.userId }.single() }
        assertEquals(listOf(null, null), listOf(row[ReviewTbl.authorName], row[ReviewTbl.contact]))
        assertEquals("کیف خیلی خوبیه، دوخت تمیزی داره", row[ReviewTbl.text])
    }

    @Test
    fun `the order stays in the panel with its province and city, nobody's name or phone, and the search can't find it by her phone`() = panel.app {
        val sara = saraWithEverything()
        val phone = sara.caller.user.activePhone.value

        deleted(sara.caller)

        val order = call("GET", "/admin/orders/${sara.order.value}", admin).json()
        val customer = order.getValue("customer").jsonObject
        assertEquals(listOf(null, null), listOf(customer.text("fullName"), customer.text("phone")))
        val address = order.getValue("address").jsonObject
        assertEquals(listOf("تهران", "تهران", null, null, null, null), listOf("provinceName", "cityName", "addressLine", "postalCode", "recipientName", "recipientPhone").map { address.text(it) })
        assertEquals("تهران، تهران", address.text("fullText"))
        assertEquals("DELIVERED", order.text("status"))
        val searchText = transaction(db) { OrderQmTbl.select(OrderQmTbl.searchText).where { OrderQmTbl.code eq sara.order.value }.single()[OrderQmTbl.searchText] }
        assertFalse(phone in searchText || "09123456789" in searchText, searchText)
        val found = call("GET", "/admin/orders?q=$phone", admin).json().getValue("items").jsonArray
        assertTrue(found.isEmpty(), "found by the phone of a deleted account: $found")
    }

    @Test
    fun `the return and the refund stay as they were - financial records`() = panel.app {
        val sara = saraWithEverything()

        deleted(sara.caller)

        val refund = transaction(db) { RefundTbl.selectAll().where { RefundTbl.refundId eq sara.refundId }.single() }
        assertEquals("COMPLETED", refund[RefundTbl.status].name)
        assertEquals(1, countOf(ReturnRequestTbl) { ReturnRequestTbl.returnId eq sara.returnId }.toInt())
    }

    @Test
    fun `the account is a tombstone, every session ends, the cookie is cleared and the reason is kept without an id`() = panel.app {
        val sara = saraWithEverything()

        val answer = deleted(sara.caller)

        assertTrue(answer.headers.getAll(HttpHeaders.SetCookie).orEmpty().any { it.startsWith("kiva_rt=;") && "Max-Age=0" in it && "Path=/api/v1/auth" in it })
        val row = transaction(db) { UserTbl.selectAll().where { UserTbl.id eq sara.caller.userId }.single() }
        assertNull(row[UserTbl.phone])
        assertNotNull(row[UserTbl.deletedOn])
        assertEquals(listOf(null, null, null), listOf(row[UserTbl.firstName], row[UserTbl.lastName], row[UserTbl.email]))
        val reasons = transaction(db) { AccountDeletionReasonTbl.selectAll().where { AccountDeletionReasonTbl.reason eq REASON }.map { it[AccountDeletionReasonTbl.createdOn] } }
        assertEquals(1, reasons.size)
        val sessions = transaction(db) { AuthSessionTbl.selectAll().where { AuthSessionTbl.userId eq sara.caller.userId }.map { it[AuthSessionTbl.revokeReason] } }
        assertTrue(sessions.isNotEmpty() && sessions.all { it == RevokeReason.ACCOUNT_DELETED }, "$sessions")
    }

    @Test
    fun `the access token of that very moment is refused at once - 401 on GET me`() = panel.app {
        val sara = saraWithEverything()

        deleted(sara.caller)

        val me = call("GET", "/me", sara.caller)
        assertEquals(HttpStatusCode.Unauthorized, me.status)
        assertEquals("UNAUTHORIZED", me.json().text("code"))
    }

    private companion object {
        const val EMAIL = "sara.erased@example.com"
        const val REASON = "دیگه از این‌جا خرید نمی‌کنم (آزمایش حذف)"
    }
}
