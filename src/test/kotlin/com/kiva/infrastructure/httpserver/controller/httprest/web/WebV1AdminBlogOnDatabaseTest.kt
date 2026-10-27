package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.ContractSchemas
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The panel's blog (`/admin/blog/posts*`; ADMIN, CONTENT_EDITOR; roadmap phase 15, step 15.2) on kiva_test. */
class WebV1AdminBlogOnDatabaseTest {

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

    private fun body(
        slug: String = "bag-by-body-shape",
        status: String? = null,
        featured: Boolean = false,
        blocks: String = """[{"type":"HEADING","text":"چرا فرم بدن مهمه؟"},{"type":"PARAGRAPH","text":"کیف فقط یه وسیله نیست."}]""",
        extra: String = "",
    ) = """{"title":"راهنمای انتخاب کیف","slug":"$slug","categoryId":${categoryId()},"lead":"قد و فرم شانه مهمه.","blocks":$blocks,"tags":["فرم بدن"],"isFeatured":$featured${status?.let { ""","status":"$it"""" } ?: ""}$extra}"""

    private fun categoryId(slug: String = "style"): Long = transaction(db) {
        exec("SELECT \"pId\" FROM blogcategory_tbl WHERE slug = '$slug'") { it.next(); it.getLong(1) }!!
    }

