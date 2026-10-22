package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A wishlist's public link over kiva_test (roadmap step 9.3): one link however often it is asked for, revoked at once,
 * a new token after; the flag; what the public list shows (the owner's first name only, never a phone, published
 * products only) and that every way of not being there is the same 404.
 */
class WebV1WishlistShareOnDatabaseTest {

    private val db = TestDatabase.database
    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
        flag(true)
    }

    @AfterEach
    fun deleteWhatWasStored() {
        flag(false)
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private fun flag(on: Boolean) = transaction(db) { exec("""UPDATE shopsettings_tbl SET features = jsonb_set(features, '{wishlistShare}', '$on')""") }

    private fun productOf(slug: String): Long {
        val variant = panel.purchase.variantOf(slug, "black")
        return transaction(db) { ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variant }.single()[ProductVariantQmTbl.productId] }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.share(who: PanelTestStack.Caller): HttpResponse =
        client.post("$API_V1/me/wishlist/share") { header(HttpHeaders.Authorization, who.bearer) }

    private suspend fun ApplicationTestBuilder.shared(token: String): HttpResponse = client.get("$API_V1/wishlists/shared/$token")

    @Test
    fun `asked twice - one token - revoked, the old link 404 at once - asked again, a new token - the list shows it`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))

        val first = share(sara)
        val again = share(sara)

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val link = first.json()
        assertEquals(ContractSchemas.propertiesOf("WishlistShare"), link.keys)
        val token = link.text("token")!!
        assertTrue(Regex("wl_[0-9A-Za-z]{32}").matches(token))
        assertEquals("${PanelTestStack.SITE_URL}/wishlist/shared/$token", link.text("url"))
        assertEquals(token, again.json().text("token"))
        assertEquals(token, client.get("$API_V1/me/wishlist") { header(HttpHeaders.Authorization, sara.bearer) }.json().getValue("share").jsonObject.text("token"))
        assertEquals(HttpStatusCode.OK, shared(token).status)

        val revoked = client.delete("$API_V1/me/wishlist/share") { header(HttpHeaders.Authorization, sara.bearer) }
        val revokedAgain = client.delete("$API_V1/me/wishlist/share") { header(HttpHeaders.Authorization, sara.bearer) }
        assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.NoContent), listOf(revoked.status, revokedAgain.status))
        assertEquals(HttpStatusCode.NotFound, shared(token).status)
        assertEquals(JsonNull, client.get("$API_V1/me/wishlist") { header(HttpHeaders.Authorization, sara.bearer) }.json()["share"])

        val fresh = share(sara).json().text("token")!!
        assertNotEquals(token, fresh)
        assertEquals(HttpStatusCode.OK, shared(fresh).status)
    }

    @Test
    fun `the flag off - sharing 422, the public list 404, no share in the list - revoking still 204`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        val token = share(sara).json().text("token")!!
        flag(false)

        val refused = share(sara)
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("WISHLIST_SHARE_DISABLED", refused.json().text("code"))
        assertEquals(HttpStatusCode.NotFound, shared(token).status)
        assertEquals(JsonNull, client.get("$API_V1/me/wishlist") { header(HttpHeaders.Authorization, sara.bearer) }.json()["share"])
        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/me/wishlist/share") { header(HttpHeaders.Authorization, sara.bearer) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$API_V1/me/wishlist/share").status)
    }

    @Test
    fun `the public list - the first name only, never a phone, published products only, no-store - a nameless owner is a friend of Kiva`() = panel.app {
        val named = panel.caller(PanelTestStack.phone(901), name = "سارا محمدی")
        val nameless = panel.caller(PanelTestStack.phone(902))
        val (nila, mahak) = listOf("nila-crossbody-bag", "mahak-shoulder-bag").map(::productOf)
        listOf(nila, mahak).forEach { client.put("$API_V1/me/wishlist/items/$it") { header(HttpHeaders.Authorization, named.bearer) } }
        transaction(db) { ProductQmTbl.update({ ProductQmTbl.productId eq mahak }) { it[status] = ProductStatus.DRAFT } }
        val token = share(named).json().text("token")!!

        val answer = shared(token)

        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertEquals("no-store", answer.headers[HttpHeaders.CacheControl])
        val body = answer.json()
        assertEquals(ContractSchemas.propertiesOf("SharedWishlist"), body.keys)
        assertEquals("سارا", body.text("ownerDisplayName"))
        val items = body.getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(nila.toString()), items.map { it.text("id") })
        assertEquals(JsonNull, items.single()["isWishlisted"])
        val text = answer.bodyAsText()
        assertTrue(named.user.phone.value !in text && named.user.phone.value.drop(1) !in text, "no phone in the answer")
        assertTrue("محمدی" !in text, "no last name")
        transaction(db) { ProductQmTbl.update({ ProductQmTbl.productId eq mahak }) { it[status] = ProductStatus.ACTIVE } }

        val friend = shared(share(nameless).json().text("token")!!).json()
        assertEquals(listOf("یکی از دوستان کیوا", "[]"), listOf(friend.text("ownerDisplayName"), friend.getValue("items").toString()))
    }

    @Test
    fun `a token one character short or long, not one at all, or nobody's - the same 404 every time`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(901))
        val token = share(sara).json().text("token")!!

        val answers = listOf(token.dropLast(1), token + "a", "wl_" + "0".repeat(32), "not-a-token", "WL_" + token.drop(3)).map { shared(it) }

        assertEquals(List(5) { HttpStatusCode.NotFound }, answers.map { it.status })
        assertEquals(1, answers.map { it.json().let { p -> p.text("code") to p.text("message") } }.toSet().size, "one answer for all")
    }
}
