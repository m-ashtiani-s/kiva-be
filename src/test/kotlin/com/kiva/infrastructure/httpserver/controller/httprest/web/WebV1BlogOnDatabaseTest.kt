package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.BlogFeedbackTbl
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
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shop's magazine on kiva_test (roadmap phase 15): `/blog/categories`, `/blog/posts` and `/blog/posts/{idOrSlug}`
 * (step 15.3), the related posts and «مفید بود؟» (step 15.4).
 */
class WebV1BlogOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-24T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val catalog = StoredCatalog()
    private val db = TestDatabase.database

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        catalog.deleteAll()
        seeded.deleteSeeded()
    }

    private val editor by lazy { panel.callerWith(Role.CONTENT_EDITOR) }
    private val nilaId by lazy {
        val variant = panel.purchase.variantOf("nila-crossbody-bag", "black")
        transaction(db) { ProductVariantQmTbl.select(ProductVariantQmTbl.productId).where { ProductVariantQmTbl.variantId eq variant }.single()[ProductVariantQmTbl.productId] }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun HttpResponse.errors(): List<String> =
        json()["errors"]?.jsonArray.orEmpty().map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" }

    private fun categoryId(slug: String): Long = transaction(db) {
        exec("SELECT \"pId\" FROM blogcategory_tbl WHERE slug = '$slug'") { it.next(); it.getLong(1) }!!
    }

    /** Writes a post in the panel; its id. */
    private suspend fun ApplicationTestBuilder.write(
        slug: String,
        status: String = "PUBLISHED",
        category: String = "style",
        featured: Boolean = false,
        title: String = "راهنمای انتخاب کیف",
        blocks: String = """[{"type":"HEADING","text":"چرا فرم بدن مهمه؟"},{"type":"PARAGRAPH","text":"کیف فقط یه وسیله نیست."}]""",
        extra: String = "",
    ): String {
        val body = """{"title":"$title","slug":"$slug","categoryId":${categoryId(category)},"blocks":$blocks,"tags":["فرم بدن"],"isFeatured":$featured,"status":"$status"$extra}"""
        val made = client.post("$API_V1/admin/blog/posts") { header(HttpHeaders.Authorization, editor.bearer); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        return made.json().text("id")!!
    }

    private suspend fun ApplicationTestBuilder.shop(path: String): HttpResponse = client.get("$API_V1$path")

    private suspend fun ApplicationTestBuilder.slugsOf(path: String): List<String?> =
        shop(path).json().getValue("items").jsonArray.map { it.jsonObject.text("slug") }

    @Test
    fun `the featured post only on page one - out of the items and the count - and not when the filter leaves it out`() = panel.app {
        write("first")
        write("second")
        write("the-featured", featured = true)
        write("in-care", category = "care")
        write("a-draft", status = "DRAFT")

        val one = shop("/blog/posts?size=2").json()
        assertEquals("the-featured", one.getValue("featured").jsonObject.text("slug"))
        assertEquals(listOf("in-care", "second"), one.getValue("items").jsonArray.map { it.jsonObject.text("slug") })
        assertEquals(listOf("3", "2", "true"), listOf("totalItems", "totalPages", "hasNext").map { one.getValue("meta").jsonObject.text(it) })
        assertNull(one.text("emptyMessage"))
        assertEquals(setOf("featured", "items", "meta", "emptyMessage"), one.keys)

        val two = shop("/blog/posts?size=2&page=2").json()
        assertEquals(JsonNull, two["featured"])
        assertEquals(listOf("first"), two.getValue("items").jsonArray.map { it.jsonObject.text("slug") })

        val care = shop("/blog/posts?category=care").json()
        assertEquals(JsonNull, care["featured"])
        assertEquals(listOf("in-care"), care.getValue("items").jsonArray.map { it.jsonObject.text("slug") })
        assertEquals("the-featured", shop("/blog/posts?category=style").json().getValue("featured").jsonObject.text("slug"))
    }

    @Test
    fun `an unknown category or tag is an empty list with its message - words and a tag are found normalized - too long and too big are 400`() = panel.app {
        write("first")
        write("other", title = "نگه‌داری چرم", blocks = """[{"type":"PARAGRAPH","text":"چرم رو خشک نگه دار."}]""")

        val nowhere = shop("/blog/posts?category=no-such-category").json()
        assertEquals(JsonNull, nowhere["featured"])
        assertEquals(0, nowhere.getValue("items").jsonArray.size)
        assertEquals("مقاله‌ای با این مشخصات پیدا نشد.", nowhere.text("emptyMessage"))
        assertEquals(emptyList<String?>(), slugsOf("/blog/posts?tag=" + "x".repeat(31)))
        assertEquals(listOf("first"), slugsOf("/blog/posts?q=راهنماي"))
        assertEquals(listOf("other", "first"), slugsOf("/blog/posts?tag=فرم بدن"))
        assertEquals(listOf("q SEARCH_QUERY_TOO_LONG"), shop("/blog/posts?q=" + "a".repeat(101)).errors())
        assertEquals(HttpStatusCode.BadRequest, shop("/blog/posts?size=31").status)
        assertEquals("9", shop("/blog/posts").json().getValue("meta").jsonObject.text("size"))
    }

    @Test
    fun `a post scheduled for an hour later is 404 and out of the list until then - a draft and an archived post are 404`() = panel.app {
        val later = clock.now.plus(Duration.ofHours(1))
        write("scheduled", extra = ""","publishedAt":"$later"""")
        write("a-draft", status = "DRAFT")
        write("archived", status = "ARCHIVED")

        assertEquals(HttpStatusCode.NotFound, shop("/blog/posts/scheduled").status)
        assertEquals(emptyList<String?>(), slugsOf("/blog/posts"))
        assertEquals(listOf("NOT_FOUND", "NOT_FOUND"), listOf(shop("/blog/posts/a-draft").json().text("code"), shop("/blog/posts/archived").json().text("code")))

        clock.advance(Duration.ofHours(1))
        assertEquals(HttpStatusCode.OK, shop("/blog/posts/scheduled").status)
        assertEquals(listOf("scheduled"), slugsOf("/blog/posts"))
    }

    @Test
    fun `the id and the slug are the same post - with its page's fields - and a product taken out of the shop leaves no block`() = panel.app {
        val blocks = """[{"type":"HEADING","text":"یک"},{"type":"PRODUCT","productId":$nilaId},{"type":"PARAGRAPH","text":"دو"}]"""
        val id = write("bag-by-body-shape", blocks = blocks)

        val byId = shop("/blog/posts/$id")
        assertEquals(HttpStatusCode.OK, byId.status, byId.bodyAsText())
        val page = shop("/blog/posts/bag-by-body-shape").json()
        assertEquals(byId.json(), page)
        assertEquals(listOf("BlogPostSummary", "BlogPostDetail").flatMap(ContractSchemas::propertiesOf).toSet(), page.keys)
        assertEquals(listOf("HEADING", "PRODUCT", "PARAGRAPH"), page.getValue("blocks").jsonArray.map { it.jsonObject.text("type") })
        assertEquals(JsonNull, page.getValue("blocks").jsonArray[1].jsonObject.getValue("product").jsonObject["isWishlisted"])
        assertTrue(page.text("shareUrl")!!.endsWith("/blog/bag-by-body-shape"), page.text("shareUrl"))
        assertEquals(HttpStatusCode.NotFound, shop("/blog/posts/999999").status)

        transaction(db) { ProductQmTbl.update({ ProductQmTbl.productId eq nilaId }) { it[status] = ProductStatus.ARCHIVED } }
        assertEquals(listOf("HEADING", "PARAGRAPH"), shop("/blog/posts/$id").json().getValue("blocks").jsonArray.map { it.jsonObject.text("type") })
    }

    @Test
    fun `postCount leaves drafts and scheduled posts out - the answers are cached - 304 with If-None-Match`() = panel.app {
        write("first")
        write("second", category = "care")
        write("a-draft", status = "DRAFT")
        write("scheduled", extra = ""","publishedAt":"${clock.now.plus(Duration.ofDays(1))}"""")

        val categories = Json.parseToJsonElement(shop("/blog/categories").bodyAsText()).jsonArray.map { it.jsonObject }
        val counts = categories.associate { it.text("slug") to it.text("postCount") }
        assertEquals(mapOf<String?, String?>("buying-guide" to "0", "style" to "1", "care" to "1", "materials" to "0", "behind-the-scenes" to "0"), counts)
        assertEquals(listOf("buying-guide", "style", "care", "materials", "behind-the-scenes"), counts.keys.toList())

        for (path in listOf("/blog/categories", "/blog/posts", "/blog/posts/first")) {
            val first = shop(path)
            val etag = first.headers[HttpHeaders.ETag]!!
            assertTrue("max-age=300" in first.headers[HttpHeaders.CacheControl].orEmpty(), path)
            assertEquals(HttpStatusCode.NotModified, client.get("$API_V1$path") { header(HttpHeaders.IfNoneMatch, etag) }.status, path)
        }
        assertNull(shop("/blog/posts/a-draft").headers[HttpHeaders.ETag], "a 404 is never cached")
    }

    private suspend fun ApplicationTestBuilder.vote(postId: String, body: String, who: PanelTestStack.Caller? = null, ip: String? = null): HttpResponse =
        client.post("$API_V1/blog/posts/$postId/feedback") {
            who?.let { header(HttpHeaders.Authorization, it.bearer) }
            ip?.let { header(HttpHeaders.XForwardedFor, it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun votesOf(postId: String): List<Triple<String, Boolean, String?>> = transaction(db) {
        BlogFeedbackTbl.selectAll().where { BlogFeedbackTbl.postId eq postId.toLong() }.orderBy(BlogFeedbackTbl.voterKey)
            .map { Triple(it[BlogFeedbackTbl.voterKey], it[BlogFeedbackTbl.helpful], it[BlogFeedbackTbl.comment]) }
    }

    @Test
    fun `related - the same category first, the latest first, never the post itself - limit 1 to 12 - a post the shop doesn't see is 404`() = panel.app {
        val post = write("the-post")
        write("other-care", category = "care")
        write("old-style")
        clock.advance(Duration.ofMinutes(1))
        write("new-style")
        write("newest-care", category = "care")
        val draft = write("a-draft", status = "DRAFT")

        val related = shop("/blog/posts/$post/related?limit=12")
        assertEquals(HttpStatusCode.OK, related.status, related.bodyAsText())
        val posts = Json.parseToJsonElement(related.bodyAsText()).jsonArray.map { it.jsonObject }
        assertEquals(listOf("new-style", "old-style", "newest-care", "other-care"), posts.map { it.text("slug") })
        assertEquals(ContractSchemas.propertiesOf("BlogPostSummary").toSet(), posts.first().keys)
        assertEquals(3, Json.parseToJsonElement(shop("/blog/posts/$post/related").bodyAsText()).jsonArray.size)
        for (limit in listOf("13", "0", "abc")) assertEquals(listOf("limit NUMBER_OUT_OF_RANGE"), shop("/blog/posts/$post/related?limit=$limit").errors(), limit)
        assertEquals(HttpStatusCode.NotFound, shop("/blog/posts/$draft/related").status)
        assertEquals(HttpStatusCode.NotFound, shop("/blog/posts/999999/related").status)
    }

    @Test
    fun `a guest votes once per address - again replaces it - another address and a logged-in reader each count apart`() = panel.app(trustedProxyHops = 1) {
        val post = write("voted")

        assertEquals(HttpStatusCode.NoContent, vote(post, """{"helpful":true}""", ip = "203.0.113.7").status)
        assertEquals(HttpStatusCode.NoContent, vote(post, """{"helpful":false,"comment":"مثال نداشت"}""", ip = "203.0.113.7").status)
        assertEquals(HttpStatusCode.NoContent, vote(post, """{"helpful":true,"comment":"این نباید بمونه"}""", ip = "198.51.100.4").status)
        assertEquals(HttpStatusCode.NoContent, vote(post, """{"helpful":true}""", who = editor, ip = "203.0.113.7").status)

        val votes = votesOf(post)
        assertEquals(3, votes.size)
        assertEquals(Triple("u:${editor.userId}", true, null), votes.single { it.first.startsWith("u:") })
        assertEquals(listOf(false to "مثال نداشت", true to null), votes.filter { it.first.startsWith("ip:") }.map { it.second to it.third }.sortedBy { it.first })
        val page = shop("/blog/posts/$post").json().getValue("helpfulStats").jsonObject
        assertEquals(listOf("2", "1"), listOf(page.text("helpfulCount"), page.text("notHelpfulCount")))
        val panelPost = client.get("$API_V1/admin/blog/posts/$post") { header(HttpHeaders.Authorization, editor.bearer) }.json()
        assertEquals(listOf("مثال نداشت"), panelPost.getValue("feedbackComments").jsonArray.map { it.jsonObject.text("comment") })
    }

    @Test
    fun `the address itself is nowhere in the database - only its keyed hash`() = panel.app(trustedProxyHops = 1) {
        val post = write("hashed")
        val ip = "203.0.113.7"

        vote(post, """{"helpful":false,"comment":"کوتاه بود"}""", ip = ip)

        val key = votesOf(post).single().first
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(PanelTestStack.VOTER_PEPPER, "HmacSHA256")) }
        assertEquals("ip:" + mac.doFinal(ip.toByteArray()).joinToString("") { "%02x".format(it) }, key)
        val plain = MessageDigest.getInstance("SHA-256").digest(ip.toByteArray()).joinToString("") { "%02x".format(it) }
        assertTrue(plain !in key, "a plain SHA-256 of an address is reversed by trying them all")
        val everything = transaction(db) { exec("SELECT string_agg(t::text, ' ') FROM blogfeedback_tbl t") { it.next(); it.getString(1) } }.orEmpty()
        assertTrue(ip !in everything && "203.0.113" !in everything, everything)
    }

    @Test
    fun `helpful is required - a long comment is 400 - a post the shop doesn't see is 404 - the 31st vote in an hour is 429`() = panel.app {
        val post = write("limited")
        val scheduled = write("scheduled", extra = ""","publishedAt":"${clock.now.plus(Duration.ofHours(1))}"""")

        assertEquals(listOf("helpful FIELD_REQUIRED"), vote(post, """{"comment":"چیزی"}""").errors())
        assertEquals(listOf("comment TEXT_TOO_LONG"), vote(post, """{"helpful":false,"comment":"${"ا".repeat(301)}"}""").errors())
        assertEquals(HttpStatusCode.NotFound, vote(scheduled, """{"helpful":true}""").status)
        assertEquals(HttpStatusCode.NotFound, vote("999999", """{"helpful":true}""").status)
        assertEquals(emptyList(), votesOf(scheduled))

        repeat(26) { assertEquals(HttpStatusCode.NoContent, vote(post, """{"helpful":true}""").status, "vote ${it + 5}") }
        assertEquals(HttpStatusCode.TooManyRequests, vote(post, """{"helpful":true}""").status)
    }
}