    private suspend fun ApplicationTestBuilder.create(body: String, who: PanelTestStack.Caller = editor): HttpResponse =
        client.post("$API_V1/admin/blog/posts") { header(HttpHeaders.Authorization, who.bearer); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun ApplicationTestBuilder.update(id: Any, body: String, who: PanelTestStack.Caller = editor): HttpResponse =
        client.put("$API_V1/admin/blog/posts/$id") { header(HttpHeaders.Authorization, who.bearer); contentType(ContentType.Application.Json); setBody(body) }

    private suspend fun ApplicationTestBuilder.read(path: String, who: PanelTestStack.Caller = editor): HttpResponse =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, who.bearer) }

    private suspend fun ApplicationTestBuilder.blogPhoto(): String {
        val uploaded = client.post("$API_V1/admin/media") {
            header(HttpHeaders.Authorization, editor.bearer)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("purpose", "BLOG")
                        append("file", FakeMediaProcessor.photo(2000, 1500), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"a.jpg\"") })
                    },
                ),
            )
        }
        assertEquals(HttpStatusCode.Created, uploaded.status, uploaded.bodyAsText())
        return uploaded.json().text("id")!!
    }

    @Test
    fun `a new post is a draft - 201 with the whole post - in the panel's list, and the change is logged`() = panel.app {
        val made = create(body())

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        val post = made.json()
        assertEquals(listOf("DRAFT", "false", "bag-by-body-shape", "تیم کیوا", "s0"), listOf(post.text("status"), post.text("scheduled"), post.text("slug"), post.getValue("author").jsonObject.text("name"), post.getValue("toc").jsonArray[0].jsonObject.text("anchor")))
        assertEquals(listOf("BlogPostSummary", "BlogPostDetail", "AdminBlogPost").flatMap(ContractSchemas::propertiesOf).toSet(), post.keys)
        val list = read("/admin/blog/posts").json().getValue("items").jsonArray.map { it.jsonObject }
        assertEquals(listOf(post.text("id")), list.map { it.text("id") })
        assertEquals(0, list.single().getValue("blocks").jsonArray.size, "the list has no blocks")
        val logged = transaction(db) { AuditLogTbl.selectAll().where { (AuditLogTbl.action eq "BLOG_POST_CREATED") and (AuditLogTbl.entityId eq post.text("id")!!) }.count() }
        assertEquals(1, logged)
    }

    @Test
    fun `the form's own excerpt, reading time and SEO are apart from the page's - sent back, nothing becomes the post's own`() = panel.app {
        val post = create(body()).json()

        assertEquals("قد و فرم شانه مهمه.", post.text("excerpt"), "the page takes the excerpt from the lead")
        assertEquals("راهنمای انتخاب کیف", post.getValue("seo").jsonObject.text("title")?.substringBefore(" |"))
        assertEquals(listOf(null, null), listOf(post.text("excerptInput"), post.text("readingMinutesInput")))
        val seoInput = post.getValue("seoInput").jsonObject
        assertEquals(listOf(null, null, "false"), listOf("title", "description", "noIndex").map { seoInput.text(it) })

        val own = ""","excerpt":null,"readingMinutes":null,"seo":$seoInput"""
        val back = update(post.text("id")!!, body(extra = own))

        assertEquals(HttpStatusCode.OK, back.status, back.bodyAsText())
        val saved = back.json()
        assertEquals(listOf(null, null), listOf(saved.text("excerptInput"), saved.text("readingMinutesInput")))
        assertEquals(null, saved.getValue("seoInput").jsonObject.text("title"))
    }

    @Test
    fun `a paragraph's script and onerror are cleaned out - a relative link stays`() = panel.app {
        val html = """<p>بخون <a href=\"/products?onSale=true\">تخفیف‌ها</a><script>alert(1)</script><img src=x onerror=alert(1)></p>"""
        val made = create(body(blocks = """[{"type":"PARAGRAPH","html":"$html"}]""")).json()

        val stored = made.getValue("blocks").jsonArray.single().jsonObject.text("html")!!
        assertFalse("script" in stored || "onerror" in stored, stored)
        assertTrue("href=\"/products?onSale=true\"" in stored, stored)
    }

    @Test
    fun `a slug taken is 409 - only digits 400 - a draft product in a block 400 on its place - all the form's problems at once`() = panel.app {
        create(body())
        val draftProduct = catalog.newProduct(listOf(StoredCatalog.NewColor("lilac"))).id.value

        assertEquals(HttpStatusCode.Conflict, create(body()).status)
        assertEquals("DUPLICATE_SLUG", create(body()).json().text("code"))
        assertEquals(listOf("slug SLUG_INVALID"), create(body(slug = "123")).errors())
        val blocks = """[{"type":"HEADING","text":"یک"},{"type":"PARAGRAPH","text":"دو"},{"type":"PRODUCT","productId":$nilaId},{"type":"PRODUCT","productId":$draftProduct}]"""
        assertEquals(listOf("blocks.3.productId PRODUCT_NOT_FOUND"), create(body(slug = "with-products", blocks = blocks)).errors())
        assertEquals(
            listOf("categoryId BLOG_CATEGORY_NOT_FOUND", "authorId BLOG_AUTHOR_NOT_FOUND"),
            create(body(slug = "nowhere").replace("\"categoryId\":${categoryId()}", "\"categoryId\":999999,\"authorId\":999999")).errors(),
        )
    }

    @Test
    fun `a cover and a photo block must be the blog's photos - a photo is attached once the post is saved`() = panel.app {
        val photo = blogPhoto()

        val made = create(body(blocks = """[{"type":"IMAGE","imageMediaId":"$photo","text":"کیف هوبو"}]""", extra = ""","coverMediaId":"$photo""""))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        assertEquals(photo, made.json().getValue("cover").jsonObject.text("id"))
        assertEquals(listOf("coverMediaId MEDIA_NOT_FOUND"), create(body(slug = "missing-cover", extra = ""","coverMediaId":"med_${"9".repeat(22)}"""")).errors())
    }

    @Test
    fun `featuring a second post unfeatures the first`() = panel.app {
        val first = create(body(slug = "first", featured = true)).json().text("id")
        val second = create(body(slug = "second", featured = true)).json().text("id")

        assertEquals("false", read("/admin/blog/posts/$first").json().text("isFeatured"))
        assertEquals("true", read("/admin/blog/posts/$second").json().text("isFeatured"))
    }

    @Test
    fun `publishing - now without a date, scheduled with a later one, and published again it keeps its first date`() = panel.app {
        val id = create(body()).json().text("id")
        val published = update(id!!, body(status = "PUBLISHED")).json()
        assertEquals(listOf(clock.now.toString(), "false"), listOf(published.text("publishedAt"), published.text("scheduled")))

        clock.advance(Duration.ofMinutes(10))
        val again = update(id, body(status = "PUBLISHED").replace("راهنمای انتخاب کیف", "راهنمای تازه")).json()
        assertEquals(published.text("publishedAt"), again.text("publishedAt"))

        val later = clock.now.plus(Duration.ofHours(2)).toString()
        val scheduled = update(id, body(status = "PUBLISHED", extra = ""","publishedAt":"$later"""")).json()
        assertEquals(listOf(later, "true"), listOf(scheduled.text("publishedAt"), scheduled.text("scheduled")))
    }

    @Test
    fun `a post nobody has is 404 to read, change and delete - deleting is 204, then 404`() = panel.app {
        val id = create(body()).json().text("id")

        assertEquals(HttpStatusCode.NotFound, read("/admin/blog/posts/999999").status)
        assertEquals(HttpStatusCode.NotFound, update(999999, body()).status)
        assertEquals(HttpStatusCode.NotFound, read("/admin/blog/posts/not-a-number").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/admin/blog/posts/$id") { header(HttpHeaders.Authorization, editor.bearer) }.status)
        assertEquals(HttpStatusCode.NotFound, read("/admin/blog/posts/$id").status)
    }

    @Test
    fun `the list filters by status, words and category - an unknown status is 400`() = panel.app {
        create(body(slug = "draft-one"))
        create(body(slug = "care-one", status = "PUBLISHED").replace("\"categoryId\":${categoryId()}", "\"categoryId\":${categoryId("care")}"))

        assertEquals(listOf("care-one"), read("/admin/blog/posts?status=PUBLISHED").json().getValue("items").jsonArray.map { it.jsonObject.text("slug") })
        assertEquals(listOf("care-one"), read("/admin/blog/posts?category=care").json().getValue("items").jsonArray.map { it.jsonObject.text("slug") })
        assertEquals(2, read("/admin/blog/posts?q=راهنماي").json().getValue("items").jsonArray.size)
        assertEquals(listOf("status BLOG_POST_STATUS_INVALID"), read("/admin/blog/posts?status=LIVE").errors())
    }

    @Test
    fun `the authors are every byline - one without a post too - the default first, and a post can be signed by either`() = panel.app {
        val sara = transaction(db) {
            exec("INSERT INTO blogauthor_tbl (name, title) VALUES ('سارا', 'تیم استایل')")
            exec("SELECT \"pId\" FROM blogauthor_tbl WHERE name = 'سارا'") { it.next(); it.getLong(1) }!!
        }
        try {
            val answer = read("/admin/blog/authors")

            assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
            val authors = Json.parseToJsonElement(answer.bodyAsText()).jsonArray.map { it.jsonObject }
            assertEquals(listOf("تیم کیوا" to "true", "سارا" to "false"), authors.map { it.text("name") to it.text("isDefault") })
            assertEquals(listOf(sara.toString(), "س"), listOf(authors[1].text("id"), authors[1].text("initial")))
            assertEquals(ContractSchemas.propertiesOf("BlogAuthor").toSet() + "isDefault", authors[1].keys)
            val signed = create(body(extra = ""","authorId":$sara"""))
            assertEquals(HttpStatusCode.Created, signed.status, signed.bodyAsText())
            assertEquals("سارا", signed.json().getValue("author").jsonObject.text("name"))
        } finally {
            panel.deleteWhatWasStored()
            transaction(db) { exec("DELETE FROM blogauthor_tbl WHERE \"pId\" = $sara") }
        }
    }

    @Test
    fun `an OPERATOR is 403 on all six routes - ADMIN may`() = panel.app {
        val operator = panel.callerWith(Role.OPERATOR)
        val answers = listOf(
            read("/admin/blog/posts", operator).status,
            read("/admin/blog/posts/1", operator).status,
            create(body(), operator).status,
            update(1, body(), operator).status,
            client.delete("$API_V1/admin/blog/posts/1") { header(HttpHeaders.Authorization, operator.bearer) }.status,
            read("/admin/blog/authors", operator).status,
        )

        assertEquals(List(6) { HttpStatusCode.Forbidden }, answers)
        assertEquals(HttpStatusCode.Created, create(body(), panel.callerWith(Role.ADMIN)).status)
    }
}
